package io.vibeminer.infrastructure.discovery;

import io.vibeminer.application.ProductDiscoveryService;
import io.vibeminer.domain.ProductCatalogue;
import io.vibeminer.domain.SourceDocument;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;

/** Bounded explicit official summary refresh; construction performs no network I/O. */
public final class OfficialProductDiscoveryService implements ProductDiscoveryService {
    private static final URI SUMMARY = URI.create("https://us.version.battle.net/v2/summary");
    private final MetadataTransport transport;
    private final Semaphore refreshes = new Semaphore(2);
    public OfficialProductDiscoveryService() {
        this(new BoundedMetadataHttp(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC()));
    }
    OfficialProductDiscoveryService(MetadataTransport transport) { this.transport = Objects.requireNonNull(transport); }
    @Override public CompletionStage<ProductCatalogue> refreshProducts() {
        if (!refreshes.tryAcquire()) return CompletableFuture.failedFuture(new IllegalStateException("Too many product refreshes are already running"));
        try {
            var source = transport.fetch(SUMMARY).toCompletableFuture();
            var result = source.thenApply(captured -> new ProductSummaryMapper().map(new SourceDocument(
                captured.source().toString(), captured.observedAt(), captured.sha256(), captured.bytes())));
            result.whenComplete((value, failure) -> {
                if (result.isCancelled()) source.cancel(true);
                refreshes.release();
            });
            return result;
        } catch (RuntimeException failure) {
            refreshes.release();
            return CompletableFuture.failedFuture(failure);
        }
    }
}
