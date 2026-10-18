package io.vibeminer.infrastructure.discovery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** HTTPS metadata transport with bounded bodies, deadlines and no redirects. */
final class BoundedMetadataHttp implements MetadataTransport {
    private final HttpClient client;
    private final Clock clock;

    BoundedMetadataHttp(HttpClient client, Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.clock = Objects.requireNonNull(clock);
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Metadata client must reject redirects");
        }
    }

    @Override public CompletionStage<CapturedMetadata> fetch(URI uri) {
        if (!"https".equals(uri.getScheme()) || !"us.version.battle.net".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().equals("/v2/summary") || uri.getPath().matches("/v2/products/[a-z][a-z0-9_-]{0,63}/(versions|cdns)"))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Metadata source is not approved"));
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept-Encoding", "identity").header("User-Agent", "VibeMiner/0.1 metadata-discovery").GET().build();
        var body = new java.util.concurrent.atomic.AtomicReference<LimitedBodySubscriber>();
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        var exchange = client.sendAsync(request, info -> {
            var subscriber = new LimitedBodySubscriber(BlizzardMetadataParser.MAX_DOCUMENT_BYTES);
            body.set(subscriber);
            if (stopped.get()) subscriber.cancel();
            return subscriber;
        });
        var result = exchange.thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new java.util.concurrent.CompletionException(new IOException("Official metadata HTTP status " + response.statusCode()));
                    }
                    var encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
                    if (!encoding.equalsIgnoreCase("identity")) {
                        throw new MetadataFormatException("Unexpected metadata transfer encoding");
                    }
                    return new CapturedMetadata(uri, clock.instant(), response.body());
                });
        result.whenComplete((metadata, failure) -> {
            if (result.isCancelled()) {
                stopped.set(true);
                var subscriber = body.get();
                if (subscriber != null) subscriber.cancel();
                exchange.cancel(true);
            }
        });
        return result;
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES;
        static {
            DEADLINES = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
                var thread = new Thread(task, "vibeminer-metadata-deadlines");
                thread.setDaemon(true);
                return thread;
            });
            DEADLINES.setRemoveOnCancelPolicy(true);
        }

        LimitedBodySubscriber(int maximum) { this(maximum, Duration.ofSeconds(10)); }
        LimitedBodySubscriber(int maximum, Duration deadline) {
            if (maximum < 1 || deadline.isZero() || deadline.isNegative()) throw new IllegalArgumentException("Invalid response limits");
            this.maximum = maximum;
            var timeout = DEADLINES.schedule(() -> {
                fail(new java.net.http.HttpTimeoutException("Metadata response body deadline exceeded"));
            }, deadline.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            result.whenComplete((body, failure) -> timeout.cancel(false));
        }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null || result.isDone()) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new MetadataFormatException("Metadata response exceeds size limit"));
                    return;
                }
                var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        private synchronized void fail(Throwable failure) {
            if (subscription != null) subscription.cancel();
            result.completeExceptionally(failure);
        }
        public synchronized void cancel() {
            if (subscription != null) subscription.cancel();
            result.cancel(true);
        }
        @Override public synchronized void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
