package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Strict single-range HTTPS acquisition. It never retries as a full-object request or publishes
 * storage aliases.
 */
public final class PinnedCdnRangeTransport {
  private static final Pattern CONTENT_RANGE =
      Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)", Pattern.CASE_INSENSITIVE);
  private final HttpClient client;
  private final Clock clock;
  private final Duration deadline;

  public PinnedCdnRangeTransport() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        Clock.systemUTC(),
        Duration.ofSeconds(15));
  }

  public PinnedCdnRangeTransport(HttpClient client, Clock clock, Duration deadline) {
    this.client = Objects.requireNonNull(client);
    this.clock = Objects.requireNonNull(clock);
    this.deadline = Objects.requireNonNull(deadline);
    if (client.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("Range transport must reject redirects");
    if (deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Invalid range acquisition deadline");
  }

  public CompletableFuture<AcquiredRange> fetch(CdnRangeRequest request) {
    Objects.requireNonNull(request);
    var subscriber = new AtomicReference<PinnedCdnTransport.LimitedBody>();
    var stopped = new AtomicBoolean();
    var result = new CompletableFuture<AcquiredRange>();
    var http =
        HttpRequest.newBuilder(request.uri())
            .timeout(deadline)
            .header("Range", request.rangeHeader())
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "VibeMiner/0.1 pinned-range-acquisition")
            .GET()
            .build();
    CompletableFuture<HttpResponse<byte[]>> wire;
    try {
      wire =
          client.sendAsync(
              http,
              info -> {
                var body = new PinnedCdnTransport.LimitedBody(request.count());
                try {
                  validateHeaders(info.statusCode(), info.headers(), request);
                } catch (RuntimeException failure) {
                  body.fail(classify(failure));
                }
                // Cancellation may precede late headers; both registration orders must stop demand.
                subscriber.set(body);
                if (stopped.get()) {
                  body.cancel();
                }
                return body;
              });
    } catch (RuntimeException failure) {
      result.completeExceptionally(classify(failure));
      return result;
    }
    // This deadline covers delayed headers and a stalled body, not just connection setup.
    var timeout =
        DEADLINES.schedule(
            () -> {
              if (result.completeExceptionally(
                  new AcquisitionException(TRANSPORT, "CDN range acquisition deadline exceeded")))
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
            validateHeaders(response.statusCode(), response.headers(), request);
            if (!request.uri().equals(response.uri()))
              throw new AcquisitionException(INTEGRITY, "Range response source changed");
            if (response.body().length != request.count())
              throw new AcquisitionException(INTEGRITY, "Range response body length mismatch");
            String hash =
                HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
            result.complete(
                new AcquiredRange(request, response.uri(), clock.instant(), response.body(), hash));
          } catch (java.security.NoSuchAlgorithmException impossible) {
            result.completeExceptionally(new IllegalStateException(impossible));
          } catch (RuntimeException error) {
            result.completeExceptionally(classify(error));
          }
        });
    return result;
  }

  private static void stop(
      AtomicBoolean stopped,
      AtomicReference<PinnedCdnTransport.LimitedBody> subscriber,
      CompletableFuture<?> wire) {
    stopped.set(true);
    var body = subscriber.get();
    if (body != null) body.cancel();
    wire.cancel(true);
  }

  private static void validateHeaders(int status, HttpHeaders headers, CdnRangeRequest request) {
    if (status == 404 || status == 410 || status == 416)
      throw new AcquisitionException(UNAVAILABLE, "Pinned range unavailable (HTTP " + status + ")");
    if (status == 200)
      throw new AcquisitionException(INTEGRITY, "Official server ignored exact range request");
    if (status != 206)
      throw new AcquisitionException(TRANSPORT, "Official range source returned HTTP " + status);
    // Validate representation headers before subscribing to any response bytes.
    String encoding = single(headers, "Content-Encoding", false);
    if (encoding != null && !encoding.equalsIgnoreCase("identity"))
      throw new AcquisitionException(INTEGRITY, "Unexpected range content encoding");
    String type = single(headers, "Content-Type", false);
    if (type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/"))
      throw new AcquisitionException(INTEGRITY, "Multipart range response is unsupported");

    // The complete pinned interval and total must agree; no wildcard or multipart ranges.
    String range = single(headers, "Content-Range", true);
    var matcher = CONTENT_RANGE.matcher(range);
    if (!matcher.matches())
      throw new AcquisitionException(INTEGRITY, "Malformed range coordinates");
    if (decimal(matcher.group(1)) != request.start()
        || decimal(matcher.group(2)) != request.endInclusive()
        || decimal(matcher.group(3)) != request.wholeEncodedLength())
      throw new AcquisitionException(INTEGRITY, "Range coordinates differ from pinned request");

    // Absent Content-Length is valid for chunked HTTP, but actual bytes remain exactly bounded.
    String length = single(headers, "Content-Length", false);
    if (length != null && decimal(length) != request.count())
      throw new AcquisitionException(INTEGRITY, "Declared range length mismatch");
    String transfer = single(headers, "Transfer-Encoding", false);
    if (transfer != null && (length != null || !transfer.equalsIgnoreCase("chunked")))
      throw new AcquisitionException(INTEGRITY, "Ambiguous or unsupported range transfer encoding");
  }

  private static String single(HttpHeaders headers, String name, boolean required) {
    var values = headers.allValues(name);
    if (values.size() > 1 || (required && values.isEmpty()))
      throw new AcquisitionException(INTEGRITY, "Missing or duplicate " + name);
    return values.isEmpty() ? null : values.getFirst();
  }

  private static long decimal(String value) {
    if (!value.matches("[0-9]+"))
      throw new AcquisitionException(INTEGRITY, "Invalid decimal range field");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw new AcquisitionException(INTEGRITY, "Range field overflow", overflow);
    }
  }

  private static Throwable classify(Throwable failure) {
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) failure = failure.getCause();
    if (failure instanceof AcquisitionException) return failure;
    if (failure instanceof CancellationException)
      return new AcquisitionException(CANCELLED, "Range acquisition cancelled", failure);
    return new AcquisitionException(TRANSPORT, "Official CDN range transport failed", failure);
  }

  private static final ScheduledThreadPoolExecutor DEADLINES;

  static {
    DEADLINES =
        new ScheduledThreadPoolExecutor(
            1,
            task -> {
              var thread = new Thread(task, "vibeminer-cdn-range-deadlines");
              thread.setDaemon(true);
              return thread;
            });
    DEADLINES.setRemoveOnCancelPolicy(true);
  }
}
