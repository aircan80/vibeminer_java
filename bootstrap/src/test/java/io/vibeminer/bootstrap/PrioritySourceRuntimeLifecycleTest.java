package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.scheduler.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrioritySourceRuntimeLifecycleTest {
  @TempDir Path directory;

  @Test
  void closeKeepsActualLeaseAndDrainsAcceptedButNotStartedSourceWrappers() throws Exception {
    byte[] bytes = "owned synthetic source bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var client = new MetadataRuntimeTest.Client();
    var runtime = ContentRuntime.open(directory, () -> client);
    var store = field(runtime, "store", FileObjectStore.class);
    var scheduler = field(runtime, "sourceScheduler", PrioritySourceScheduler.class);
    var workers = field(runtime, "acquisition", ExecutorService.class);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var object =
        store.publish(
            new ByteArrayInputStream(bytes),
            new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, sha, bytes.length),
            bytes.length,
            CancellationToken.none());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var leaseClosed = new CountDownLatch(1);
    var blockedWorkers = new CountDownLatch(3);
    var actual =
        scheduler.submit(
            source(0),
            token -> {
              try (var lease = store.openRead(object.digest(), CancellationToken.none())) {
                entered.countDown();
                hold(release);
                assertEquals(bytes.length, lease.object().length());
              } finally {
                leaseClosed.countDown();
              }
            },
            PrioritySourceScheduler.Demand.foreground());
    CompletableFuture<Void> closing = null;
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      // Force the permitted executor-accepted / not-yet-invoked boundary deterministically.
      for (int i = 0; i < 3; i++)
        workers.execute(
            () -> {
              blockedWorkers.countDown();
              hold(release);
            });
      assertTrue(blockedWorkers.await(2, TimeUnit.SECONDS));
      var unstarted = new AtomicInteger();
      var queued = new SourceDemandRegistration(scheduler, source(1));
      queued.register(PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      queued.start(
          token -> fail("Closed queued producer entered"), failure -> unstarted.incrementAndGet());
      closing = CompletableFuture.runAsync(runtime::close);
      assertTrue(client.stopping.await(2, TimeUnit.SECONDS));
      assertFalse(closing.isDone());
      assertFalse(actual.retirement().toCompletableFuture().isDone());
      assertEquals(0, unstarted.get());
      assertThrows(
          ObjectStoreException.class, () -> new FileObjectStore(directory.resolve("content")));
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertEquals(0, leaseClosed.getCount());
      assertEquals(1, unstarted.get());
      actual.retirement().toCompletableFuture().join();
      try (var reopened = new FileObjectStore(directory.resolve("content"))) {
        assertEquals(
            object, reopened.verify(object.digest(), CancellationToken.none()).orElseThrow());
      }
      assertEquals(0, client.requests.get());
    } finally {
      release.countDown();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      runtime.close();
    }
  }

  static ResolvedSourceIdentity source(int index) {
    return new ResolvedSourceIdentity("test.runtime", "source-" + index, "test-v1");
  }

  static void hold(CountDownLatch latch) {
    boolean done = false;
    while (!done) {
      try {
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        done = true;
      } catch (InterruptedException ignoredCancellation) {
        /* Actual work deliberately ignores it. */
      }
    }
  }

  static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(owner));
  }
}
