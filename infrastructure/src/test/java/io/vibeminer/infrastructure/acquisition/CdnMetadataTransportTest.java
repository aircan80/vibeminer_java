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

class CdnMetadataTransportTest {
  static final String KEY = "0017a402f556fbece46c38dc431a2c9b";
  static final CdnResource INDEX = new CdnResource(SOURCE, KEY, CdnResource.Kind.INDEX);
  static final CdnResource DATA = new CdnResource(SOURCE, KEY, CdnResource.Kind.DATA);

  static CdnIndexFooterRequest footer() {
    return new CdnIndexFooterRequest(INDEX, 1024 * 1024);
  }

  static CdnSizeRequest head() {
    return new CdnSizeRequest(DATA, 512L * 1024 * 1024);
  }

  static Map<String, List<String>> footerHeaders() {
    return Map.of(
        "Content-Range", List.of("bytes 173040-173067/173068"), "Content-Length", List.of("28"));
  }

  static CdnHttpExchangeTest.ControlledClient client(
      int status, Map<String, List<String>> headers, byte[] body, boolean stalled) {
    return new CdnHttpExchangeTest.ControlledClient(status, headers, body, stalled);
  }

  static CdnMetadataTransport transport(HttpClient client) {
    return new CdnMetadataTransport(client, CLOCK, Duration.ofSeconds(1));
  }

  @Test
  void explicitIndexResourcePreservesExistingDataRangeApi() {
    assertEquals(
        "https://us.cdn.blizzard.com/tpr/wow/data/00/17/" + KEY + ".index", INDEX.uri().toString());
    var range = new CdnRangeRequest(INDEX, 173068, 0, 4096, 4096);
    assertEquals(INDEX.uri(), range.uri());
    assertEquals(CdnResource.Kind.INDEX, range.resourceKind());
    assertEquals(DATA.uri(), new CdnRangeRequest(SOURCE, KEY, 100, 0, 3, 3).uri());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CdnResource(SOURCE, KEY + ".index", CdnResource.Kind.INDEX));
    assertThrows(IllegalArgumentException.class, () -> new CdnIndexFooterRequest(DATA, 100));
    assertThrows(IllegalArgumentException.class, () -> new CdnIndexFooterRequest(INDEX, 27));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CdnIndexFooterRequest(INDEX, 2L * 1024 * 1024 * 1024 + 1));
    assertThrows(IllegalArgumentException.class, () -> new CdnSizeRequest(INDEX, 100));
    assertThrows(IllegalArgumentException.class, () -> new CdnSizeRequest(DATA, 0));
  }

  @Test
  void suffixObservesExactFooterWithoutClaimingFilenameOrWholeVerification() {
    byte[] bytes = new byte[28];
    var headers = new HashMap<>(footerHeaders());
    headers.put("ETag", List.of("\"metadata\""));
    var client = client(206, headers, bytes, false);
    var observed = transport(client).footer(footer()).join();
    assertEquals("bytes=-28", client.request.headers().firstValue("Range").orElseThrow());
    assertEquals("GET", client.request.method());
    assertEquals("identity", client.request.headers().firstValue("Accept-Encoding").orElseThrow());
    assertEquals(173068, observed.observedWholeBytes());
    assertEquals(173040, observed.start());
    assertEquals(CLOCK.instant(), observed.receivedAt());
    assertEquals(INDEX.uri(), observed.actualSourceUri());
    assertEquals("GET", observed.method());
    assertEquals(Optional.of("\"metadata\""), observed.etag());
    assertEquals(64, observed.footerSha256().length());
    bytes[0] = 7;
    assertEquals(0, observed.bytes()[0]);
    var copy = observed.bytes();
    copy[0] = 8;
    assertEquals(0, observed.bytes()[0]);
  }

  @Test
  void headObservesPositiveBoundedSizeWithoutBodyReadiness() {
    var client =
        client(
            200,
            Map.of(
                "Content-Length",
                List.of("268435456"),
                "ETag",
                List.of("tag"),
                "Last-Modified",
                List.of("Sun, 18 Oct 2026 00:00:00 GMT")),
            new byte[0],
            false);
    var result = transport(client).size(head()).join();
    assertEquals("HEAD", client.request.method());
    assertTrue(client.request.headers().allValues("Range").isEmpty());
    assertEquals(268435456, result.observedWholeBytes());
    assertEquals(DATA.uri(), result.actualSourceUri());
    assertEquals("HEAD", result.method());
    assertEquals(Optional.of("tag"), result.etag());
    assertTrue(result.lastModified().isPresent());
    assertEquals(CLOCK.instant(), result.receivedAt());
  }

  @Test
  void ignoredFooterRedirectAndUnavailableRejectBeforeDemand() {
    for (int status : new int[] {200, 301, 302, 307, 308, 404, 410, 416, 500}) {
      var client = client(status, Map.of(), new byte[100], false);
      reason(
          status == 200
              ? INTEGRITY
              : status == 404 || status == 410 || status == 416 ? UNAVAILABLE : TRANSPORT,
          transport(client).footer(footer()));
      assertEquals(0, client.demand);
      assertTrue(client.subscriptionCancelled);
    }
  }

  @Test
  void footerRejectsWrongIntervalsUnknownTotalOverflowDuplicatesAndOversizedWhole() {
    reason(INTEGRITY, transport(client(206, Map.of(), new byte[28], false)).footer(footer()));
    for (String range :
        List.of(
            "bytes 173039-173066/173068",
            "bytes 173040-173066/173068",
            "bytes 173040-173067/*",
            "bytes */173068",
            "bytes 0-27/29",
            "bytes 9223372036854775808-9223372036854775835/9223372036854775836",
            "bytes 0-27/0"))
      reason(
          INTEGRITY,
          transport(client(206, Map.of("Content-Range", List.of(range)), new byte[28], false))
              .footer(footer()));
    reason(
        LIMIT,
        transport(
                client(
                    206,
                    Map.of("Content-Range", List.of("bytes 1048573-1048600/1048601")),
                    new byte[28],
                    false))
            .footer(footer()));
    var headers = new HashMap<>(footerHeaders());
    headers.put(
        "Content-Range", List.of("bytes 173040-173067/173068", "bytes 173040-173067/173068"));
    reason(INTEGRITY, transport(client(206, headers, new byte[28], false)).footer(footer()));
  }

  @Test
  void footerRejectsEncodingLengthTransferAndBodyViolations() {
    for (var entry :
        List.of(
            Map.entry("Content-Length", List.of("27")),
            Map.entry("Content-Length", List.of("28", "28")),
            Map.entry("Content-Length", List.of("28,28")),
            Map.entry("Content-Encoding", List.of("gzip")),
            Map.entry("Content-Encoding", List.of("identity", "identity")),
            Map.entry("Content-Type", List.of("multipart/byteranges")),
            Map.entry("ETag", List.of("a", "b")),
            Map.entry("Transfer-Encoding", List.of("chunked")))) {
      var headers = new HashMap<>(footerHeaders());
      headers.put(entry.getKey(), entry.getValue());
      reason(INTEGRITY, transport(client(206, headers, new byte[28], false)).footer(footer()));
    }
    reason(
        INTEGRITY, transport(client(206, footerHeaders(), new byte[27], false)).footer(footer()));
    reason(LIMIT, transport(client(206, footerHeaders(), new byte[29], false)).footer(footer()));
    var chunked = new HashMap<>(footerHeaders());
    chunked.remove("Content-Length");
    chunked.put("Transfer-Encoding", List.of("chunked"));
    assertEquals(
        28,
        transport(client(206, chunked, new byte[28], false))
            .footer(footer())
            .join()
            .bytes()
            .length);
  }

  @Test
  void headRejectsMissingInvalidDuplicateOversizeAndUnexpectedRepresentation() {
    reason(INTEGRITY, transport(client(200, Map.of(), new byte[0], false)).size(head()));
    for (var values :
        List.of(
            List.of("0"),
            List.of("-1"),
            List.of("+3"),
            List.of("3", "3"),
            List.of("3,3"),
            List.of("9223372036854775808")))
      reason(
          INTEGRITY,
          transport(client(200, Map.of("Content-Length", values), new byte[0], false))
              .size(head()));
    reason(
        LIMIT,
        transport(client(200, Map.of("Content-Length", List.of("536870913")), new byte[0], false))
            .size(head()));
    for (var entry :
        List.of(
            Map.entry("Content-Encoding", List.of("gzip")),
            Map.entry("Content-Encoding", List.of("identity", "identity")),
            Map.entry("Content-Range", List.of("bytes 0-2/3")),
            Map.entry("Transfer-Encoding", List.of("chunked")),
            Map.entry("Last-Modified", List.of("a", "b")))) {
      var headers = new HashMap<String, List<String>>();
      headers.put("Content-Length", List.of("3"));
      headers.put(entry.getKey(), entry.getValue());
      reason(INTEGRITY, transport(client(200, headers, new byte[0], false)).size(head()));
    }
    reason(
        INTEGRITY,
        transport(client(200, Map.of("Content-Length", List.of("3")), new byte[] {1}, false))
            .size(head()));
  }

  @Test
  void headRejectsNon200BeforeBodyDemand() {
    for (int status : new int[] {206, 301, 302, 307, 308, 404, 410, 500}) {
      var client = client(status, Map.of("Content-Length", List.of("100")), new byte[0], false);
      reason(
          status == 404 || status == 410 ? UNAVAILABLE : TRANSPORT, transport(client).size(head()));
      assertEquals(0, client.demand);
    }
  }

  @Test
  void deadlineAndCancellationAreBoundedPerOperation() throws Exception {
    var stalled = client(206, footerHeaders(), new byte[0], true);
    reason(
        TRANSPORT,
        new CdnMetadataTransport(stalled, CLOCK, Duration.ofMillis(30)).footer(footer()));
    assertTrue(stalled.bodyCancelled.await(1, TimeUnit.SECONDS));
    var cancelled = client(200, Map.of("Content-Length", List.of("3")), new byte[0], true);
    var future = transport(cancelled).size(head());
    future.cancel(true);
    assertTrue(cancelled.bodyCancelled.await(1, TimeUnit.SECONDS));
    assertEquals(
        28,
        transport(client(206, footerHeaders(), new byte[28], false))
            .footer(footer())
            .join()
            .bytes()
            .length);
  }

  @Test
  void cancellationRejectsLateFooterAndHeadSubscribersWithoutDemand() {
    for (boolean suffix : new boolean[] {true, false}) {
      var client = new LateClient(suffix);
      CompletableFuture<?> future =
          suffix ? transport(client).footer(footer()) : transport(client).size(head());
      future.cancel(true);
      client.release();
      assertTrue(client.subscriptionCancelled);
      assertEquals(0, client.demand);
    }
  }

  @Test
  void rejectsRedirectClientsAndInvalidDeadlines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CdnMetadataTransport(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),
                CLOCK,
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CdnMetadataTransport(HttpClient.newHttpClient(), CLOCK, Duration.ZERO));
  }

  static final class LateClient extends CdnHttpExchangeTest.ControlledClient {
    HttpResponse.BodyHandler<?> handler;
    final boolean suffix;

    LateClient(boolean suffix) {
      super(
          suffix ? 206 : 200,
          suffix ? footerHeaders() : Map.of("Content-Length", List.of("3")),
          new byte[0],
          true);
      this.suffix = suffix;
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
                  return status;
                }

                public HttpHeaders headers() {
                  return HttpHeaders.of(LateClient.this.headers, (a, b) -> true);
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
