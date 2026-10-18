package io.vibeminer.infrastructure.bootstrap;

import static io.vibeminer.infrastructure.bootstrap.BootstrapWorkflowIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.infrastructure.testing.PublisherTrackingStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPublisherAdmissionTest {
  @TempDir Path directory;

  @Test
  void installWorkerBlockedInHttpBeforeCasExcludesLateStartup() throws Exception {
    byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
    var pending = new CompletableFuture<AcquiredBytes>();
    var entered = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    try (var actual = new FileObjectStore(directory.resolve("bytes"));
        var fence = new FileObjectStore(directory.resolve("fence"));
        var worker = Executors.newSingleThreadExecutor()) {
      var tracked = new PublisherTrackingStore(actual, fence);
      BootstrapTransfer transfer =
          r -> {
            if (r.kind() != CdnRequest.Kind.CONFIG) {
              entered.countDown();
              return pending;
            }
            return CompletableFuture.completedFuture(
                new AcquiredBytes(r, NOW, config, hash("SHA-256", config), true));
          };
      var gateway = new OfficialBootstrapArtifactGateway(tracked, transfer, Clock.systemUTC());
      var plan = gateway.inspectConfiguration(build(config), CancellationToken.none());
      assertEquals(0, tracked.starts.get());
      var job = worker.submit(() -> gateway.acquireInstall(plan, cancelled::get));
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(1, tracked.starts.get());
        assertThrows(
            IllegalStateException.class,
            () -> fence.contentSession().recoverAtStartup(() -> fail("late recovery")));
      } finally {
        cancelled.set(true);
        pending.cancel(true);
      }
      assertThrows(ExecutionException.class, () -> job.get(2, TimeUnit.SECONDS));
    }
  }
}
