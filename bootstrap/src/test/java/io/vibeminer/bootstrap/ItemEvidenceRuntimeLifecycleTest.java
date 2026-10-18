package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.itemexport.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemEvidenceRuntimeLifecycleTest {
  @TempDir Path directory;

  @Test
  void cancelledCaptureRetainsActualLeaseAndRuntimeLockUntilWorkerRetires() throws Exception {
    byte[] bytes = {1, 2, 3};
    StoredObject object;
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      object =
          store.publish(
              new ByteArrayInputStream(bytes),
              new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, hash, bytes.length),
              bytes.length,
              CancellationToken.none());
    }
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var leaseClosed = new AtomicBoolean();
    var runtime =
        ContentRuntime.open(
            directory,
            RetentionRuntimeTest.ForbiddenHttp::new,
            (s, b) -> b,
            (s, g) -> g,
            (s, b) -> b,
            (store, real) ->
                (selection, token) -> {
                  assertTrue(Thread.currentThread().getName().startsWith("vibeminer-item-names-"));
                  try (var lease = store.openRead(object.digest(), CancellationToken.none())) {
                    entered.countDown();
                    hold(release, interrupted);
                    assertArrayEquals(bytes, lease.stream().readAllBytes());
                  } catch (java.io.IOException failed) {
                    throw new AssertionError(failed);
                  } finally {
                    leaseClosed.set(true);
                  }
                  throw new CancellationException("Actual capture retired");
                },
            writer -> writer);
    CompletableFuture<Void> closing = null;
    try {
      var capture = runtime.itemEvidence().capture(selection()).toCompletableFuture();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      closing = CompletableFuture.runAsync(runtime::close);
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertTrue(capture.isCancelled());
      assertFalse(closing.isDone());
      assertThrows(
          ObjectStoreException.class, () -> new FileObjectStore(directory.resolve("content")));
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertTrue(leaseClosed.get());
      try (var reopened = new FileObjectStore(directory.resolve("content"))) {
        assertNotNull(reopened);
      }
    } finally {
      release.countDown();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      runtime.close();
    }
  }

  @Test
  void closeDuringActualSaveWaitsForCommitAndPreservesReceipt() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var receipt = new ItemEvidenceReceipt(10, "a".repeat(64), Instant.EPOCH);
    var runtime =
        ContentRuntime.open(
            directory,
            RetentionRuntimeTest.ForbiddenHttp::new,
            (s, b) -> b,
            (s, g) -> g,
            (s, b) -> b,
            (s, real) -> (selection, token) -> snapshot(selection),
            real ->
                new ItemEvidenceWriter() {
                  public void validateDestination(String path) {
                    real.validateDestination(path);
                  }

                  public ItemEvidenceReceipt publish(
                      ItemEvidenceSnapshot snapshot, String path, CancellationToken token) {
                    assertTrue(
                        Thread.currentThread().getName().startsWith("vibeminer-item-names-"));
                    entered.countDown();
                    hold(release, interrupted);
                    return receipt;
                  }
                });
    CompletableFuture<Void> closing = null;
    try {
      var snapshot = runtime.itemEvidence().capture(selection()).toCompletableFuture().join();
      var save =
          runtime
              .itemEvidence()
              .save(snapshot, directory.resolve("out.json").toAbsolutePath().toString());
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      closing = CompletableFuture.runAsync(runtime::close);
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertFalse(save.completion().toCompletableFuture().isDone());
      assertFalse(closing.isDone());
      assertThrows(
          ObjectStoreException.class, () -> new FileObjectStore(directory.resolve("content")));
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertEquals(receipt, save.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      runtime.close();
    }
  }

  @Test
  void exportConstructionFailureReleasesStoreAndAllowsRetry() {
    assertThrows(
        IllegalStateException.class,
        () ->
            ContentRuntime.open(
                directory,
                RetentionRuntimeTest.ForbiddenHttp::new,
                (s, b) -> b,
                (s, g) -> g,
                (s, b) -> b,
                (s, b) -> b,
                writer -> {
                  throw new IllegalStateException("Writer construction failure");
                }));
    try (var runtime = ContentRuntime.open(directory, RetentionRuntimeTest.ForbiddenHttp::new)) {
      assertNotNull(runtime.itemEvidence());
    }
  }

  static ItemEvidenceSelection selection() {
    return new ItemEvidenceSelection(ItemNamesRuntimeSeed.request().item(), 25, Optional.empty());
  }

  static ItemEvidenceSnapshot snapshot(ItemEvidenceSelection selection) {
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var binding : selection.item().definition().interpretation().schema().fields())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              binding.field(),
              0,
              binding.candidateBits(),
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              0,
              binding.candidateBits(),
              binding.sign()));
    return new ItemEvidenceSnapshot(
        selection,
        new ItemPhysicalRow(25, 0, 0, values),
        Optional.empty(),
        Instant.EPOCH,
        Instant.EPOCH,
        "b".repeat(64));
  }

  static void hold(CountDownLatch release, CountDownLatch interrupted) {
    boolean done = false;
    while (!done)
      try {
        assertTrue(release.await(5, TimeUnit.SECONDS));
        done = true;
      } catch (InterruptedException ignored) {
        interrupted.countDown();
      }
  }
}
