package io.vibeminer.infrastructure.discovery;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfficialProductDiscoveryServiceTest {
    @Test void constructionIsOfflineAndRefreshUsesOnlyBoundedOfficialSummarySource() {
        var requests = new ArrayList<URI>();
        var service = new OfficialProductDiscoveryService(uri -> {
            requests.add(uri);
            return CompletableFuture.completedFuture(new CapturedMetadata(uri, Instant.EPOCH,
                "Product!STRING:0|Seqn!DEC:4|Flags!STRING:0\nwowf|42|\nwowf|43|cdn\n".getBytes(StandardCharsets.UTF_8)));
        });
        assertTrue(requests.isEmpty());
        var catalogue = service.refreshProducts().toCompletableFuture().join();
        assertEquals(java.util.List.of(URI.create("https://us.version.battle.net/v2/summary")), requests);
        assertEquals("wowf", catalogue.products().getFirst().productId());
        assertEquals(Instant.EPOCH, catalogue.evidence().capturedAt());
        assertTrue(catalogue.products().getFirst().hasCurrentVersions());
    }
    @Test void cancellingRefreshCancelsUnderlyingRequestAndReleasesCapacity() {
        var requests = new ArrayList<CompletableFuture<CapturedMetadata>>();
        var service = new OfficialProductDiscoveryService(uri -> { var value = new CompletableFuture<CapturedMetadata>(); requests.add(value); return value; });
        var first = service.refreshProducts().toCompletableFuture();
        var second = service.refreshProducts().toCompletableFuture();
        assertThrows(java.util.concurrent.CompletionException.class, () -> service.refreshProducts().toCompletableFuture().join());
        first.cancel(true); assertTrue(requests.getFirst().isCancelled());
        var third = service.refreshProducts().toCompletableFuture();
        assertEquals(3, requests.size()); second.cancel(true); third.cancel(true);
    }
    @Test void synchronousTransportFailureDoesNotLeakRefreshPermit() {
        var service = new OfficialProductDiscoveryService(uri -> { throw new IllegalStateException("synthetic transport failure"); });
        for (int i=0;i<5;i++) assertThrows(java.util.concurrent.CompletionException.class, () -> service.refreshProducts().toCompletableFuture().join());
    }
}
