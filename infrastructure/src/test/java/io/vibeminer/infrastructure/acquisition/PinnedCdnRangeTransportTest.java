package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;
import static io.vibeminer.infrastructure.acquisition.PinnedCdnTransportTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class PinnedCdnRangeTransportTest {
  static final String KEY = "a".repeat(32);

  static CdnRangeRequest request() {
    return new CdnRangeRequest(SOURCE, KEY, 188_000_000, 100, 3, 16);
  }

  static Map<String, List<String>> validHeaders() {
    return Map.of("Content-Range", List.of("bytes 100-102/188000000"));
  }

  static CdnHttpExchangeTest.ControlledClient client(
      int status, Map<String, List<String>> headers, byte[] bytes, boolean stall) {
    return new CdnHttpExchangeTest.ControlledClient(status, headers, bytes, stall);
  }

  static CompletableFuture<AcquiredRange> fetch(
      int status, Map<String, List<String>> headers, byte[] bytes) {
    return new PinnedCdnRangeTransport(
            client(status, headers, bytes, false), CLOCK, Duration.ofSeconds(1))
        .fetch(request());
  }

  @Test
  void requestPinsLargeWholeObjectButOnlySmallExplicitRange() {
    var request = request();
    assertEquals("https://us.cdn.blizzard.com/tpr/wow/data/aa/aa/" + KEY, request.uri().toString());
    assertEquals("bytes=100-102", request.rangeHeader());
    assertEquals(102, request.endInclusive());
    assertEquals(
        KEY, new CdnRangeRequest(SOURCE, KEY.toUpperCase(Locale.ROOT), 5, 0, 1, 1).encodedKey());
  }

  @Test
  void requestRejectsNegativeZeroOutOfObjectOverBudgetAndOverflow() {
    for (long whole : new long[] {-1, 0, 100, 102})
      assertThrows(
          IllegalArgumentException.class, () -> new CdnRangeRequest(SOURCE, KEY, whole, 100, 3, 3));
    for (int count : new int[] {-1, 0, 4, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> new CdnRangeRequest(SOURCE, KEY, 100, 0, count, 3));
    for (int budget : new int[] {-1, 0, 64 * 1024 * 1024 + 1, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class,
          () -> new CdnRangeRequest(SOURCE, KEY, 100, 0, 1, budget));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CdnRangeRequest(SOURCE, KEY, Long.MAX_VALUE, Long.MAX_VALUE - 1, 3, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CdnRangeRequest(SOURCE, KEY, 100, -1, 3, 3));
    assertThrows(
        IllegalArgumentException.class, () -> new CdnRangeRequest(SOURCE, "../bad", 100, 0, 1, 1));
  }

  @Test
  void exact206ProducesOnlyImmutableRangeEvidenceAndCanonicalRequest() {
    byte[] bytes = {1, 2, 3};
    var client = client(206, validHeaders(), bytes, false);
    var result =
        new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(request()).join();
    assertEquals(request(), result.request());
    assertEquals(request().uri(), result.actualSourceUri());
    assertEquals(CLOCK.instant(), result.receivedAt());
    assertEquals(64, result.rangeSha256().length());
    assertEquals("bytes=100-102", client.request.headers().firstValue("Range").orElseThrow());
    assertEquals("identity", client.request.headers().firstValue("Accept-Encoding").orElseThrow());
    assertEquals("GET", client.request.method());
    assertEquals(Duration.ofSeconds(1), client.request.timeout().orElseThrow());
    bytes[0] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, result.bytes());
    var copy = result.bytes();
    copy[0] = 9;
    assertEquals(1, result.bytes()[0]);
  }

  @Test
  void acceptsExactContentLengthOrChunkedBodyAndIdentityCase() {
    for (var extra :
        List.of(
            Map.of("Content-Length", List.of("3")),
            Map.of("Transfer-Encoding", List.of("chunked")),
            Map.of("Content-Encoding", List.of("IDENTITY")))) {
      var headers = new HashMap<>(validHeaders());
      headers.putAll(extra);
      assertEquals(3, fetch(206, headers, new byte[3]).join().bytes().length);
    }
  }

  @Test
  void fullSpan206StillReturnsRangeType() {
    var client = client(206, Map.of("Content-Range", List.of("bytes 0-2/3")), new byte[3], false);
    var result =
        new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(1))
            .fetch(new CdnRangeRequest(SOURCE, KEY, 3, 0, 3, 3))
            .join();
    assertInstanceOf(AcquiredRange.class, result);
  }

  @Test
  void ignoredRangeRedirectsAndMissingObjectFailBeforeBodyDemand() {
    for (int code : new int[] {200, 301, 302, 303, 307, 308, 404, 410, 416, 500}) {
      var client =
          client(code, Map.of("Content-Length", List.of("188000000")), new byte[100], false);
      reason(
          code == 404 || code == 410 || code == 416
              ? UNAVAILABLE
              : code == 200 ? INTEGRITY : TRANSPORT,
          new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(request()));
      assertEquals(0, client.demand);
      assertTrue(client.subscriptionCancelled);
    }
  }

  @Test
  void rejectsMissingMalformedDuplicatedWrongCoordinatesAndOverflowContentRange() {
    reason(INTEGRITY, fetch(206, Map.of(), new byte[3]));
    for (String value :
        List.of(
            "bytes 99-101/188000000",
            "bytes 100-103/188000000",
            "bytes 100-102/188000001",
            "bytes 100-102/*",
            "bytes */188000000",
            "bytes 100-102/9223372036854775808",
            "bytes 9223372036854775808-102/188000000",
            "bytes 100-102/188000000, bytes 100-102/188000000",
            "items 100-102/188000000"))
      reason(INTEGRITY, fetch(206, Map.of("Content-Range", List.of(value)), new byte[3]));
    reason(
        INTEGRITY,
        fetch(
            206,
            Map.of("Content-Range", List.of("bytes 100-102/188000000", "bytes 100-102/188000000")),
            new byte[3]));
  }

  @Test
  void rejectsDuplicateConflictingMalformedLengthAndEncodingHeaders() {
    for (var entry :
        List.of(
            Map.entry("Content-Length", List.of("2")),
            Map.entry("Content-Length", List.of("3", "3")),
            Map.entry("Content-Length", List.of("3, 3")),
            Map.entry("Content-Length", List.of("+3")),
            Map.entry("Content-Length", List.of("9223372036854775808")),
            Map.entry("Content-Encoding", List.of("gzip")),
            Map.entry("Content-Encoding", List.of("identity", "identity")),
            Map.entry("Content-Type", List.of("multipart/byteranges; boundary=x")),
            Map.entry("Transfer-Encoding", List.of("gzip")),
            Map.entry("Transfer-Encoding", List.of("chunked", "chunked")))) {
      var headers = new HashMap<>(validHeaders());
      headers.put(entry.getKey(), entry.getValue());
      reason(INTEGRITY, fetch(206, headers, new byte[3]));
    }
    var headers = new HashMap<>(validHeaders());
    headers.put("Content-Length", List.of("3"));
    headers.put("Transfer-Encoding", List.of("chunked"));
    reason(INTEGRITY, fetch(206, headers, new byte[3]));
  }

  @Test
  void shortAndLongActualBodiesFailEvenWithoutContentLength() {
    reason(INTEGRITY, fetch(206, validHeaders(), new byte[2]));
    reason(LIMIT, fetch(206, validHeaders(), new byte[4]));
  }

  @Test
  void deadlineAndCancellationStopOnlyTheirOwnBodySubscriptions() throws Exception {
    var stalled = client(206, validHeaders(), new byte[0], true);
    reason(
        TRANSPORT,
        new PinnedCdnRangeTransport(stalled, CLOCK, Duration.ofMillis(40)).fetch(request()));
    assertTrue(stalled.bodyCancelled.await(1, TimeUnit.SECONDS));
    var first = client(206, validHeaders(), new byte[0], true);
    var second = client(206, validHeaders(), new byte[] {1, 2, 3}, false);
    var operation =
        new PinnedCdnRangeTransport(first, CLOCK, Duration.ofSeconds(1)).fetch(request());
    operation.cancel(true);
    assertTrue(first.bodyCancelled.await(1, TimeUnit.SECONDS));
    assertEquals(
        3,
        new PinnedCdnRangeTransport(second, CLOCK, Duration.ofSeconds(1))
            .fetch(request())
            .join()
            .bytes()
            .length);
  }

  @Test
  void cancellationBeforeLateHeadersRequestsZeroBytes() {
    var client = new LateRangeClient();
    var future = new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(request());
    future.cancel(true);
    client.release();
    assertTrue(client.subscriptionCancelled);
    assertEquals(0, client.demand);
  }

  @Test
  void refusesRedirectFollowingClientAndUnboundedDeadline() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PinnedCdnRangeTransport(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
                CLOCK,
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PinnedCdnRangeTransport(HttpClient.newHttpClient(), CLOCK, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PinnedCdnRangeTransport(HttpClient.newHttpClient(), CLOCK, Duration.ofMinutes(2)));
  }

  static final class LateRangeClient extends CdnHttpExchangeTest.ControlledClient {
    HttpResponse.BodyHandler<?> handler;

    LateRangeClient() {
      super(206, validHeaders(), new byte[0], true);
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> h) {
      handler = h;
      return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pending;
    }

    void release() {
      var subscriber =
          handler.apply(
              new HttpResponse.ResponseInfo() {
                public int statusCode() {
                  return 206;
                }

                public HttpHeaders headers() {
                  return HttpHeaders.of(validHeaders(), (a, b) -> true);
                }

                public HttpClient.Version version() {
                  return HttpClient.Version.HTTP_1_1;
                }
              });
      subscriber.onSubscribe(
          new Flow.Subscription() {
            public void request(long n) {
              demand++;
            }

            public void cancel() {
              subscriptionCancelled = true;
            }
          });
    }
  }
}
