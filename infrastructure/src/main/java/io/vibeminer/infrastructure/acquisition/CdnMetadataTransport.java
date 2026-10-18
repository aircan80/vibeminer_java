package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Dedicated bounded observations. Neither footer suffixes nor HEAD metadata establish whole-object
 * integrity.
 */
public final class CdnMetadataTransport {
  private static final Pattern CONTENT_RANGE =
      Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)", Pattern.CASE_INSENSITIVE);
  private final HttpClient client;
  private final Clock clock;
  private final Duration deadline;

  private record Captured(URI uri, Instant time, long whole, byte[] bytes, HttpHeaders headers) {}

  public CdnMetadataTransport() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Clock.systemUTC(),
        Duration.ofSeconds(15));
  }

  public CdnMetadataTransport(HttpClient client, Clock clock, Duration deadline) {
    this.client = Objects.requireNonNull(client);
    this.clock = Objects.requireNonNull(clock);
    this.deadline = Objects.requireNonNull(deadline);
    if (client.followRedirects() != HttpClient.Redirect.NEVER
        || deadline.isNegative()
        || deadline.isZero()
        || deadline.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException(
          "Metadata transport requires no redirects and a bounded positive deadline");
  }

  public CompletableFuture<IndexFooterObservation> footer(CdnIndexFooterRequest request) {
    Objects.requireNonNull(request);
    return execute(
        request.resource().uri(),
        true,
        request.maximumWholeBytes(),
        captured ->
            new IndexFooterObservation(
                request,
                captured.uri,
                captured.time,
                captured.whole,
                captured.bytes,
                sha256(captured.bytes),
                optional(captured.headers, "ETag"),
                optional(captured.headers, "Last-Modified")));
  }

  public CompletableFuture<DataSizeObservation> size(CdnSizeRequest request) {
    Objects.requireNonNull(request);
    return execute(
        request.resource().uri(),
        false,
        request.maximumWholeBytes(),
        captured ->
            new DataSizeObservation(
                request,
                captured.uri,
                captured.time,
                captured.whole,
                optional(captured.headers, "ETag"),
                optional(captured.headers, "Last-Modified")));
  }

  private <T> CompletableFuture<T> execute(
      URI uri, boolean suffix, long maximumWhole, Function<Captured, T> finish) {
    var stopped = new AtomicBoolean();
    var subscriber = new AtomicReference<MetadataBody>();
    var result = new CompletableFuture<T>();
    var builder =
        HttpRequest.newBuilder(uri)
            .timeout(deadline)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "VibeMiner/0.1 bounded-source-metadata");
    if (suffix) builder.header("Range", "bytes=-28").GET();
    else builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
    CompletableFuture<HttpResponse<byte[]>> wire;
    try {
      wire =
          client.sendAsync(
              builder.build(),
              info -> {
                var body = new MetadataBody(suffix ? 28 : 0);
                try {
                  validate(info.statusCode(), info.headers(), suffix, maximumWhole);
                } catch (RuntimeException failure) {
                  body.fail(classify(failure));
                }
                // A cancellation can race response-header delivery; both registration orders stop
                // demand.
                subscriber.set(body);
                if (stopped.get()) body.cancel();
                return body;
              });
    } catch (RuntimeException failure) {
      result.completeExceptionally(classify(failure));
      return result;
    }
    var timeout =
        DEADLINES.schedule(
            () -> {
              if (result.completeExceptionally(
                  new AcquisitionException(
                      TRANSPORT, "CDN metadata observation deadline exceeded")))
                stop(stopped, subscriber, wire);
            },
            deadline.toNanos(),
            TimeUnit.NANOSECONDS);
    result.whenComplete(
        (value, failure) -> {
          timeout.cancel(false);
          if (result.isCancelled()) stop(stopped, subscriber, wire);
        });
    wire.whenComplete(
        (response, failure) -> {
          if (result.isDone()) return;
          if (failure != null) {
            result.completeExceptionally(classify(failure));
            return;
          }
          try {
            long whole = validate(response.statusCode(), response.headers(), suffix, maximumWhole);
            if (!uri.equals(response.uri()))
              throw new AcquisitionException(
                  INTEGRITY, "Metadata source URI changed", response.statusCode());
            if (response.body().length != (suffix ? 28 : 0))
              throw new AcquisitionException(
                  INTEGRITY, "Metadata response body length mismatch", response.statusCode());
            result.complete(
                finish.apply(
                    new Captured(
                        response.uri(),
                        clock.instant(),
                        whole,
                        response.body(),
                        response.headers())));
          } catch (RuntimeException error) {
            result.completeExceptionally(classify(error));
          }
        });
    return result;
  }

  private static long validate(int status, HttpHeaders headers, boolean suffix, long maximumWhole) {
    try {
      return validateResponse(status, headers, suffix, maximumWhole);
    } catch (AcquisitionException failure) {
      throw new AcquisitionException(
          failure.reason(), failure.getMessage(), failure.getCause(), status);
    }
  }

  private static long validateResponse(
      int status, HttpHeaders headers, boolean suffix, long maximumWhole) {
    if (status == 404 || status == 410 || status == 416)
      throw new AcquisitionException(
          UNAVAILABLE, "Official metadata resource unavailable (HTTP " + status + ")");
    if (suffix && status == 200)
      throw new AcquisitionException(INTEGRITY, "Server ignored index footer suffix request");
    if (status != (suffix ? 206 : 200))
      throw new AcquisitionException(TRANSPORT, "Official metadata source returned HTTP " + status);
    String encoding = single(headers, "Content-Encoding", false);
    if (encoding != null && !encoding.equalsIgnoreCase("identity"))
      throw new AcquisitionException(INTEGRITY, "Unexpected metadata content encoding");
    String type = single(headers, "Content-Type", false);
    if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/"))
      throw new AcquisitionException(INTEGRITY, "Multipart source metadata is unsupported");
    single(headers, "ETag", false);
    single(headers, "Last-Modified", false);
    String length = single(headers, "Content-Length", !suffix),
        transfer = single(headers, "Transfer-Encoding", false);
    if (transfer != null && (!suffix || length != null || !transfer.equalsIgnoreCase("chunked")))
      throw new AcquisitionException(INTEGRITY, "Ambiguous metadata transfer framing");
    long whole;
    if (suffix) {
      String range = single(headers, "Content-Range", true);
      var matcher = CONTENT_RANGE.matcher(range);
      if (!matcher.matches())
        throw new AcquisitionException(INTEGRITY, "Malformed footer Content-Range");
      long start = decimal(matcher.group(1)), end = decimal(matcher.group(2));
      whole = decimal(matcher.group(3));
      if (whole < 28 || start != whole - 28 || end != whole - 1)
        throw new AcquisitionException(INTEGRITY, "Footer is not the exact final28 bytes");
      if (length != null && decimal(length) != 28)
        throw new AcquisitionException(INTEGRITY, "Declared footer length differs from28");
    } else {
      if (!headers.allValues("Content-Range").isEmpty())
        throw new AcquisitionException(
            INTEGRITY, "HEAD size observation cannot be a partial range");
      whole = decimal(length);
      if (whole < 1) throw new AcquisitionException(INTEGRITY, "HEAD object size must be positive");
    }
    if (whole > maximumWhole)
      throw new AcquisitionException(LIMIT, "Observed whole resource size exceeds policy");
    return whole;
  }

  private static Optional<String> optional(HttpHeaders headers, String name) {
    return Optional.ofNullable(single(headers, name, false));
  }

  private static String single(HttpHeaders headers, String name, boolean required) {
    var values = headers.allValues(name);
    if (values.size() > 1 || (required && values.isEmpty()))
      throw new AcquisitionException(INTEGRITY, "Missing or duplicate " + name);
    return values.isEmpty() ? null : values.getFirst();
  }

  private static long decimal(String value) {
    if (!value.matches("[0-9]+"))
      throw new AcquisitionException(INTEGRITY, "Invalid metadata decimal field");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw new AcquisitionException(INTEGRITY, "Metadata integer overflow", overflow);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Throwable classify(Throwable failure) {
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) failure = failure.getCause();
    if (failure instanceof AcquisitionException) return failure;
    if (failure instanceof CancellationException)
      return new AcquisitionException(CANCELLED, "Metadata observation cancelled", failure);
    return new AcquisitionException(TRANSPORT, "Official metadata transport failed", failure);
  }

  private static void stop(
      AtomicBoolean stopped, AtomicReference<MetadataBody> subscriber, CompletableFuture<?> wire) {
    stopped.set(true);
    var body = subscriber.get();
    if (body != null) body.cancel();
    wire.cancel(true);
  }

  private static final ScheduledThreadPoolExecutor DEADLINES;

  static {
    DEADLINES =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              var thread = new Thread(task, "vibeminer-source-metadata-deadlines");
              thread.setDaemon(true);
              return thread;
            });
    DEADLINES.setRemoveOnCancelPolicy(true);
  }

  /** HEAD allows zero bytes; footer allows28. Bounds apply before copying any incoming buffer. */
  private static final class MetadataBody implements HttpResponse.BodySubscriber<byte[]> {
    final int maximum;
    final ByteArrayOutputStream bytes;
    final CompletableFuture<byte[]> result = new CompletableFuture<>();
    Flow.Subscription subscription;

    MetadataBody(int maximum) {
      this.maximum = maximum;
      bytes = new ByteArrayOutputStream(maximum);
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
          fail(
              new AcquisitionException(
                  maximum == 0 ? INTEGRITY : LIMIT,
                  maximum == 0
                      ? "HEAD delivered an unexpected body"
                      : "Footer body exceeds28 bytes"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    public synchronized void onError(Throwable failure) {
      result.completeExceptionally(failure);
    }

    public synchronized void onComplete() {
      result.complete(bytes.toByteArray());
    }

    synchronized void fail(Throwable failure) {
      if (subscription != null) subscription.cancel();
      result.completeExceptionally(failure);
    }

    synchronized void cancel() {
      if (subscription != null) subscription.cancel();
      result.cancel(true);
    }
  }
}
