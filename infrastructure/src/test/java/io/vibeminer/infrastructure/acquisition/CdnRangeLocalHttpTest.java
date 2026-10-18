package io.vibeminer.infrastructure.acquisition;

import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;
import static io.vibeminer.infrastructure.acquisition.PinnedCdnRangeTransportTest.*;
import static io.vibeminer.infrastructure.acquisition.PinnedCdnTransportTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
 * Real local streaming HTTP, reached only through a test client that forwards the already validated
 * canonical request.
 */
class CdnRangeLocalHttpTest {
  @Test
  void realChunkedResponseIsExactAndRequestUsesPinnedRange() throws Exception {
    try (var server =
        new Fixture(
            exchange -> {
              assertEquals("bytes=100-102", exchange.getRequestHeaders().getFirst("Range"));
              assertEquals("identity", exchange.getRequestHeaders().getFirst("Accept-Encoding"));
              exchange.getResponseHeaders().set("Content-Range", "bytes 100-102/188000000");
              exchange.sendResponseHeaders(206, 0);
              exchange.getResponseBody().write(new byte[] {1});
              exchange.getResponseBody().write(new byte[] {2, 3});
            })) {
      var result = server.transport(Duration.ofSeconds(2)).fetch(request()).join();
      assertArrayEquals(new byte[] {1, 2, 3}, result.bytes());
      assertEquals(request().uri(), result.actualSourceUri());
      assertEquals(1, server.requests.get());
    }
  }

  @Test
  void realFixedLengthResponseRetainsExactRange() throws Exception {
    try (var server =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().set("Content-Range", "bytes 100-102/188000000");
              exchange.sendResponseHeaders(206, 3);
              exchange.getResponseBody().write(new byte[] {1, 2, 3});
            })) {
      assertEquals(
          3, server.transport(Duration.ofSeconds(2)).fetch(request()).join().bytes().length);
    }
  }

  @Test
  void realShortFixedAndOversizedChunkedBodiesNeverProduceEvidence() throws Exception {
    try (var server =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().set("Content-Range", "bytes 100-102/188000000");
              exchange.sendResponseHeaders(206, 3);
              exchange.getResponseBody().write(new byte[] {1, 2});
            })) {
      assertThrows(
          CompletionException.class,
          () -> server.transport(Duration.ofSeconds(2)).fetch(request()).join());
    }
    try (var server =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().set("Content-Range", "bytes 100-102/188000000");
              exchange.sendResponseHeaders(206, 0);
              exchange.getResponseBody().write(new byte[] {1, 2, 3, 4});
            })) {
      reason(LIMIT, server.transport(Duration.ofSeconds(2)).fetch(request()));
    }
  }

  @Test
  void realIgnoredRangeRedirectAndUnsatisfiedRangeNeverRetry() throws Exception {
    for (int status : new int[] {200, 302, 416}) {
      try (var server =
          new Fixture(
              exchange -> {
                exchange.getResponseHeaders().set("Location", "https://evil.example/");
                exchange.sendResponseHeaders(status, -1);
              })) {
        reason(
            status == 200 ? INTEGRITY : status == 416 ? UNAVAILABLE : TRANSPORT,
            server.transport(Duration.ofSeconds(2)).fetch(request()));
        assertEquals(1, server.requests.get());
      }
    }
  }

  @Test
  void realDuplicatedRangeAndWrongTotalAreRejected() throws Exception {
    for (boolean duplicate : new boolean[] {true, false}) {
      try (var server =
          new Fixture(
              exchange -> {
                exchange
                    .getResponseHeaders()
                    .add(
                        "Content-Range",
                        duplicate ? "bytes 100-102/188000000" : "bytes 100-102/188000001");
                if (duplicate)
                  exchange.getResponseHeaders().add("Content-Range", "bytes 100-102/188000000");
                exchange.sendResponseHeaders(206, 3);
                exchange.getResponseBody().write(new byte[3]);
              })) {
        reason(INTEGRITY, server.transport(Duration.ofSeconds(2)).fetch(request()));
      }
    }
  }

  @Test
  void realStalledHeadersAndBodiesRespectDeadline() throws Exception {
    for (boolean sendHeaders : new boolean[] {false, true}) {
      var release = new CountDownLatch(1);
      try (var server =
          new Fixture(
              exchange -> {
                if (sendHeaders) {
                  exchange.getResponseHeaders().set("Content-Range", "bytes 100-102/188000000");
                  exchange.sendResponseHeaders(206, 0);
                  exchange.getResponseBody().write(1);
                  exchange.getResponseBody().flush();
                }
                try {
                  release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                  Thread.currentThread().interrupt();
                }
              })) {
        reason(TRANSPORT, server.transport(Duration.ofMillis(100)).fetch(request()));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void cancellingOneRealRequestLeavesAnotherOnSameTransportIndependent() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var server =
        new Fixture(
            exchange -> {
              exchange.getResponseHeaders().set("Content-Range", "bytes 100-102/188000000");
              exchange.sendResponseHeaders(206, 0);
              if (exchange.getRequestURI().getQuery() != null)
                throw new AssertionError("No query inputs expected");
              if (Thread.currentThread().isInterrupted()) throw new IOException("Stopped");
              // The first exchange stalls; the second can finish on its independent virtual thread.
              if (entered.getCount() > 0) {
                entered.countDown();
                try {
                  release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                  Thread.currentThread().interrupt();
                }
              }
              exchange.getResponseBody().write(new byte[] {1, 2, 3});
            })) {
      var transport = server.transport(Duration.ofSeconds(2));
      var first = transport.fetch(request());
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      first.cancel(true);
      var second = transport.fetch(request());
      assertArrayEquals(new byte[] {1, 2, 3}, second.get(1, TimeUnit.SECONDS).bytes());
      assertTrue(first.isCancelled());
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
              /* Deliberate cancellation/truncation. */
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    PinnedCdnRangeTransport transport(Duration deadline) {
      return new PinnedCdnRangeTransport(
          new ForwardingClient(actual, server.getAddress()), CLOCK, deadline);
    }

    public void close() {
      server.stop(0);
      actual.shutdownNow();
      workers.shutdownNow();
    }
  }

  /**
   * The production source policy is unchanged; only this test double chooses the loopback
   * destination.
   */
  static final class ForwardingClient extends CdnHttpExchangeTest.ControlledClient {
    final HttpClient delegate;
    final InetSocketAddress address;

    ForwardingClient(HttpClient delegate, InetSocketAddress address) {
      super(206, Map.of(), new byte[0], false);
      this.delegate = delegate;
      this.address = address;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest canonical, HttpResponse.BodyHandler<T> handler) {
      assertEquals(request().uri(), canonical.uri());
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
          .forEach((key, values) -> values.forEach(value -> local.header(key, value)));
      var wire = delegate.sendAsync(local.GET().build(), handler);
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
