package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentRuntimeTest {
  @TempDir Path directory;

  @Test
  void metadataOnlyFacadeDoesNotTakeContentLockOrOpenCatalog() {
    try (var held = new FileObjectStore(directory.resolve("content"));
        var lazy = new LazyBootstrapService(() -> ContentRuntime.open(directory))) {
      assertFalse(Files.exists(directory.resolve("catalog.sqlite")));
      assertThrows(
          CompletionException.class,
          () ->
              lazy.inspectConfiguration(FixtureDiscovery.builds().getFirst())
                  .toCompletableFuture()
                  .join());
    }
  }

  @Test
  void rootFacadeDoesNotOpenStorageUntilAnExplicitActionAndReportsLockFailure() {
    try (var held = new FileObjectStore(directory.resolve("content"));
        var lazy = new LazyBootstrapService(() -> ContentRuntime.open(directory));
        var lookup = new LazyFileLookupService(lazy)) {
      assertFalse(Files.exists(directory.resolve("catalog.sqlite")));
      var failure =
          assertThrows(
              CompletionException.class,
              () ->
                  lookup
                      .planRoot(
                          FixtureDiscovery.builds().getFirst(),
                          io.vibeminer.application.lookup.LookupBudgets.defaults())
                      .toCompletableFuture()
                      .join());
      assertEquals(
          io.vibeminer.application.lookup.FileLookupException.Code.STORAGE_FAILURE,
          assertInstanceOf(
                  io.vibeminer.application.lookup.FileLookupException.class, failure.getCause())
              .code());
    }
  }

  @Test
  void realRuntimeExposesBothServicesAndReleasesItsSingleStoreOnClose() {
    try (var runtime = ContentRuntime.open(directory)) {
      assertNotNull(runtime.service());
      assertNotNull(runtime.lookup());
      assertThrows(
          io.vibeminer.application.ObjectStoreException.class,
          () -> new FileObjectStore(directory.resolve("content")));
    }
    try (var reopened = new FileObjectStore(directory.resolve("content"))) {
      assertNotNull(reopened);
    }
  }

  @Test
  void closingDuringInitializationReleasesRealStoreEvenOnInterruptedInitializer() throws Exception {
    var opened = new CountDownLatch(1);
    var lazy =
        new LazyBootstrapService(
            () -> {
              var runtime = ContentRuntime.open(directory);
              opened.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
              }
              return runtime;
            });
    var pending =
        lazy.inspectConfiguration(FixtureDiscovery.builds().getFirst()).toCompletableFuture();
    assertTrue(opened.await(3, TimeUnit.SECONDS));
    lazy.close();
    assertTrue(pending.isCancelled());
    try (var reopened = new FileObjectStore(directory.resolve("content"))) {
      assertNotNull(reopened);
    }
  }
}
