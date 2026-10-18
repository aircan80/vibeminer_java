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

  @Test
  void realRuntimeExposesSelectedFilesWithoutAnotherStoreOwner() {
    io.vibeminer.application.selectedfile.SelectedFileService selected;
    try (var runtime = ContentRuntime.open(directory)) {
      selected = runtime.selectedFiles();
      assertNotNull(selected);
      assertThrows(
          io.vibeminer.application.ObjectStoreException.class,
          () -> new FileObjectStore(directory.resolve("content")));
    }
    try (var reopened = new FileObjectStore(directory.resolve("content"))) {
      assertNotNull(reopened);
    }
    var build =
        new io.vibeminer.domain.BuildSnapshot(
            "wow",
            "Fixture",
            "current",
            "us",
            "test",
            "c".repeat(32),
            "d".repeat(32),
            java.time.Instant.EPOCH,
            true);
    var variant =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            build,
            new io.vibeminer.domain.ResolvedSourceIdentity("fixture", "root", "root.v1"),
            42,
            "a".repeat(32),
            1,
            2,
            3,
            4,
            5,
            java.util.Optional.empty());
    var descriptor =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            variant,
            "b".repeat(32),
            io.vibeminer.domain.selectedfile.ContentRepresentation.VERIFIER_SEMANTICS);
    var failure =
        assertThrows(
            CompletionException.class,
            () ->
                selected
                    .inspectOffline(
                        descriptor,
                        io.vibeminer.application.selectedfile.SelectedFileBudgets.defaults())
                    .toCompletableFuture()
                    .join());
    assertEquals(
        io.vibeminer.application.selectedfile.SelectedFileException.Code.CLOSED,
        assertInstanceOf(
                io.vibeminer.application.selectedfile.SelectedFileException.class,
                failure.getCause())
            .code());
  }

  @Test
  void runtimeFencesAbandonedWorkerBeforeAnyProducerExists() throws Exception {
    var catalog =
        new io.vibeminer.infrastructure.persistence.SqliteCatalog(
            directory.resolve("catalog.sqlite"));
    var abandoned = seedAbandonedWorker(catalog);
    try (var runtime = ContentRuntime.open(directory, RetentionRuntimeTest.ForbiddenHttp::new);
        var connection = catalog.connect();
        var query =
            connection.prepareStatement(
                "SELECT status,owner_session,worker_nonce FROM canonical_content_slots WHERE"
                    + " token=?")) {
      assertNotNull(runtime.service());
      assertNotNull(runtime.lookup());
      assertNotNull(runtime.selectedFiles());
      query.setString(1, abandoned.token());
      try (var row = query.executeQuery()) {
        assertTrue(row.next());
        assertEquals("RECOVERY", row.getString(1));
        assertNotEquals(abandoned.session(), row.getString(2));
        assertNull(row.getString(3));
      }
    }
  }

  @Test
  void failedStartupFenceRollsBackAndReleasesExclusiveStore() throws Exception {
    var catalog =
        new io.vibeminer.infrastructure.persistence.SqliteCatalog(
            directory.resolve("catalog.sqlite"));
    var abandoned = seedAbandonedWorker(catalog);
    try (var connection = catalog.connect();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TRIGGER fail_startup_fence BEFORE UPDATE OF owner_session ON"
              + " canonical_content_slots BEGIN SELECT RAISE(ABORT,'synthetic fence failure');"
              + " END");
    }
    assertThrows(
        RuntimeException.class,
        () ->
            ContentRuntime.open(
                directory,
                () -> {
                  throw new AssertionError(
                      "No HTTP client or producer should exist before the startup fence succeeds");
                }));
    try (var reopened = new FileObjectStore(directory.resolve("content"))) {
      assertNotNull(reopened);
    }
    try (var connection = catalog.connect();
        var query =
            connection.prepareStatement(
                "SELECT status,owner_session,worker_nonce FROM canonical_content_slots WHERE"
                    + " token=?")) {
      query.setString(1, abandoned.token());
      try (var row = query.executeQuery()) {
        assertTrue(row.next());
        assertEquals("PUBLISHING", row.getString(1));
        assertEquals(abandoned.session(), row.getString(2));
        assertNotNull(row.getString(3));
      }
      try (var statement = connection.createStatement()) {
        statement.execute("DROP TRIGGER fail_startup_fence");
      }
    }
    try (var runtime = ContentRuntime.open(directory, RetentionRuntimeTest.ForbiddenHttp::new)) {
      assertNotNull(runtime.selectedFiles());
    }
  }

  private record AbandonedWorker(String token, String session) {}

  private AbandonedWorker seedAbandonedWorker(
      io.vibeminer.infrastructure.persistence.SqliteCatalog catalog) throws Exception {
    String oldSession = java.util.UUID.randomUUID().toString();
    String token = java.util.UUID.randomUUID().toString();
    try (var connection = catalog.connect();
        var insert =
            connection.prepareStatement(
                "INSERT INTO"
                    + " canonical_content_slots(namespace,content_key,encoding_key,encoded_bytes,token,status,owner_session,representation,worker_nonce)"
                    + " VALUES('wow',?,?,80,?,'PUBLISHING',?,NULL,?)")) {
      insert.setString(1, "a".repeat(32));
      insert.setString(2, "b".repeat(32));
      insert.setString(3, token);
      insert.setString(4, oldSession);
      insert.setString(5, java.util.UUID.randomUUID().toString());
      insert.executeUpdate();
    }
    return new AbandonedWorker(token, oldSession);
  }
}
