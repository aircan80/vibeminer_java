package io.vibeminer.application.buildoverview;

import static io.vibeminer.application.buildoverview.BuildOverview.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildretention.BuildRetention;
import io.vibeminer.application.discoveryhistory.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DefaultBuildOverviewServiceTest {
  static final Limits LIMITS = new Limits(1000, 1000000, 128, 1000000, Duration.ofSeconds(30));

  static Request request() {
    var ref = reference();
    var scope = "all-declared-v1";
    return new Request(
        ref,
        "enUS",
        scope,
        LIMITS,
        Optional.of(
            new BuildRetention.Target(
                ref.key().identity(),
                ref.snapshotId(),
                "c".repeat(64),
                "d".repeat(64),
                1,
                scope,
                "e".repeat(64))));
  }

  static BuildLibraryReference reference() {
    var key =
        new BuildReferenceGroupKey(
            "wow", "Published", "authored", "a".repeat(32), "b".repeat(32), "", "", Map.of());
    var versions =
        new DiscoveryEventSummary.DocumentReceipt(
            "https://us.version.battle.net/v2/products/wow/versions",
            Instant.EPOCH,
            "a".repeat(64),
            1);
    var cdns =
        new DiscoveryEventSummary.DocumentReceipt(
            "https://us.version.battle.net/v2/products/wow/cdns", Instant.EPOCH, "b".repeat(64), 1);
    return new BuildLibraryReference(
        key,
        UUID.randomUUID(),
        1,
        key.snapshotIdFor("us"),
        0,
        "us",
        Instant.EPOCH,
        versions,
        cdns,
        new DiscoveryEvent.CdnRow(
            0, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "authored.invalid")));
  }

  static Snapshot partial(Request r) {
    return new Snapshot(
        r.reference(),
        r.locale(),
        r.scopeRevision(),
        "a".repeat(64),
        Instant.EPOCH,
        MetadataState.READY,
        InventoryState.UNKNOWN,
        Readiness.PARTIAL,
        r.target(),
        Measurements.unknown(),
        Optional.empty(),
        List.of());
  }

  static Snapshot full(Request r) {
    var target = r.target().orElseThrow();
    return new Snapshot(
        r.reference(),
        r.locale(),
        r.scopeRevision(),
        "f".repeat(64),
        Instant.EPOCH,
        MetadataState.READY,
        InventoryState.SEALED,
        Readiness.FULL,
        Optional.of(target),
        new Measurements(
            OptionalLong.empty(),
            OptionalLong.of(3),
            OptionalLong.of(2),
            OptionalLong.of(10),
            OptionalLong.of(2),
            OptionalLong.of(10),
            true,
            true,
            OptionalLong.of(8),
            OptionalLong.empty(),
            OptionalLong.empty()),
        Optional.empty(),
        List.of());
  }

  static Snapshot finish(Request r, BuildOverviewBackend.ProgressSink p) {
    var result = full(r);
    p.update(new Progress(Stage.COMPLETE, 2, OptionalLong.of(2), 20, Optional.of(result)));
    return result;
  }

  static Throwable failure(BuildOverviewService.Operation op) {
    try {
      op.completion().toCompletableFuture().join();
      throw new AssertionError("Expected failure");
    } catch (CompletionException e) {
      return e.getCause();
    } catch (CancellationException e) {
      return e;
    }
  }

  static final class Queue implements Executor {
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    public void execute(Runnable r) {
      queue.add(r);
    }

    void run() {
      queue.remove().run();
    }
  }

  @Test
  void actualWorkerReportsFastPartialThenAuthoritativeCompletionWithoutInventingUnknowns() {
    var request = request();
    var queue = new Queue();
    var calls = new AtomicInteger();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              calls.incrementAndGet();
              p.update(
                  new Progress(
                      Stage.READING_INVENTORY,
                      0,
                      OptionalLong.empty(),
                      0,
                      Optional.of(partial(r))));
              assertTrue(p != null);
              return finish(r, p);
            },
            queue)) {
      var op = service.read(request);
      assertEquals(0, calls.get());
      assertTrue(op.progress().observation().isEmpty());
      queue.run();
      assertEquals(Readiness.FULL, op.completion().toCompletableFuture().join().readiness());
      assertEquals(Stage.COMPLETE, op.progress().stage());
      assertTrue(
          op.progress().observation().orElseThrow().measurements().sharedPhysicalBytes().isEmpty());
    }
  }

  @Test
  void nullRequestRefusedBeforeBackendAndExecutorIO() {
    var calls = new AtomicInteger();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              calls.incrementAndGet();
              throw new AssertionError();
            },
            r -> {
              throw new AssertionError();
            })) {
      assertEquals(
          BuildOverviewException.Code.INVALID_REQUEST,
          ((BuildOverviewException) failure(service.read(null))).code());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void sameSnapshotDifferentReceiptOrLocaleCannotReplaceExactObservation() {
    var req = request();
    try (var service =
        new DefaultBuildOverviewService((r, p, t) -> finish(request(), p), Runnable::run)) {
      assertEquals(
          BuildOverviewException.Code.CORRUPT_RESULT,
          ((BuildOverviewException) failure(service.read(req))).code());
    }
  }

  @Test
  void fourActualQueuedWorkersHoldCapacityAfterCancellationUntilRetired() {
    var queue = new Queue();
    var calls = new AtomicInteger();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              calls.incrementAndGet();
              return finish(r, p);
            },
            queue)) {
      var first = service.read(request());
      for (int n = 0; n < 3; n++) service.read(request());
      first.requestCancellation();
      assertEquals(
          BuildOverviewException.Code.BUSY,
          ((BuildOverviewException) failure(service.read(request()))).code());
      queue.run();
      assertInstanceOf(CancellationException.class, failure(first));
      assertEquals(0, calls.get());
      var replacement = service.read(request());
      assertFalse(replacement.completion().toCompletableFuture().isDone());
      while (!queue.queue.isEmpty()) queue.run();
    }
  }

  @Test
  void cancelHeldActualWorkerCannotReleaseCapacityOrPublishLateFull() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              return finish(r, p);
            },
            pool)) {
      var first = service.read(request());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      for (int n = 0; n < 3; n++) service.read(request());
      first.requestCancellation();
      assertEquals(
          BuildOverviewException.Code.BUSY,
          ((BuildOverviewException) failure(service.read(request()))).code());
      release.countDown();
      assertInstanceOf(CancellationException.class, failure(first));
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void lateFailurePreservesOnlyPartialObservationAndDoesNotCompleteFull() {
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              p.update(
                  new Progress(
                      Stage.READING_INVENTORY,
                      0,
                      OptionalLong.empty(),
                      0,
                      Optional.of(partial(r))));
              throw new IllegalStateException("late proof failure");
            },
            Runnable::run)) {
      var op = service.read(request());
      assertInstanceOf(IllegalStateException.class, failure(op));
      assertEquals(Readiness.PARTIAL, op.progress().observation().orElseThrow().readiness());
    }
  }

  @Test
  void progressCannotRegressExceedBudgetOrOmitFinalSnapshot() {
    for (int kind = 0; kind < 3; kind++) {
      final int mode = kind;
      try (var service =
          new DefaultBuildOverviewService(
              (r, p, t) -> {
                if (mode == 0) {
                  p.update(
                      new Progress(
                          Stage.CHECKING_RETAINED,
                          1,
                          OptionalLong.of(2),
                          4,
                          Optional.of(partial(r))));
                  p.update(
                      new Progress(
                          Stage.CHECKING_RETAINED,
                          0,
                          OptionalLong.of(2),
                          0,
                          Optional.of(partial(r))));
                } else if (mode == 1)
                  p.update(
                      new Progress(
                          Stage.CHECKING_RETAINED,
                          0,
                          OptionalLong.empty(),
                          r.limits().maximumPayloadVerificationBytes() + 1,
                          Optional.of(partial(r))));
                return full(r);
              },
              Runnable::run)) {
        assertEquals(
            BuildOverviewException.Code.CORRUPT_RESULT,
            ((BuildOverviewException) failure(service.read(request()))).code());
      }
    }
  }

  @Test
  void finalCallbackRunsAfterCapacityRetirementAndLateProgressCannotRepaint() {
    var saved = new AtomicReference<BuildOverviewBackend.ProgressSink>();
    var queue = new Queue();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              saved.set(p);
              return finish(r, p);
            },
            queue)) {
      var op = service.read(request());
      op.completion()
          .thenRun(
              () ->
                  assertFalse(service.read(request()).completion().toCompletableFuture().isDone()));
      queue.run();
      var finalProgress = op.progress();
      saved.get().update(Progress.initial());
      assertEquals(finalProgress, op.progress());
      service.close();
      while (!queue.queue.isEmpty()) queue.run();
      assertEquals(
          BuildOverviewException.Code.CLOSED,
          ((BuildOverviewException) failure(service.read(request()))).code());
    }
  }

  @Test
  void backendFailureAfterTerminalProgressCannotExposeFull() {
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              p.update(
                  new Progress(
                      Stage.READING_INVENTORY,
                      0,
                      OptionalLong.empty(),
                      0,
                      Optional.of(partial(r))));
              finish(r, p);
              throw new IllegalStateException("terminal cleanup failed");
            },
            Runnable::run)) {
      var op = service.read(request());
      assertInstanceOf(IllegalStateException.class, failure(op));
      assertEquals(Readiness.PARTIAL, op.progress().observation().orElseThrow().readiness());
    }
  }

  @Test
  void heldTerminalFullIsPrivateAndCancellationPreservesLastPartial() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> {
              p.update(
                  new Progress(
                      Stage.READING_INVENTORY,
                      0,
                      OptionalLong.empty(),
                      0,
                      Optional.of(partial(r))));
              var result = finish(r, p);
              entered.countDown();
              try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              return result;
            },
            pool)) {
      var op = service.read(request());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertFalse(op.completion().toCompletableFuture().isDone());
      assertEquals(Readiness.PARTIAL, op.progress().observation().orElseThrow().readiness());
      op.requestCancellation();
      release.countDown();
      assertInstanceOf(CancellationException.class, failure(op));
      assertEquals(Readiness.PARTIAL, op.progress().observation().orElseThrow().readiness());
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void sameBuildTargetGenerationSubstitutionIsRejected() {
    var r = request();
    var old = r.target().orElseThrow();
    var other =
        new BuildRetention.Target(
            old.groupId(),
            old.snapshotId(),
            old.admissionId(),
            old.inventoryId(),
            old.inventoryGeneration() + 1,
            old.scopeRevision(),
            old.scopeCommitment());
    try (var service =
        new DefaultBuildOverviewService(
            (req, p, t) ->
                finish(
                    new Request(
                        req.reference(),
                        req.locale(),
                        req.scopeRevision(),
                        req.limits(),
                        Optional.of(other)),
                    p),
            Runnable::run)) {
      assertEquals(
          BuildOverviewException.Code.CORRUPT_RESULT,
          ((BuildOverviewException) failure(service.read(r))).code());
    }
  }

  @Test
  void absentIssuedTargetReturnsExplicitUnknownInventoryWithoutImplicitPreparation() {
    var r = new Request(reference(), "enUS", "all-declared-v1", LIMITS);
    try (var service =
        new DefaultBuildOverviewService(
            (req, p, t) -> {
              assertTrue(req.target().isEmpty());
              var result = partial(req);
              p.update(
                  new Progress(Stage.COMPLETE, 0, OptionalLong.empty(), 0, Optional.of(result)));
              return result;
            },
            Runnable::run)) {
      var result = service.read(r).completion().toCompletableFuture().join();
      assertEquals(Readiness.PARTIAL, result.readiness());
      assertEquals(InventoryState.UNKNOWN, result.inventory());
      assertTrue(result.target().isEmpty());
      assertTrue(result.measurements().acquisitionUnits().isEmpty());
    }
  }

  @Test
  void rejectedExecutorAdmissionDoesNotConsumeActualWorkerCapacity() {
    var queue = new Queue();
    var attempts = new AtomicInteger();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> finish(r, p),
            task -> {
              if (attempts.incrementAndGet() <= 4) throw new RejectedExecutionException();
              queue.execute(task);
            })) {
      for (int n = 0; n < 4; n++)
        assertEquals(
            BuildOverviewException.Code.BUSY,
            ((BuildOverviewException) failure(service.read(request()))).code());
      var accepted = service.read(request());
      assertFalse(accepted.completion().toCompletableFuture().isDone());
      queue.run();
      assertEquals(Readiness.FULL, accepted.completion().toCompletableFuture().join().readiness());
    }
  }

  @Test
  void executorErrorBeforeRunnableDoesNotStrandActualWorkerSlots() {
    var queue = new Queue();
    var attempts = new AtomicInteger();
    try (var service =
        new DefaultBuildOverviewService(
            (r, p, t) -> finish(r, p),
            task -> {
              if (attempts.incrementAndGet() <= 4)
                throw new AssertionError("authored fatal executor rejection");
              queue.execute(task);
            })) {
      for (int n = 0; n < 4; n++) {
        var failed = (BuildOverviewException) failure(service.read(request()));
        assertEquals(BuildOverviewException.Code.BUSY, failed.code());
        assertInstanceOf(AssertionError.class, failed.getCause());
      }
      var accepted = service.read(request());
      assertFalse(accepted.completion().toCompletableFuture().isDone());
      queue.run();
      assertEquals(Readiness.FULL, accepted.completion().toCompletableFuture().join().readiness());
    }
  }

  @Test
  void incompleteAssessmentCannotSupplyExactRetainedTotalsAndUnknownIsNotZero() {
    assertTrue(Measurements.unknown().retainedUnits().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Measurements(
                OptionalLong.empty(),
                OptionalLong.of(3),
                OptionalLong.of(2),
                OptionalLong.of(10),
                OptionalLong.of(1),
                OptionalLong.of(5),
                true,
                false,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Request(reference(), "enUS", "bad\ud800", LIMITS));
  }

  @Test
  void metadataAdmissionScopeAndIssuedInventoryScopeRemainIndependent() {
    var ref = reference();
    var target =
        new BuildRetention.Target(
            ref.key().identity(),
            ref.snapshotId(),
            "c".repeat(64),
            "d".repeat(64),
            1,
            "wow-root-all-declared-inventory-v1",
            "e".repeat(64));
    var r = new Request(ref, "enUS", "wow-direct-metadata-v1", LIMITS, Optional.of(target));
    try (var service =
        new DefaultBuildOverviewService((req, p, t) -> finish(req, p), Runnable::run)) {
      var result = service.read(r).completion().toCompletableFuture().join();
      assertEquals("wow-direct-metadata-v1", result.scopeRevision());
      assertEquals(
          "wow-root-all-declared-inventory-v1", result.target().orElseThrow().scopeRevision());
      assertEquals(Readiness.FULL, result.readiness());
    }
  }

  @Test
  void fullCannotBeFabricatedFromUnknownCountsOrHistoricalJobSuccess() {
    var r = request();
    var target = full(r).target();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Snapshot(
                r.reference(),
                r.locale(),
                r.scopeRevision(),
                "f".repeat(64),
                Instant.EPOCH,
                MetadataState.READY,
                InventoryState.SEALED,
                Readiness.FULL,
                target,
                Measurements.unknown(),
                Optional.of(
                    new Retention(
                        BuildRetention.Mode.DOWNLOAD_ALL,
                        1,
                        OptionalLong.of(0),
                        OptionalLong.of(0),
                        OptionalLong.of(2),
                        OptionalLong.of(0))),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Measurements(
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.of(1),
                OptionalLong.of(4),
                OptionalLong.of(2),
                OptionalLong.of(8),
                true,
                true,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty()));
  }
}
