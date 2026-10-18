package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataRuntimeResumeDownloadsTest {
  @TempDir Path dir;

  void catalog(String mode) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("catalog.sqlite"));
        var q = c.createStatement()) {
      q.execute("CREATE TABLE build_retention_intents(mode TEXT)");
      q.execute(
          "CREATE INDEX build_retention_active_group ON build_retention_intents(mode) WHERE"
              + " mode='DOWNLOAD_ALL'");
      q.execute("INSERT INTO build_retention_intents VALUES('" + mode + "')");
    }
  }

  LazyBootstrapService owner(AtomicInteger starts, AtomicInteger closes) {
    return new LazyBootstrapService(
        () -> {
          starts.incrementAndGet();
          return new LazyBootstrapService.Resources() {
            public io.vibeminer.application.bootstrap.BootstrapService service() {
              throw new AssertionError("No source action allowed");
            }

            public void close() {
              closes.incrementAndGet();
            }
          };
        });
  }

  @Test
  void closeFencesCheckQueuedBehindExistingMetadataWork() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var starts = new AtomicInteger();
    var closes = new AtomicInteger();
    var client = new MetadataRuntimeTest.Client();
    var runtime =
        MetadataRuntime.open(
            dir,
            () -> client,
            g ->
                (product, region, token) -> {
                  entered.countDown();
                  try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                  return MetadataRuntimeTest.event();
                });
    try (var owner = owner(starts, closes)) {
      runtime.history().refreshObservation("wow", "us");
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var pending = runtime.resumeAuthorizedDownloads(owner).toCompletableFuture();
      var closing = CompletableFuture.runAsync(runtime::close);
      assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
      assertEquals(0, starts.get());
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertEquals(0, starts.get());
      assertEquals(0, client.requests.get());
    } finally {
      release.countDown();
      runtime.close();
    }
  }

  @Test
  void closeSettlesPendingInitializationAndDoesNotCloseBorrowedOwner() throws Exception {
    catalog("DOWNLOAD_ALL");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closes = new AtomicInteger();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              return new LazyBootstrapService.Resources() {
                public io.vibeminer.application.bootstrap.BootstrapService service() {
                  throw new AssertionError("No source action");
                }

                public void close() {
                  closes.incrementAndGet();
                }
              };
            })) {
      var runtime = MetadataRuntime.open(dir, MetadataRuntimeTest.Client::new, g -> g);
      try {
        var pending = runtime.resumeAuthorizedDownloads(owner).toCompletableFuture();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        runtime.close();
        assertThrows(CompletionException.class, pending::join);
        assertEquals(0, closes.get());
        release.countDown();
        assertEquals(
            "borrowed",
            owner
                .dispatchResources(
                    r -> CompletableFuture.completedFuture("borrowed"),
                    java.util.function.Function.identity())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS));
        assertEquals(0, closes.get());
      } finally {
        release.countDown();
        runtime.close();
      }
    }
    assertEquals(1, closes.get());
  }

  @Test
  void freshAndPausedStartupDoNotInitializeContentOrSendHttp() throws Exception {
    for (boolean paused : new boolean[] {false, true}) {
      if (paused) catalog("ON_DEMAND");
      var starts = new AtomicInteger();
      var closes = new AtomicInteger();
      var client = new MetadataRuntimeTest.Client();
      try (var owner = owner(starts, closes);
          var runtime = MetadataRuntime.open(dir, () -> client, g -> g)) {
        assertFalse(
            runtime
                .resumeAuthorizedDownloads(owner)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS));
        assertEquals(0, starts.get());
        assertEquals(0, client.requests.get());
        assertFalse(Files.exists(dir.resolve("content")));
        if (!paused) assertFalse(Files.exists(dir.resolve("catalog.sqlite")));
      }
      assertEquals(0, closes.get());
    }
  }

  @Test
  void priorDownloadAllOnlyInitializesBorrowedOwnerOnceAndReturnedStageIsReadOnly()
      throws Exception {
    catalog("DOWNLOAD_ALL");
    var starts = new AtomicInteger();
    var closes = new AtomicInteger();
    var client = new MetadataRuntimeTest.Client();
    try (var owner = owner(starts, closes);
        var runtime = MetadataRuntime.open(dir, () -> client, g -> g)) {
      var first = runtime.resumeAuthorizedDownloads(owner);
      first.toCompletableFuture().cancel(false);
      assertTrue(
          runtime.resumeAuthorizedDownloads(owner).toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertEquals(1, starts.get());
      assertEquals(0, client.requests.get());
      runtime.close();
      assertEquals(0, closes.get());
    }
    assertEquals(1, closes.get());
  }

  @Test
  void closedAndCorruptCatalogSettleWithoutContentInitialization() throws Exception {
    var starts = new AtomicInteger();
    var closes = new AtomicInteger();
    try (var owner = owner(starts, closes)) {
      var runtime = MetadataRuntime.open(dir, MetadataRuntimeTest.Client::new, g -> g);
      runtime.close();
      assertThrows(
          CompletionException.class,
          () -> runtime.resumeAuthorizedDownloads(owner).toCompletableFuture().join());
      assertFalse(Files.exists(dir.resolve("catalog.sqlite")));
      Files.writeString(dir.resolve("catalog.sqlite"), "corrupt");
      try (var second = MetadataRuntime.open(dir, MetadataRuntimeTest.Client::new, g -> g)) {
        assertThrows(
            CompletionException.class,
            () -> second.resumeAuthorizedDownloads(owner).toCompletableFuture().join());
      }
      assertEquals(0, starts.get());
    }
  }
}
