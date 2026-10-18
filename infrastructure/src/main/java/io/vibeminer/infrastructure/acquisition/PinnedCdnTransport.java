package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * Bounded, explicit HTTPS acquisition. No retries, redirects, pointer refreshes or archive
 * fallback.
 */
public final class PinnedCdnTransport {
  record Response(int status, String contentEncoding, long declaredLength, byte[] bytes) {}

  @FunctionalInterface
  interface Exchange {
    CompletableFuture<Response> get(CdnRequest request, Duration deadline);
  }

  private final Exchange exchange;
  private final Clock clock;
  private final Duration deadline;

  public PinnedCdnTransport() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Clock.systemUTC(),
        Duration.ofSeconds(15));
  }

  public PinnedCdnTransport(HttpClient client, Clock clock, Duration deadline) {
    this(httpExchange(client), clock, deadline);
  }

  PinnedCdnTransport(Exchange exchange, Clock clock, Duration deadline) {
    this.exchange = Objects.requireNonNull(exchange);
    this.clock = Objects.requireNonNull(clock);
    if (deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Invalid acquisition deadline");
    this.deadline = deadline;
  }

  public CompletableFuture<AcquiredBytes> fetch(CdnRequest request) {
    Objects.requireNonNull(request);
    CompletableFuture<Response> wire;
    try {
      wire = exchange.get(request, deadline);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(classify(failure));
    }
    var result = new CompletableFuture<AcquiredBytes>();
    // The overall deadline includes delayed headers, body delivery and an injected transport.
    var timeout =
        DEADLINES.schedule(
            () -> {
              if (result.completeExceptionally(
                  new AcquisitionException(TRANSPORT, "CDN acquisition deadline exceeded")))
                wire.cancel(true);
            },
            deadline.toNanos(),
            TimeUnit.NANOSECONDS);
    result.whenComplete(
        (value, failure) -> {
          timeout.cancel(false);
          if (result.isCancelled()) wire.cancel(true);
        });
    wire.whenComplete(
        (response, failure) -> {
          if (result.isDone()) return;
          if (failure != null) {
            result.completeExceptionally(classify(failure));
            return;
          }
          try {
            if (response.status == 404 || response.status == 410)
              throw new AcquisitionException(
                  UNAVAILABLE,
                  "Pinned official loose object/configuration unavailable (HTTP "
                      + response.status
                      + ")",
                  response.status);
            if (response.status != 200)
              throw new AcquisitionException(
                  TRANSPORT,
                  "Pinned official CDN returned HTTP " + response.status,
                  response.status);
            if (!"identity".equalsIgnoreCase(response.contentEncoding))
              throw new AcquisitionException(
                  INTEGRITY, "Unexpected content encoding", response.status);
            if (response.bytes.length > request.maximumBytes())
              throw new AcquisitionException(LIMIT, "Transfer exceeds budget", response.status);
            if ((response.declaredLength >= 0 && response.declaredLength != response.bytes.length)
                || (request.exactBytes() >= 0 && request.exactBytes() != response.bytes.length))
              throw new AcquisitionException(
                  INTEGRITY, "Pinned response length mismatch", response.status);
            boolean config = request.kind() == CdnRequest.Kind.CONFIG;
            if (config && !digest("MD5", response.bytes).equals(request.key()))
              throw new AcquisitionException(
                  INTEGRITY, "Pinned configuration MD5 mismatch", response.status);
            result.complete(
                new AcquiredBytes(
                    request,
                    clock.instant(),
                    response.bytes,
                    digest("SHA-256", response.bytes),
                    config));
          } catch (RuntimeException error) {
            result.completeExceptionally(classify(error));
          }
        });
    return result;
  }

  private static Throwable classify(Throwable failure) {
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) failure = failure.getCause();
    if (failure instanceof AcquisitionException) return failure;
    if (failure instanceof CancellationException)
      return new AcquisitionException(CANCELLED, "CDN acquisition cancelled", failure);
    return new AcquisitionException(TRANSPORT, "Official CDN transport failed", failure);
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Exchange httpExchange(HttpClient client) {
    Objects.requireNonNull(client);
    if (client.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("CDN transport must reject redirects");
    return (request, deadline) -> {
      var body = new java.util.concurrent.atomic.AtomicReference<LimitedBody>();
      var stopped = new java.util.concurrent.atomic.AtomicBoolean();
      var http =
          HttpRequest.newBuilder(request.uri())
              .timeout(deadline)
              .header("Accept-Encoding", "identity")
              .header("User-Agent", "VibeMiner/0.1 pinned-acquisition")
              .GET()
              .build();
      var sent =
          client.sendAsync(
              http,
              info -> {
                var subscriber =
                    new LimitedBody(
                        request.exactBytes() > 0
                            ? (int) request.exactBytes()
                            : request.maximumBytes());
                int status = info.statusCode();
                if (status == 404 || status == 410)
                  subscriber.fail(
                      new AcquisitionException(
                          UNAVAILABLE, "Pinned official source unavailable", status));
                else if (status != 200)
                  subscriber.fail(
                      new AcquisitionException(
                          TRANSPORT, "Pinned official CDN returned HTTP " + status, status));
                else if (!info.headers()
                    .firstValue("Content-Encoding")
                    .orElse("identity")
                    .equalsIgnoreCase("identity"))
                  subscriber.fail(
                      new AcquisitionException(INTEGRITY, "Unexpected content encoding", status));
                else {
                  long length = info.headers().firstValueAsLong("Content-Length").orElse(-1);
                  if (length > request.maximumBytes())
                    subscriber.fail(
                        new AcquisitionException(
                            LIMIT, "Declared response exceeds budget", status));
                  else if (request.exactBytes() >= 0
                      && length >= 0
                      && length != request.exactBytes())
                    subscriber.fail(
                        new AcquisitionException(
                            INTEGRITY, "Declared response length mismatch", status));
                }
                body.set(subscriber);
                if (stopped.get()) subscriber.cancel();
                return subscriber;
              });
      var result =
          sent.thenApply(
              response ->
                  new Response(
                      response.statusCode(),
                      response.headers().firstValue("Content-Encoding").orElse("identity"),
                      response.headers().firstValueAsLong("Content-Length").orElse(-1),
                      response.body()));
      result.whenComplete(
          (value, failure) -> {
            if (result.isCancelled()) {
              stopped.set(true);
              var subscriber = body.get();
              if (subscriber != null) subscriber.cancel();
              sent.cancel(true);
            }
          });
      return result;
    };
  }

  private static final ScheduledThreadPoolExecutor DEADLINES;

  static {
    DEADLINES =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              var thread = new Thread(task, "vibeminer-cdn-deadlines");
              thread.setDaemon(true);
              return thread;
            });
    DEADLINES.setRemoveOnCancelPolicy(true);
  }

  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBody(int maximum) {
      if (maximum < 1) throw new IllegalArgumentException();
      this.maximum = maximum;
    }

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public synchronized void onSubscribe(Flow.Subscription incoming) {
      if (subscription != null || result.isDone()) {
        incoming.cancel();
        return;
      }
      subscription = incoming;
      incoming.request(1);
    }

    public synchronized void onNext(List<ByteBuffer> buffers) {
      if (result.isDone()) return;
      for (var buffer : buffers) {
        if (buffer.remaining() > maximum - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(
              new AcquisitionException(LIMIT, "CDN response exceeds budget"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    synchronized void fail(Throwable failure) {
      if (subscription != null) subscription.cancel();
      result.completeExceptionally(failure);
    }

    public synchronized void cancel() {
      if (subscription != null) subscription.cancel();
      result.cancel(true);
    }

    public synchronized void onError(Throwable failure) {
      result.completeExceptionally(failure);
    }

    public synchronized void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
