package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;
import static io.vibeminer.infrastructure.acquisition.CdnMetadataTransportTest.*;
import static io.vibeminer.infrastructure.acquisition.PinnedCdnTransportTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/**
 * Real local HTTP fixtures through a test-only forwarding client; production approved-source rules
 * remain intact.
 */
class CdnMetadataLocalHttpTest {
  @Test
  void realChunkedSuffixPreservesObservedTotalAndExact28Bytes() throws Exception {
    try (var fixture =
        new Fixture(
            exchange -> {
              assertEquals("GET", exchange.getRequestMethod());
              assertEquals("bytes=-28", exchange.getRequestHeaders().getFirst("Range"));
              assertTrue(exchange.getRequestURI().getPath().endsWith(".index"));
              exchange.getResponseHeaders().set("Content-Range", "bytes 173040-173067/173068");
              exchange.sendResponseHeaders(206, 0);
              exchange.getResponseBody().write(new byte[28]);
            })) {
      var result = fixture.metadata(Duration.ofSeconds(2)).footer(footer()).join();
      assertEquals(173068, result.observedWholeBytes());
      assertEquals(INDEX.uri(), result.actualSourceUri());
      assertEquals(28, result.bytes().length);
      assertEquals(1, fixture.requests.get());
    }
  }

  @Test
  void realHeadObservesSizeWithoutDownloadingObject() throws Exception {
    try (var fixture =
        new Fixture(
            exchange -> {
              assertEquals("HEAD", exchange.getRequestMethod());
              assertNull(exchange.getRequestHeaders().getFirst("Range"));
              assertFalse(exchange.getRequestURI().getPath().endsWith(".index"));
              exchange.getResponseHeaders().set("Content-Length", "268435456");
              exchange.getResponseHeaders().set("ETag", "\"observed\"");
              exchange.sendResponseHeaders(200, -1);
            })) {
      var result = fixture.metadata(Duration.ofSeconds(2)).size(head()).join();
      assertEquals(268435456, result.observedWholeBytes());
      assertEquals(Optional.of("\"observed\""), result.etag());
      assertEquals(DATA.uri(), result.actualSourceUri());
    }
  }

  @Test
  void ignoredSuffixRedirectAndUnsatisfiedRangeNeverRetry() throws Exception {
    for (int status : new int[] {200, 302, 416})
      try (var fixture =
          new Fixture(
              exchange -> {
                exchange.getResponseHeaders().set("Location", "https://evil.example");
                exchange.sendResponseHeaders(status, -1);
              })) {
        reason(
            status == 200 ? INTEGRITY : status == 416 ? UNAVAILABLE : TRANSPORT,
            fixture.metadata(Duration.ofSeconds(2)).footer(footer()));
        assertEquals(1, fixture.requests.get());
      }
  }

  @Test
  void realFooterDuplicateCoordinatesAndBodyTruncationFailClosed() throws Exception {
    try (var fixture =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().add("Content-Range", "bytes 173040-173067/173068");
              exchange.getResponseHeaders().add("Content-Range", "bytes 173040-173067/173068");
              exchange.sendResponseHeaders(206, 28);
              exchange.getResponseBody().write(new byte[28]);
            })) {
      reason(INTEGRITY, fixture.metadata(Duration.ofSeconds(2)).footer(footer()));
    }
    try (var fixture =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().set("Content-Range", "bytes 173040-173067/173068");
              exchange.sendResponseHeaders(206, 28);
              exchange.getResponseBody().write(new byte[27]);
            })) {
      assertThrows(
          CompletionException.class,
          () -> fixture.metadata(Duration.ofSeconds(2)).footer(footer()).join());
    }
    try (var fixture =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().set("Content-Range", "bytes 173040-173067/173068");
              exchange.sendResponseHeaders(206, 0);
              exchange.getResponseBody().write(new byte[29]);
            })) {
      reason(LIMIT, fixture.metadata(Duration.ofSeconds(2)).footer(footer()));
    }
  }

  @Test
  void exactIndexRangesRequireTheirAuthenticatedWholeLengthNotConfigurationHint() throws Exception {
    try (var fixture =
        new Fixture(
            exchange -> {
              assertEquals("bytes=0-3", exchange.getRequestHeaders().getFirst("Range"));
              assertTrue(exchange.getRequestURI().getPath().endsWith(".index"));
              exchange.getResponseHeaders().set("Content-Range", "bytes 0-3/173068");
              exchange.sendResponseHeaders(206, 4);
              exchange.getResponseBody().write(new byte[] {1, 2, 3, 4});
            })) {
      var ranges = new PinnedCdnRangeTransport(fixture.forwarding, CLOCK, Duration.ofSeconds(2));
      assertArrayEquals(
          new byte[] {1, 2, 3, 4},
          ranges.fetch(new CdnRangeRequest(INDEX, 173068, 0, 4, 4)).join().bytes());
      reason(INTEGRITY, ranges.fetch(new CdnRangeRequest(INDEX, 135988, 0, 4, 4)));
    }
  }

  @Test
  void realMetadataHeadersAndFooterBodyRespectDeadlines() throws Exception {
    for (boolean head : new boolean[] {false, true}) {
      var release = new CountDownLatch(1);
      try (var fixture =
          new Fixture(
              exchange -> {
                try {
                  release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException stop) {
                  Thread.currentThread().interrupt();
                }
              })) {
        var transport = fixture.metadata(Duration.ofMillis(60));
        reason(TRANSPORT, head ? transport.size(head()) : transport.footer(footer()));
      } finally {
        release.countDown();
      }
    }
    var release = new CountDownLatch(1);
    try (var fixture =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().set("Content-Range", "bytes 173040-173067/173068");
              exchange.sendResponseHeaders(206, 0);
              exchange.getResponseBody().write(1);
              exchange.getResponseBody().flush();
              try {
                release.await(2, TimeUnit.SECONDS);
              } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
              }
            })) {
      reason(TRANSPORT, fixture.metadata(Duration.ofMillis(60)).footer(footer()));
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancellingFooterLeavesConcurrentHeadOnSameClientIndependent() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture =
        new Fixture(
            exchange -> {
              if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", "1000");
                exchange.sendResponseHeaders(200, -1);
                return;
              }
              exchange.getResponseHeaders().set("Content-Range", "bytes 173040-173067/173068");
              exchange.sendResponseHeaders(206, 0);
              exchange.getResponseBody().write(1);
              exchange.getResponseBody().flush();
              entered.countDown();
              try {
                release.await(2, TimeUnit.SECONDS);
              } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
              }
            })) {
      var transport = fixture.metadata(Duration.ofSeconds(2));
      var pending = transport.footer(footer());
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      pending.cancel(true);
      assertEquals(1000, transport.size(head()).get(1, TimeUnit.SECONDS).observedWholeBytes());
      assertTrue(pending.isCancelled());
    } finally {
      release.countDown();
    }
  }

  @FunctionalInterface
  interface Script {
    void respond(HttpExchange exchange) throws IOException;
  }

  static final class Fixture implements AutoCloseable {
    final HttpServer server;
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger requests = new AtomicInteger();
    final HttpClient actual =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    final ForwardingClient forwarding;

    Fixture(Script script) throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setExecutor(workers);
      server.createContext(
          "/",
          exchange -> {
            requests.incrementAndGet();
            try {
              script.respond(exchange);
            } catch (IOException disconnected) {
              /* Expected for cancellation and deliberately truncated bodies. */
            } finally {
              exchange.close();
            }
          });
      server.start();
      forwarding = new ForwardingClient(actual, server.getAddress());
    }

    CdnMetadataTransport metadata(Duration deadline) {
      return new CdnMetadataTransport(forwarding, CLOCK, deadline);
    }

    public void close() {
      server.stop(0);
      actual.shutdownNow();
      workers.shutdownNow();
    }
  }

  static final class ForwardingClient extends CdnHttpExchangeTest.ControlledClient {
    final HttpClient actual;
    final InetSocketAddress address;

    ForwardingClient(HttpClient actual, InetSocketAddress address) {
      super(200, Map.of(), new byte[0], false);
      this.actual = actual;
      this.address = address;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest canonical, HttpResponse.BodyHandler<T> handler) {
      assertTrue(canonical.uri().equals(INDEX.uri()) || canonical.uri().equals(DATA.uri()));
      var local =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://"
                          + address.getHostString()
                          + ":"
                          + address.getPort()
                          + canonical.uri().getPath()))
              .timeout(canonical.timeout().orElseThrow());
      canonical
          .headers()
          .map()
          .forEach((name, values) -> values.forEach(value -> local.header(name, value)));
      var wire =
          actual.sendAsync(
              local.method(canonical.method(), HttpRequest.BodyPublishers.noBody()).build(),
              handler);
      CompletableFuture<HttpResponse<T>> result =
          wire.thenApply(
              response ->
                  new HttpResponse<T>() {
                    public int statusCode() {
                      return response.statusCode();
                    }

                    public HttpRequest request() {
                      return canonical;
                    }

                    public Optional<HttpResponse<T>> previousResponse() {
                      return Optional.empty();
                    }

                    public HttpHeaders headers() {
                      return response.headers();
                    }

                    public T body() {
                      return response.body();
                    }

                    public Optional<SSLSession> sslSession() {
                      return response.sslSession();
                    }

                    public URI uri() {
                      return canonical.uri();
                    }

                    public Version version() {
                      return response.version();
                    }
                  });
      result.whenComplete(
          (value, failure) -> {
            if (result.isCancelled()) wire.cancel(true);
          });
      return result;
    }
  }
}
