package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.domain.SourceDocument;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildOpeningRuntimeIntegrationTest {
  @TempDir Path directory;

  @Test
  void runtimeProvidesOpeningAndKeepsOneStoreOwnerUntilClose() {
    try (var runtime = ContentRuntime.open(directory)) {
      assertNotNull(runtime.buildOpening());
      assertSame(runtime.buildOpening(), runtime.buildOpening());
      assertThrows(
          io.vibeminer.application.ObjectStoreException.class,
          () -> new FileObjectStore(directory.resolve("content")));
    }
    try (var reopened = new FileObjectStore(directory.resolve("content"))) {
      assertNotNull(reopened);
    }
  }

  @Test
  void actualRuntimeRejectsUnregisteredReferenceBeforeAnyHttp() {
    try (var runtime = ContentRuntime.open(directory, RetentionRuntimeTest.ForbiddenHttp::new)) {
      var failure =
          assertThrows(
              CompletionException.class,
              () ->
                  runtime
                      .buildOpening()
                      .open(reference(), "enUS")
                      .completion()
                      .toCompletableFuture()
                      .join());
      assertEquals(
          io.vibeminer.application.buildopening.BuildOpeningException.Code.NOT_READY,
          assertInstanceOf(
                  io.vibeminer.application.buildopening.BuildOpeningException.class,
                  failure.getCause())
              .code());
    }
  }

  @Test
  void closeDrainsAcceptedQueuedOpeningRatherThanDroppingItsWrapper() throws Exception {
    var runtime = ContentRuntime.open(directory);
    var field = ContentRuntime.class.getDeclaredField("orchestration");
    field.setAccessible(true);
    var executor = (ExecutorService) field.get(runtime);
    var entered = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    var closer = Executors.newSingleThreadExecutor();
    try {
      for (int i = 0; i < 4; i++)
        executor.execute(
            () -> {
              entered.countDown();
              boolean interrupted = false;
              while (true) {
                try {
                  release.await();
                  break;
                } catch (InterruptedException ignored) {
                  interrupted = true;
                }
              }
              if (interrupted) Thread.currentThread().interrupt();
            });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var pending =
          runtime.buildOpening().open(reference(), "enUS").completion().toCompletableFuture();
      var closing = closer.submit(runtime::close);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (!executor.isShutdown() && System.nanoTime() < deadline) Thread.onSpinWait();
      assertTrue(
          executor.isShutdown(), "Close must cancel admissions before draining accepted jobs");
      assertFalse(closing.isDone(), "Active orchestration keeps storage protected");
      assertFalse(pending.isDone(), "Accepted queued job still owns its completion wrapper");
      assertThrows(
          io.vibeminer.application.ObjectStoreException.class,
          () -> new FileObjectStore(directory.resolve("content")));
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertTrue(pending.isDone(), "Accepted queued wrapper must retire and publish cancellation");
      assertTrue(pending.isCompletedExceptionally());
      var cancellation = assertThrows(CompletionException.class, pending::join);
      assertInstanceOf(CancellationException.class, cancellation.getCause());
      try (var reopened = new FileObjectStore(directory.resolve("content"))) {
        assertNotNull(reopened);
      }
    } finally {
      release.countDown();
      runtime.close();
      closer.shutdownNow();
      assertTrue(closer.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  static BuildLibraryReference reference() {
    String base = "https://us.version.battle.net/v2/products/wow";
    var versions =
        new SourceDocument(
            base + "/versions",
            Instant.EPOCH,
            ("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
                    + "a".repeat(32)
                    + "|"
                    + "b".repeat(32)
                    + "|fixture\n")
                .getBytes(StandardCharsets.UTF_8));
    var cdns =
        new SourceDocument(
            base + "/cdns",
            Instant.EPOCH,
            "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\nus|tpr/wow|us.cdn.blizzard.com\n"
                .getBytes(StandardCharsets.UTF_8));
    return BuildLibraryReference.from(
            new DiscoveryObservationMapper()
                .map(UUID.randomUUID(), "wow", "us", Instant.EPOCH, versions, cdns),
            1)
        .getFirst();
  }
}
