package io.vibeminer.infrastructure.discovery;

import org.junit.jupiter.api.Test;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import static org.junit.jupiter.api.Assertions.*;

class BoundedMetadataHttpTest {
    private static final URI SOURCE = URI.create("https://us.version.battle.net/v2/products/wow/versions");

    @Test void boundsStreamingBodyAndCancelsUpstream() {
        var subscriber = new BoundedMetadataHttp.LimitedBodySubscriber(4);
        var subscription = new TestSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5})));
        assertTrue(subscription.cancelled);
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    }

    @Test void stalledBodyHasADeadlineAndCancelsItsSubscription() {
        var subscriber = new BoundedMetadataHttp.LimitedBodySubscriber(3, Duration.ofMillis(20));
        var subscription = new TestSubscription();
        subscriber.onSubscribe(subscription);
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> subscriber.getBody().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(subscription.cancelled);
    }

    @Test void exactLimitCompletesWithoutLosingBytesAndPropagatesReadErrors() {
        var subscriber = new BoundedMetadataHttp.LimitedBodySubscriber(3);
        subscriber.onSubscribe(new TestSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1}), ByteBuffer.wrap(new byte[]{2, 3})));
        subscriber.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3}, subscriber.getBody().toCompletableFuture().join());
        var failed = new BoundedMetadataHttp.LimitedBodySubscriber(3);
        failed.onSubscribe(new TestSubscription());
        failed.onError(new java.io.IOException("truncated"));
        assertThrows(CompletionException.class, () -> failed.getBody().toCompletableFuture().join());
    }

    @Test void rejectsUnapprovedSourcesBeforeTransportAndNeverEnablesRedirects() {
        var client = new FakeClient(200, "identity");
        var transport = new BoundedMetadataHttp(client, Clock.systemUTC());
        for (var uri : List.of("http://us.version.battle.net/v2/products/wow/versions",
                "https://evil.invalid/v2/products/wow/versions",
                "https://us.version.battle.net:443/v2/products/wow/versions",
                "https://us.version.battle.net/v2/products/wow/versions?x=1",
                "https://us.version.battle.net/v2/products/../versions")) {
            assertThrows(CompletionException.class, () -> transport.fetch(URI.create(uri)).toCompletableFuture().join());
        }
        assertEquals(0, client.requests);
        try (var redirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            assertThrows(IllegalArgumentException.class, () -> new BoundedMetadataHttp(redirects, Clock.systemUTC()));
        }
    }

    @Test void cancellationPropagatesToActualHttpRequest() {
        var client = new FakeClient(200, "identity", true);
        var request = new BoundedMetadataHttp(client, Clock.systemUTC()).fetch(SOURCE).toCompletableFuture();
        request.cancel(true);
        assertTrue(client.pendingResponse.isCancelled());
    }

    @Test void verifiesStatusEncodingAndRequestDeadline() {
        var good = new FakeClient(200, "identity");
        assertArrayEquals(new byte[]{1, 2}, new BoundedMetadataHttp(good, Clock.systemUTC()).fetch(SOURCE).toCompletableFuture().join().bytes());
        assertEquals(Duration.ofSeconds(10), good.request.timeout().orElseThrow());
        assertEquals("identity", good.request.headers().firstValue("Accept-Encoding").orElseThrow());
        for (var status : new int[]{302, 404, 500}) {
            var transport = new BoundedMetadataHttp(new FakeClient(status, "identity"), Clock.systemUTC());
            assertThrows(CompletionException.class, () -> transport.fetch(SOURCE).toCompletableFuture().join());
        }
        var compressed = new BoundedMetadataHttp(new FakeClient(200, "gzip"), Clock.systemUTC());
        assertThrows(CompletionException.class, () -> compressed.fetch(SOURCE).toCompletableFuture().join());
    }

    private static final class TestSubscription implements Flow.Subscription {
        private volatile boolean cancelled;
        @Override public void request(long n) { }
        @Override public void cancel() { cancelled = true; }
    }

    private static final class FakeClient extends HttpClient {
        private final int status;
        private final String encoding;
        private final boolean pending;
        private CompletableFuture<?> pendingResponse;
        private int requests;
        private HttpRequest request;
        FakeClient(int status, String encoding) { this(status, encoding, false); }
        FakeClient(int status, String encoding, boolean pending) { this.status = status; this.encoding = encoding; this.pending = pending; }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            this.request = request;
            requests++;
            if (pending) {
                var response = new CompletableFuture<HttpResponse<T>>();
                pendingResponse = response;
                return response;
            }
            var headers = HttpHeaders.of(Map.of("Content-Encoding", List.of(encoding)), (a, b) -> true);
            var info = new HttpResponse.ResponseInfo() {
                public int statusCode() { return status; }
                public HttpHeaders headers() { return headers; }
                public Version version() { return Version.HTTP_1_1; }
            };
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(new TestSubscription());
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2})));
            subscriber.onComplete();
            return subscriber.getBody().thenApply(body -> (HttpResponse<T>) new HttpResponse<T>() {
                public int statusCode() { return status; }
                public HttpRequest request() { return request; }
                public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return headers; }
                public T body() { return body; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            }).toCompletableFuture();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { return sendAsync(request, handler); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(5)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }
}
