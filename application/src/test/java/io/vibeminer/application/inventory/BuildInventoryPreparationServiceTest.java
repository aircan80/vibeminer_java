package io.vibeminer.application.inventory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildretention.BuildRetention;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class BuildInventoryPreparationServiceTest {
  private static BuildMetadataAdmission admission() {
    var key =
        new BuildReferenceGroupKey(
            "wow", "Published", "authored", "a".repeat(32), "b".repeat(32), "", "", Map.of());
    var ref =
        new BuildLibraryReference(
            key,
            UUID.randomUUID(),
            1,
            key.snapshotIdFor("us"),
            0,
            "us",
            Instant.EPOCH,
            new DiscoveryEventSummary.DocumentReceipt(
                "https://us.version.battle.net/v2/products/wow/versions",
                Instant.EPOCH,
                "1".repeat(64),
                1),
            new DiscoveryEventSummary.DocumentReceipt(
                "https://us.version.battle.net/v2/products/wow/cdns",
                Instant.EPOCH,
                "2".repeat(64),
                1),
            new DiscoveryEvent.CdnRow(
                0, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "authored.invalid")));
    var required =
        List.of(
                BuildMetadataAdmission.Role.BUILD_CONFIG,
                BuildMetadataAdmission.Role.CDN_CONFIG,
                BuildMetadataAdmission.Role.ENCODING,
                BuildMetadataAdmission.Role.ROOT,
                BuildMetadataAdmission.Role.INSTALL)
            .stream()
            .map(role -> new BuildMetadataAdmission.Requirement(role, role.name()))
            .toList();
    var verified =
        required.stream()
            .map(
                r ->
                    new BuildMetadataAdmission.VerifiedRequirement(
                        r,
                        new StoredObject(new ObjectDigest("c".repeat(64)), 10),
                        new BuildMetadataAdmission.ProofIdentity(r.role(), "d".repeat(64))))
            .toList();
    return new BuildMetadataAdmission(
        ref,
        "enUS",
        "authored-all-variants-v1",
        required,
        new BuildMetadataAdmission.DownloadDeclaration(
            BuildMetadataAdmission.DownloadApplicability.AUTHENTICATED_NOT_PUBLISHED,
            "e".repeat(64)),
        new BuildMetadataAdmission.SizeDeclaration(
            BuildMetadataAdmission.SizeApplicability.AUTHENTICATED_NOT_PUBLISHED, "9".repeat(64)),
        new BuildMetadataAdmission.LocatorRequirements(List.of(), "f".repeat(64)),
        verified);
  }

  private static BuildInventoryPreparationResult result(BuildMetadataAdmission admission) {
    return new BuildInventoryPreparationResult(
        admission,
        new BuildRetention.Target(
            admission.reference().key().identity(),
            admission.reference().snapshotId(),
            "3".repeat(64),
            "4".repeat(64),
            1,
            admission.scopeRevision(),
            "5".repeat(64)));
  }

  private static BuildInventoryPreparationResult ready(
      BuildMetadataAdmission admission, Consumer<BuildInventoryPreparationProgress> sink) {
    sink.accept(progress(BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, 0));
    return result(admission);
  }

  private static BuildInventoryPreparationProgress progress(
      BuildInventoryPreparationProgress.Stage stage, long count) {
    return new BuildInventoryPreparationProgress(
        stage, OptionalLong.of(count), OptionalLong.empty(), Optional.empty());
  }

  private static Throwable failure(CompletionStage<?> stage) {
    return assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join())
        .getCause();
  }

  private static void assertCode(
      BuildInventoryPreparationException.Code code, CompletionStage<?> stage) {
    assertEquals(
        code, assertInstanceOf(BuildInventoryPreparationException.class, failure(stage)).code());
  }

  @Test
  void exactValidatedResultCompletesAfterLocalProgressWithoutInventingTotals() {
    var admission = admission();
    var result = result(admission);
    var queue = new Queue();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              assertSame(admission, actual);
              assertFalse(token.isCancellationRequested());
              sink.accept(
                  progress(BuildInventoryPreparationProgress.Stage.REVALIDATING_ADMISSION, 0));
              sink.accept(progress(BuildInventoryPreparationProgress.Stage.VERIFYING_SOURCES, 0));
              sink.accept(progress(BuildInventoryPreparationProgress.Stage.PREPARING_INVENTORY, 7));
              sink.accept(progress(BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, 7));
              return result;
            },
            queue)) {
      var operation = service.prepare(admission);
      assertEquals(BuildInventoryPreparationProgress.Stage.QUEUED, operation.progress().stage());
      assertTrue(operation.progress().processedReferences().isEmpty());
      assertTrue(operation.progress().totalReferences().isEmpty());
      queue.run();
      assertSame(result, operation.completion().toCompletableFuture().join());
      assertEquals(BuildInventoryPreparationProgress.Stage.COMPLETE, operation.progress().stage());
      assertEquals(OptionalLong.of(7), operation.progress().processedReferences());
      assertTrue(operation.progress().totalReferences().isEmpty());
      assertTrue(operation.progress().failure().isEmpty());
    }
  }

  @Test
  void fullAdmissionIncludingLocaleCannotBeSubstitutedBySameGroupAndSnapshot() {
    var requested = admission();
    var changed =
        new BuildMetadataAdmission(
            requested.reference(),
            "deDE",
            requested.scopeRevision(),
            requested.required(),
            requested.download(),
            requested.size(),
            requested.locators(),
            requested.verified());
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> ready(changed, sink), Runnable::run)) {
      var operation = service.prepare(requested);
      assertCode(BuildInventoryPreparationException.Code.CORRUPT_INVENTORY, operation.completion());
      assertEquals(BuildInventoryPreparationProgress.Stage.FAILED, operation.progress().stage());
    }
    var target = result(requested).target();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildInventoryPreparationResult(
                requested,
                new BuildRetention.Target(
                    "0".repeat(64),
                    target.snapshotId(),
                    target.admissionId(),
                    target.inventoryId(),
                    target.inventoryGeneration(),
                    target.scopeRevision(),
                    target.scopeCommitment())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildInventoryPreparationResult(
                requested,
                new BuildRetention.Target(
                    target.groupId(),
                    "0".repeat(64),
                    target.admissionId(),
                    target.inventoryId(),
                    target.inventoryGeneration(),
                    target.scopeRevision(),
                    target.scopeCommitment())));
  }

  @Test
  void backendCannotPublishTerminalSuccessBeforeCleanupReturns() {
    var admission = admission();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              try {
                sink.accept(progress(BuildInventoryPreparationProgress.Stage.COMPLETE, 7));
              } finally {
                throw new IllegalStateException("authored cleanup failure");
              }
            },
            Runnable::run)) {
      var operation = service.prepare(admission);
      assertThrows(
          RuntimeException.class, () -> operation.completion().toCompletableFuture().join());
      assertEquals(BuildInventoryPreparationProgress.Stage.FAILED, operation.progress().stage());
    }
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              sink.accept(progress(BuildInventoryPreparationProgress.Stage.COMPLETE, 7));
              return result(admission);
            },
            Runnable::run)) {
      assertCode(
          BuildInventoryPreparationException.Code.CORRUPT_INVENTORY,
          service.prepare(admission).completion());
    }
  }

  @Test
  void stageAndMeasuredReferenceRegressionFailAndLateProgressCannotReplaceTerminalState() {
    var admission = admission();
    var sink = new AtomicReference<Consumer<BuildInventoryPreparationProgress>>();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, progress, token) -> {
              sink.set(progress);
              progress.accept(
                  progress(BuildInventoryPreparationProgress.Stage.PREPARING_INVENTORY, 10));
              progress.accept(
                  progress(BuildInventoryPreparationProgress.Stage.VERIFYING_SOURCES, 10));
              return result(admission);
            },
            Runnable::run)) {
      var operation = service.prepare(admission);
      assertCode(BuildInventoryPreparationException.Code.CORRUPT_INVENTORY, operation.completion());
      var terminal = operation.progress();
      sink.get().accept(progress(BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, 11));
      assertEquals(terminal, operation.progress());
    }
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, progress, token) -> {
              progress.accept(
                  progress(BuildInventoryPreparationProgress.Stage.PREPARING_INVENTORY, 10));
              progress.accept(
                  progress(BuildInventoryPreparationProgress.Stage.PREPARING_INVENTORY, 9));
              return result(admission);
            },
            Runnable::run)) {
      assertCode(
          BuildInventoryPreparationException.Code.CORRUPT_INVENTORY,
          service.prepare(admission).completion());
    }
  }

  @Test
  void omittedTargetStageAndCaughtInvalidProgressCannotMasqueradeAsReady() {
    var admission = admission();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> result(admission), Runnable::run)) {
      assertCode(
          BuildInventoryPreparationException.Code.CORRUPT_INVENTORY,
          service.prepare(admission).completion());
    }
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              sink.accept(
                  progress(BuildInventoryPreparationProgress.Stage.PREPARING_INVENTORY, 10));
              try {
                sink.accept(
                    progress(BuildInventoryPreparationProgress.Stage.VERIFYING_SOURCES, 10));
              } catch (BuildInventoryPreparationException expected) {
                /* Backend cannot suppress invalid evidence. */
              }
              sink.accept(progress(BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, 10));
              return result(admission);
            },
            Runnable::run)) {
      assertCode(
          BuildInventoryPreparationException.Code.CORRUPT_INVENTORY,
          service.prepare(admission).completion());
    }
  }

  @Test
  void typedBackendRefusalRemainsPreciseAndNeverClaimsInventoryReady() {
    var refusal =
        new BuildInventoryPreparationException(
            BuildInventoryPreparationException.Code.UNSUPPORTED, "Unsupported local metadata");
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              throw refusal;
            },
            Runnable::run)) {
      var operation = service.prepare(admission());
      assertSame(refusal, failure(operation.completion()));
      assertEquals(BuildInventoryPreparationProgress.Stage.FAILED, operation.progress().stage());
      assertEquals(
          Optional.of(BuildInventoryPreparationException.Code.UNSUPPORTED),
          operation.progress().failure());
    }
  }

  @Test
  void queuedCancellationSkipsBackendButDerivedFutureCancellationCannotHideRealResult() {
    var admission = admission();
    var result = result(admission);
    var calls = new AtomicInteger();
    var queue = new Queue();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              calls.incrementAndGet();
              sink.accept(progress(BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, 0));
              return result;
            },
            queue)) {
      var cancelled = service.prepare(admission);
      cancelled.requestCancellation();
      queue.run();
      assertInstanceOf(CancellationException.class, failure(cancelled.completion()));
      assertEquals(BuildInventoryPreparationProgress.Stage.CANCELLED, cancelled.progress().stage());
      assertEquals(0, calls.get());
      var actual = service.prepare(admission);
      actual.completion().toCompletableFuture().cancel(true);
      queue.run();
      assertSame(result, actual.completion().toCompletableFuture().join());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void ignoredRunningCancellationHoldsFourSlotsAndValidActualReturnWinsClose() throws Exception {
    var admission = admission();
    var result = result(admission);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var queue = new Queue();
    var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              entered.countDown();
              await(release);
              sink.accept(progress(BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, 0));
              return result;
            },
            queue);
    var first = service.prepare(admission);
    for (int i = 0; i < 3; i++) service.prepare(admission);
    var worker = new Thread(queue::run);
    worker.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      first.requestCancellation();
      assertFalse(first.completion().toCompletableFuture().isDone());
      assertCode(
          BuildInventoryPreparationException.Code.BUSY, service.prepare(admission).completion());
      service.close();
      assertFalse(first.completion().toCompletableFuture().isDone());
      release.countDown();
      worker.join(2000);
      assertFalse(worker.isAlive());
      assertSame(result, first.completion().toCompletableFuture().join());
      assertEquals(BuildInventoryPreparationProgress.Stage.COMPLETE, first.progress().stage());
      while (!queue.tasks.isEmpty()) queue.run();
    } finally {
      release.countDown();
      worker.join(2000);
      service.close();
    }
  }

  @Test
  void callbacksCanAdmitFromAnotherThreadOnlyAfterActualSlotRetirement() throws Exception {
    var admission = admission();
    var queue = new Queue();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> ready(admission, sink), queue)) {
      var first = service.prepare(admission);
      for (int i = 0; i < 3; i++) service.prepare(admission);
      var callback = new CompletableFuture<Void>();
      first
          .completion()
          .whenComplete(
              (result, error) -> {
                try {
                  var next =
                      CompletableFuture.supplyAsync(() -> service.prepare(admission))
                          .get(2, TimeUnit.SECONDS);
                  assertFalse(next.completion().toCompletableFuture().isDone());
                  callback.complete(null);
                } catch (Throwable failure) {
                  callback.completeExceptionally(failure);
                }
              });
      queue.run();
      callback.get(3, TimeUnit.SECONDS);
      assertEquals(4, queue.tasks.size());
      while (!queue.tasks.isEmpty()) queue.run();
    }
  }

  @Test
  void invalidClosedAndRejectedDispatchesNeverLeakAdmissionOrUseBackend() {
    var calls = new AtomicInteger();
    var dispatched = new AtomicInteger();
    var admission = admission();
    var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> {
              calls.incrementAndGet();
              return ready(actual, sink);
            },
            job -> {
              dispatched.incrementAndGet();
              throw new RejectedExecutionException("authored rejection");
            });
    assertCode(
        BuildInventoryPreparationException.Code.INVALID_REQUEST,
        service.prepare(null).completion());
    for (int i = 0; i < 6; i++)
      assertCode(
          BuildInventoryPreparationException.Code.BUSY, service.prepare(admission).completion());
    assertEquals(6, dispatched.get());
    assertEquals(0, calls.get());
    service.close();
    assertCode(
        BuildInventoryPreparationException.Code.CLOSED, service.prepare(admission).completion());
  }

  @Test
  void arbitraryRuntimeDispatchRefusalAlsoRetiresUnstartedSlot() {
    var attempts = new AtomicInteger();
    var queue = new Queue();
    var admission = admission();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> ready(actual, sink),
            job -> {
              if (attempts.incrementAndGet() == 1)
                throw new IllegalStateException("authored runtime dispatch refusal");
              queue.execute(job);
            })) {
      assertCode(
          BuildInventoryPreparationException.Code.BUSY, service.prepare(admission).completion());
      var operations = new ArrayList<BuildInventoryPreparationService.Operation>();
      for (int i = 0; i < 4; i++) operations.add(service.prepare(admission));
      assertCode(
          BuildInventoryPreparationException.Code.BUSY, service.prepare(admission).completion());
      while (!queue.tasks.isEmpty()) queue.run();
      operations.forEach(
          operation -> assertNotNull(operation.completion().toCompletableFuture().join()));
    }
  }

  @Test
  void dispatchErrorPropagatesButStillRetiresUnstartedSlot() {
    var attempts = new AtomicInteger();
    var error = new AssertionError("authored dispatch error");
    var queue = new Queue();
    var admission = admission();
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (actual, sink, token) -> ready(actual, sink),
            job -> {
              if (attempts.incrementAndGet() == 1) throw error;
              queue.execute(job);
            })) {
      assertSame(error, assertThrows(AssertionError.class, () -> service.prepare(admission)));
      var operations = new ArrayList<BuildInventoryPreparationService.Operation>();
      for (int i = 0; i < 4; i++) operations.add(service.prepare(admission));
      assertCode(
          BuildInventoryPreparationException.Code.BUSY, service.prepare(admission).completion());
      while (!queue.tasks.isEmpty()) queue.run();
      for (var operation : operations) {
        assertNotNull(operation.completion().toCompletableFuture().join());
        assertEquals(
            BuildInventoryPreparationProgress.Stage.COMPLETE, operation.progress().stage());
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  @Test
  void knownIncompleteReferenceCoverageCannotBecomeComplete() {
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (input, sink, token) -> {
              sink.accept(
                  new BuildInventoryPreparationProgress(
                      BuildInventoryPreparationProgress.Stage.ISSUING_TARGET,
                      OptionalLong.of(5),
                      OptionalLong.of(10),
                      Optional.empty()));
              return result(input);
            },
            Runnable::run)) {
      var operation = service.prepare(admission());
      assertCode(BuildInventoryPreparationException.Code.CORRUPT_INVENTORY, operation.completion());
      assertEquals(BuildInventoryPreparationProgress.Stage.FAILED, operation.progress().stage());
      assertEquals(OptionalLong.of(5), operation.progress().processedReferences());
      assertEquals(OptionalLong.of(10), operation.progress().totalReferences());
    }
  }

  @Test
  void knownCompleteReferenceCoverageRemainsMeasuredAtCompletion() {
    try (var service =
        new DefaultBuildInventoryPreparationService(
            (input, sink, token) -> {
              sink.accept(
                  new BuildInventoryPreparationProgress(
                      BuildInventoryPreparationProgress.Stage.ISSUING_TARGET,
                      OptionalLong.of(10),
                      OptionalLong.of(10),
                      Optional.empty()));
              return result(input);
            },
            Runnable::run)) {
      var operation = service.prepare(admission());
      assertNotNull(operation.completion().toCompletableFuture().join());
      assertEquals(BuildInventoryPreparationProgress.Stage.COMPLETE, operation.progress().stage());
      assertEquals(OptionalLong.of(10), operation.progress().processedReferences());
      assertEquals(OptionalLong.of(10), operation.progress().totalReferences());
    }
  }

  @Test
  void terminalProgressCannotRepresentKnownIncompleteReferenceCoverage() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildInventoryPreparationProgress(
                BuildInventoryPreparationProgress.Stage.COMPLETE,
                OptionalLong.of(5),
                OptionalLong.of(10),
                Optional.empty()));
  }

  private static final class Queue implements Executor {
    final java.util.Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    public void execute(Runnable job) {
      tasks.add(job);
    }

    void run() {
      Objects.requireNonNull(tasks.poll()).run();
    }
  }
}
