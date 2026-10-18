package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildreadiness.BuildReadiness;
import io.vibeminer.infrastructure.buildopening.BuildOpeningPolicy;
import io.vibeminer.infrastructure.inventory.SqliteBuildInventory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataRuntimeReadinessTest {
  @TempDir Path directory;

  @Test
  void freshChooserReadinessDoesNotCreateCatalogContentOrStartHttp() throws Exception {
    try (var metadata =
        MetadataRuntime.open(
            directory, RetentionRuntimeTest.ForbiddenHttp::new, gateway -> gateway)) {
      var context =
          new BuildReadiness.Context(
              BuildOpeningRuntimeIntegrationTest.reference(),
              "enUS",
              BuildOpeningPolicy.SCOPE_REVISION,
              SqliteBuildInventory.REVISION);
      var statuses =
          metadata
              .readiness()
              .read(List.of(context))
              .completion()
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS);
      assertEquals(1, statuses.size());
      assertEquals(context, statuses.getFirst().context());
      assertEquals(BuildReadiness.AdmissionPresence.ABSENT, statuses.getFirst().admission());
      assertTrue(statuses.getFirst().lastChecked().isEmpty());
      assertFalse(Files.exists(directory.resolve("catalog.sqlite")));
      assertFalse(Files.exists(directory.resolve("content")));
    }
  }

  @Test
  void closeDrainsAcceptedReadinessRequestsAfterActualMetadataWorkerRetires() throws Exception {
    var entered = new CountDownLatch(1);
    var cancellationObserved = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var metadata =
        MetadataRuntime.open(
            directory,
            RetentionRuntimeTest.ForbiddenHttp::new,
            gateway -> gateway,
            gateway ->
                (product, token) -> {
                  entered.countDown();
                  try {
                    while (!release.await(10, TimeUnit.MILLISECONDS))
                      if (token.isCancellationRequested()) cancellationObserved.countDown();
                  } catch (InterruptedException unexpected) {
                    throw new AssertionError(
                        "Accepted work must drain without being discarded", unexpected);
                  }
                  token.throwIfCancellationRequested();
                  throw new AssertionError("No HTTP operation may follow shutdown");
                });
    CompletableFuture<Void> closing = null;
    try {
      metadata.library().refreshProduct("wow");
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var context =
          new BuildReadiness.Context(
              BuildOpeningRuntimeIntegrationTest.reference(),
              "enUS",
              BuildOpeningPolicy.SCOPE_REVISION,
              SqliteBuildInventory.REVISION);
      var queued = new ArrayList<CompletableFuture<?>>();
      for (int i = 0; i < 4; i++)
        queued.add(metadata.readiness().read(List.of(context)).completion().toCompletableFuture());
      closing = CompletableFuture.runAsync(metadata::close);
      assertTrue(cancellationObserved.await(2, TimeUnit.SECONDS));
      assertFalse(closing.isDone());
      assertTrue(queued.stream().noneMatch(CompletableFuture::isDone));
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      for (var result : queued) {
        var failure = assertThrows(CompletionException.class, result::join);
        assertInstanceOf(CancellationException.class, failure.getCause());
      }
      assertFalse(Files.exists(directory.resolve("content")));
    } finally {
      release.countDown();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      metadata.close();
    }
  }
}
