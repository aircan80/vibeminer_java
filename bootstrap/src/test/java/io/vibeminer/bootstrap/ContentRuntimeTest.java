package io.vibeminer.bootstrap;

import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContentRuntimeTest {
    @TempDir Path directory;
    @Test void metadataOnlyFacadeDoesNotTakeContentLockOrOpenCatalog() {
        try (var held = new FileObjectStore(directory.resolve("content"));
             var lazy = new LazyBootstrapService(() -> ContentRuntime.open(directory))) {
            assertFalse(Files.exists(directory.resolve("catalog.sqlite")));
            assertThrows(CompletionException.class, () -> lazy.inspectConfiguration(FixtureDiscovery.builds().getFirst())
                .toCompletableFuture().join());
        }
    }
    @Test void closingDuringInitializationReleasesRealStoreEvenOnInterruptedInitializer() throws Exception {
        var opened = new CountDownLatch(1);
        var lazy = new LazyBootstrapService(() -> {
            var runtime = ContentRuntime.open(directory); opened.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            return runtime;
        });
        var pending = lazy.inspectConfiguration(FixtureDiscovery.builds().getFirst()).toCompletableFuture();
        assertTrue(opened.await(3,TimeUnit.SECONDS)); lazy.close(); assertTrue(pending.isCancelled());
        try (var reopened = new FileObjectStore(directory.resolve("content"))) { assertNotNull(reopened); }
    }
}
