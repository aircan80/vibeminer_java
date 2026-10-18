package io.vibeminer.infrastructure.acquisition;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.vibeminer.infrastructure.acquisition.PinnedCdnTransportTest.*;
import static io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason.*;

class CdnHttpExchangeTest {
    @Test void realWireAdapterRequestsOnlyPinnedHttpsWithIdentityEncodingAndTimeout() throws Exception {
        byte[] bytes = {1,2,3}; var client = new ControlledClient(200, Map.of("Content-Length", List.of("3")), bytes, false);
        var request = config(bytes);
        var acquired = new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(request).join();
        assertEquals(request.uri(), client.request.uri()); assertEquals("GET", client.request.method());
        assertEquals("identity", client.request.headers().firstValue("Accept-Encoding").orElseThrow());
        assertEquals(Duration.ofSeconds(1), client.request.timeout().orElseThrow());
        assertArrayEquals(bytes, acquired.bytes());
    }
    @Test void rejectsRedirectAndOversizedDeclaredBodiesBeforeDemandingBytes() throws Exception {
        for (int status : new int[] {301,302,307,308}) {
            var client = new ControlledClient(status, Map.of("Location", List.of("https://evil.example")), new byte[100], false);
            reason(TRANSPORT, new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(config(new byte[] {1})));
            assertTrue(client.subscriptionCancelled); assertEquals(0, client.demand);
        }
        var client = new ControlledClient(200, Map.of("Content-Length", List.of("1048577")), new byte[100], false);
        reason(LIMIT, new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(config(new byte[] {1})));
        assertEquals(0, client.demand);
    }
    @Test void stallsAfterHeadersAreBoundedAndCancellationStopsBody() throws Exception {
        var client = new ControlledClient(200, Map.of(), new byte[0], true);
        var transport = new PinnedCdnTransport(client, CLOCK, Duration.ofMillis(50));
        reason(TRANSPORT, transport.fetch(config(new byte[] {1})));
        assertTrue(client.bodyCancelled.await(1, TimeUnit.SECONDS));
        assertTrue(client.subscriptionCancelled);
    }
    @Test void encodedLengthLimitsActualChunkStreamWithoutTrustingHeader() {
        var client = new ControlledClient(200, Map.of(), new byte[] {1,2,3,4}, false);
        reason(LIMIT, new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(
                CdnRequest.loose(SOURCE, "a".repeat(32), 3, 100)));
        assertTrue(client.subscriptionCancelled);
    }
    @Test void cancellationBeforeLateHeadersRejectsSubscriberWithoutDemand() throws Exception {
        var client = new DelayedHeadersClient();
        var future = new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(1)).fetch(config(new byte[] {1}));
        future.cancel(true);
        client.releaseHeaders();
        assertTrue(future.isCancelled()); assertTrue(client.subscriptionCancelled); assertEquals(0, client.demand);
    }
    static final class DelayedHeadersClient extends ControlledClient {
        HttpResponse.BodyHandler<?> handler;
        DelayedHeadersClient() { super(200, Map.of(), new byte[0], true); }
        @SuppressWarnings("unchecked")
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            this.handler = handler;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pending;
        }
        void releaseHeaders() {
            var subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                public int statusCode() { return 200; }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
                public Version version() { return Version.HTTP_1_1; }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) { demand++; }
                public void cancel() { subscriptionCancelled = true; }
            });
        }
    }
    static class ControlledClient extends HttpClient {
        final int status; final Map<String,List<String>> headers; final byte[] bytes; final boolean stall;
        HttpRequest request; volatile boolean subscriptionCancelled; int demand;
        final CountDownLatch bodyCancelled = new CountDownLatch(1);
        final CompletableFuture<HttpResponse<Object>> pending = new CompletableFuture<>();
        ControlledClient(int status, Map<String,List<String>> headers, byte[] bytes, boolean stall) {
            this.status=status; this.headers=headers; this.bytes=bytes; this.stall=stall;
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            this.request=request;
            var responseHeaders = HttpHeaders.of(headers, (a,b) -> true);
            var subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                public int statusCode() { return status; } public HttpHeaders headers() { return responseHeaders; }
                public Version version() { return Version.HTTP_1_1; }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long n) { demand++; }
                public void cancel() { subscriptionCancelled=true; bodyCancelled.countDown(); }
            });
            if (!subscriptionCancelled && !stall) { subscriber.onNext(List.of(ByteBuffer.wrap(bytes))); subscriber.onComplete(); }
            CompletableFuture<HttpResponse<T>> result = subscriber.getBody().toCompletableFuture().thenApply(body -> new HttpResponse<T>() {
                public int statusCode() { return status; } public HttpRequest request() { return request; }
                public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return responseHeaders; } public T body() { return body; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); } public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            });
            result.whenComplete((value, failure) -> { if (result.isCancelled()) pending.cancel(true); });
            return result;
        }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch(Exception e) { throw new IllegalStateException(e); } }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
    }
}
