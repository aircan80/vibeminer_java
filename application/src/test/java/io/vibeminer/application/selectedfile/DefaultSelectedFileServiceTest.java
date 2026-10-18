package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;
import static io.vibeminer.application.selectedfile.SelectedFileFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.scheduler.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DefaultSelectedFileServiceTest {
  static SelectedFileException.Code failure(CompletionStage<?> stage) {
    return assertInstanceOf(
            SelectedFileException.class,
            assertThrows(
                    ExecutionException.class,
                    () -> stage.toCompletableFuture().get(3, TimeUnit.SECONDS))
                .getCause())
        .code();
  }

  static CandidateChoice choose(CandidatePlan plan, int index) {
    return new CandidateChoice(plan, plan.candidates().get(index));
  }

  static SelectedFilePlan located(SelectedFileService service, CandidatePlan plan, int index)
      throws Exception {
    return await(service.probeLoose(choose(plan, index))).plan().orElseThrow();
  }

  @Test
  void bulkModeOffAfterSourceVerificationPreventsOnlyUncommittedConsumerAssociation()
      throws Exception {
    for (boolean afterAssociationCommit : List.of(false, true)) {
      var f = new SelectedFileFixture();
      var variant = f.add("bulk", C);
      try (var scheduler = new PrioritySourceScheduler(Runnable::run)) {
        var owner = scheduler.newBulkOwner(variant.build().snapshotId());
        var catalog =
            (SelectedFileCatalog)
                java.lang.reflect.Proxy.newProxyInstance(
                    SelectedFileCatalog.class.getClassLoader(),
                    new Class<?>[] {SelectedFileCatalog.class},
                    (proxy, method, arguments) -> {
                      try {
                        var result = method.invoke(f.files, arguments);
                        if ((!afterAssociationCommit
                                && method.getName().equals("completePublication"))
                            || (afterAssociationCommit
                                && method.getName().equals("attachAssociation")))
                          scheduler.cancelBulk(owner);
                        return result;
                      } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        try (var service =
            new DefaultSelectedFileService(
                f.gateway, f.builds, catalog, f.pins, Runnable::run, Runnable::run, scheduler)) {
          var candidates = await(service.planCandidates(variant.build(), variant, BUDGETS));
          var plan = located(service, candidates, 0);
          var operation = service.retain(plan, owner.demand());
          if (afterAssociationCommit) {
            assertEquals(variant.build().snapshotId(), await(operation).snapshotId());
            assertEquals(1, f.files.associations.size());
          } else {
            assertThrows(CancellationException.class, () -> await(operation));
            assertTrue(f.files.associations.isEmpty());
          }
          assertEquals(1, f.gateway.acquisitions);
          assertEquals(
              CanonicalReservation.Status.RETAINED, f.files.slots.get(variant.slot()).status());
          // Mode-off never deletes retained physical content or prevents a foreground consumer.
          var foreground = await(service.retain(plan));
          assertEquals(variant.build().snapshotId(), foreground.snapshotId());
          assertEquals(1, f.gateway.acquisitions);
        }
      }
    }
  }

  @Test
  void explicitStagesPinOnlyOnRetentionAndOfflinePreservesActualReceipt() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(variant.build(), variant, BUDGETS));
      assertEquals(2, plan.candidates().size());
      assertTrue(f.pins.scopes.isEmpty());
      var payload = located(service, plan, 0);
      assertEquals(12, payload.cost().encodedTransferBytes());
      assertTrue(f.files.slots.isEmpty());
      assertEquals(0, f.gateway.acquisitions);
      var retained = await(service.retain(payload));
      assertEquals(1, f.pins.scopes.size());
      assertEquals(3, f.pins.scopes.getFirst().requirements().size());
      assertEquals(EncodedScope.ZERO_HEADER_WHOLE_ENCODED, retained.representation().scope());
      assertEquals(retained, await(service.inspectOffline(retained.descriptor(), BUDGETS)));
      assertEquals(1, f.gateway.probes);
      assertEquals(1, f.gateway.acquisitions);
    }
  }

  @Test
  void forgedAndStaleCostCandidateAndVariantFailBeforePinOrPublication() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(variant.build(), variant, BUDGETS));
      var payload = located(service, plan, 0);
      assertEquals(
          INVALID_PLAN,
          failure(
              service.retain(
                  new SelectedFilePlan(
                      payload.choice(),
                      payload.acquisition(),
                      payload.reuse(),
                      new AcquisitionCost(0, 0)))));
      var wrong =
          new CandidateChoice(
              plan, new EncodedCandidate("d".repeat(32), OptionalLong.of(12), OptionalLong.of(0)));
      assertEquals(INVALID_PLAN, failure(service.probeLoose(wrong)));
      f.gateway.plans.clear();
      assertEquals(INVALID_PLAN, failure(service.retain(payload)));
      assertTrue(f.pins.scopes.isEmpty());
      assertTrue(f.files.slots.isEmpty());
    }
  }

  @Test
  void onlyIssued404PermitCanSearchAndIncompleteSearchIsNotAbsence() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(variant.build(), variant, BUDGETS));
      assertEquals(
          INVALID_PLAN,
          failure(
              service.searchArchives(
                  new Loose404Permit(plan.sessionId(), choose(plan, 0)),
                  ArchiveSearchBudgets.defaults())));
      f.gateway.probeFailure = error(UNAVAILABLE);
      assertEquals(UNAVAILABLE, failure(service.probeLoose(choose(plan, 0))));
      assertEquals(0, f.gateway.searches);
      f.gateway.probeFailure = null;
      f.gateway.looseMissing = true;
      var permit = await(service.probeLoose(choose(plan, 0))).missing().orElseThrow();
      var rebound = new Loose404Permit(permit.sessionId(), choose(plan, 1));
      assertEquals(
          INVALID_PLAN, failure(service.searchArchives(rebound, ArchiveSearchBudgets.defaults())));
      var result = await(service.searchArchives(permit, ArchiveSearchBudgets.defaults()));
      assertFalse(result.complete());
      assertTrue(result.plan().isEmpty());
      assertEquals(1, result.checkedArchives());
      assertTrue(f.pins.scopes.isEmpty());
    }
  }

  @Test
  void retainedARejectsBAndReusesActualReceiptAcrossBuildsWithoutNewRoute() throws Exception {
    var f = new SelectedFileFixture();
    var first = f.add("one", C);
    var second = f.add("two", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var a = await(service.planCandidates(first.build(), first, BUDGETS));
      var original = await(service.retain(located(service, a, 0)));
      var b = await(service.planCandidates(second.build(), second, BUDGETS));
      assertEquals(REPLAN_REQUIRED, failure(service.probeLoose(choose(b, 1))));
      var reuse = located(service, b, 0);
      assertEquals(0, reuse.cost().encodedTransferBytes());
      var attached = await(service.retain(reuse));
      assertEquals(original.representation(), attached.representation());
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(1, f.gateway.probes);
      assertNotEquals(original.snapshotId(), attached.snapshotId());
      assertEquals(2, f.files.associations.size());
    }
  }

  @Test
  void conclusiveFailureReleasesReservationButUncertainPublicationStaysExclusive()
      throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      var a = located(service, plan, 0);
      f.gateway.acquisitionFailure =
          new EncodedAcquisitionFailure(
              EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, error(UNAVAILABLE));
      assertEquals(UNAVAILABLE, failure(service.retain(a)));
      assertTrue(f.files.slots.isEmpty());
      f.gateway.acquisitionFailure = null;
      f.gateway.failAfterPublication = true;
      assertEquals(STORAGE_UNAVAILABLE, failure(service.retain(a)));
      assertEquals(CanonicalReservation.Status.RECOVERY, f.files.slots.get(v.slot()).status());
      assertEquals(BUSY, failure(service.probeLoose(choose(plan, 1))));
      assertTrue(f.files.associations.isEmpty());
    }
  }

  @Test
  void cachedReuseStillRejectsStaleProofAndOfflineMissingOrCorruptNeverDownloads()
      throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      var retained = await(service.retain(located(service, plan, 0)));
      var reuse = located(service, plan, 0);
      f.gateway.plans.clear();
      assertEquals(INVALID_PLAN, failure(service.retain(reuse)));
      f.gateway.missingPhysical = true;
      assertEquals(MISSING_LOCAL, failure(service.inspectOffline(retained.descriptor(), BUDGETS)));
      f.gateway.missingPhysical = false;
      f.gateway.corruptPhysical = true;
      assertEquals(
          INTEGRITY_FAILURE, failure(service.inspectOffline(retained.descriptor(), BUDGETS)));
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(1, f.gateway.probes);
    }
  }

  @Test
  void unknownSizesAndSpecificationsRemainVisibleUntilKnownLocatorConsent() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    f.gateway.candidates =
        List.of(
            new EncodedCandidate(A, OptionalLong.empty(), OptionalLong.empty()),
            new EncodedCandidate(B, OptionalLong.of(12), OptionalLong.of(7)));
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      assertEquals(f.gateway.candidates, plan.candidates());
      assertTrue(plan.candidates().getFirst().encodedBytes().isEmpty());
      var selected = located(service, plan, 0);
      assertEquals(12, selected.acquisition().orElseThrow().encodedBytes());
      var association = await(service.retain(selected));
      assertEquals(A, association.descriptor().actualEncodingKey());
      assertEquals(association, await(service.inspectOffline(association.descriptor(), BUDGETS)));
      var next = f.add("two", C);
      var nextPlan = await(service.planCandidates(next.build(), next, BUDGETS));
      assertTrue(nextPlan.candidates().getFirst().encodedBytes().isEmpty());
      var reuse = located(service, nextPlan, 0);
      assertEquals(0, reuse.cost().encodedTransferBytes());
      var reused = await(service.retain(reuse));
      assertEquals(association.representation(), reused.representation());
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(1, f.gateway.probes);
      assertTrue(
          f.pins.scopes.getFirst().requirements().stream()
              .filter(r -> r.role().equals(association.role()))
              .findFirst()
              .orElseThrow()
              .logicalDecodedBytes()
              .isEmpty());
    }
  }

  @Test
  void recoveryReconcilesExistingVerifiedBytesWithoutAnotherPayloadTransfer() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    try (var first = f.service(Runnable::run, Runnable::run)) {
      var plan = await(first.planCandidates(v.build(), v, BUDGETS));
      f.gateway.failAfterPublication = true;
      assertEquals(STORAGE_UNAVAILABLE, failure(first.retain(located(first, plan, 0))));
      assertEquals(CanonicalReservation.Status.RECOVERY, f.files.slots.get(v.slot()).status());
    }
    f.gateway.plans.clear();
    f.gateway.located.clear();
    try (var second = f.service(Runnable::run, Runnable::run)) {
      var plan = await(second.planCandidates(v.build(), v, BUDGETS));
      var recovered = await(second.retain(located(second, plan, 0)));
      assertEquals(
          1, f.gateway.acquisitions); // failAfterPublication remains true: no second acquire call
      assertEquals(CanonicalReservation.Status.RETAINED, f.files.slots.get(v.slot()).status());
      assertEquals(recovered, await(second.inspectOffline(recovered.descriptor(), BUDGETS)));
    }
  }

  @Test
  void finalCancellationKeepsCandidateBusyUntilActualSourceWorkerRetires() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var retired = new CountDownLatch(1);
    var consumerRetired = new CountDownLatch(1);
    var nextConsumerRetirement = new java.util.concurrent.atomic.AtomicReference<CountDownLatch>();
    f.gateway.enteringAcquisition =
        () -> {
          entered.countDown();
          holdIgnoringInterrupts(release);
        };
    f.gateway.acquisitionFailure =
        new EncodedAcquisitionFailure(
            EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, error(CANCELLED));
    try (var jobWorkers = Executors.newFixedThreadPool(2);
        var workers = Executors.newSingleThreadExecutor()) {
      Executor jobs =
          task -> {
            var completion = nextConsumerRetirement.getAndSet(null);
            jobWorkers.execute(
                () -> {
                  try {
                    task.run();
                  } finally {
                    if (completion != null) completion.countDown();
                  }
                });
          };
      Executor acquisition =
          task ->
              workers.execute(
                  () -> {
                    try {
                      task.run();
                    } finally {
                      retired.countDown();
                    }
                  });
      try (var service = f.service(jobs, acquisition)) {
        var plan = await(service.planCandidates(v.build(), v, BUDGETS));
        var a = located(service, plan, 0);
        nextConsumerRetirement.set(consumerRetired);
        var pending = service.retain(a).toCompletableFuture();
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          assertTrue(pending.cancel(true));
          assertEquals(BUSY, failure(service.probeLoose(choose(plan, 1))));
          assertEquals(
              CanonicalReservation.Status.PUBLISHING, f.files.slots.get(v.slot()).status());
          assertEquals(0, f.files.released);
        } finally {
          release.countDown();
        }
        assertTrue(retired.await(3, TimeUnit.SECONDS));
        // Source retirement does not imply the canceled orchestration has left its slot.
        // Both real workers must finish before a different candidate can be admitted.
        assertTrue(consumerRetired.await(3, TimeUnit.SECONDS));
        assertTrue(f.files.slots.isEmpty());
        assertEquals(1, f.files.released);
        f.gateway.acquisitionFailure = null;
        f.gateway.enteringAcquisition = () -> {};
        var b = await(service.retain(located(service, plan, 1)));
        assertEquals(B, b.descriptor().actualEncodingKey());
        assertEquals(1, f.files.associations.size());
      }
    }
  }

  @Test
  void queuedSourceCancellationAndClosureRetireBeforeReleasingItsReservation() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    var queued = new LinkedBlockingQueue<Runnable>();
    try (var jobs = Executors.newFixedThreadPool(2);
        var service = f.service(jobs, queued::add)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      var pending = service.retain(located(service, plan, 0)).toCompletableFuture();
      var worker = queued.poll(3, TimeUnit.SECONDS);
      assertNotNull(worker);
      assertTrue(pending.cancel(true));
      assertEquals(BUSY, failure(service.probeLoose(choose(plan, 1))));
      // Queued work itself observes service closure even if its consumer worker has not detached
      // yet.
      service.close();
      worker.run();
      assertTrue(f.files.slots.isEmpty());
      assertEquals(0, f.gateway.acquisitions);
    }
  }

  @Test
  void earlyAndLateConsumersShareOneEncodedSourceAcrossBuildsAndContentSlots() throws Exception {
    for (boolean late : List.of(false, true))
      for (boolean differentContent : List.of(false, true)) {
        var f = new SelectedFileFixture();
        var first = f.add("one", C);
        var second = f.add("two", differentContent ? "d".repeat(32) : C);
        var queuedJobs = new LinkedBlockingQueue<Runnable>();
        var events = new LinkedBlockingQueue<Runnable>();
        try (var workers = Executors.newFixedThreadPool(2);
            var service = f.service(queuedJobs::add, events::add)) {
          var firstPlan =
              service.planCandidates(first.build(), first, BUDGETS).toCompletableFuture();
          queuedJobs.remove().run();
          var secondPlan =
              service.planCandidates(second.build(), second, BUDGETS).toCompletableFuture();
          queuedJobs.remove().run();
          var firstProbe = service.probeLoose(choose(await(firstPlan), 0)).toCompletableFuture();
          queuedJobs.remove().run();
          var secondProbe = service.probeLoose(choose(await(secondPlan), 0)).toCompletableFuture();
          queuedJobs.remove().run();
          var one = service.retain(await(firstProbe).plan().orElseThrow()).toCompletableFuture();
          workers.execute(queuedJobs.remove());
          var acquisition = events.poll(3, TimeUnit.SECONDS);
          assertNotNull(acquisition);
          var two = service.retain(await(secondProbe).plan().orElseThrow()).toCompletableFuture();
          var secondJob = queuedJobs.remove();
          if (!late) workers.execute(secondJob);
          if (late) {
            acquisition.run();
            await(one);
            assertFalse(two.isDone());
            workers.execute(secondJob);
          } else {
            one.whenComplete((value, failure) -> events.add(() -> {}));
            two.whenComplete((value, failure) -> events.add(() -> {}));
            acquisition.run();
          }
          // A late job can reuse the canonical slot; an early job can share the same flight.
          // Event-driven progress executes any actually scheduled work, not an assumed single task.
          while (!one.isDone() || !two.isDone()) {
            if (late) {
              two.whenComplete((value, failure) -> events.add(() -> {}));
            }
            var event = events.poll(3, TimeUnit.SECONDS);
            assertNotNull(event);
            event.run();
          }
          var a = await(one);
          var b = await(two);
          assertEquals(1, f.gateway.acquisitions);
          assertEquals(a.representation().encoded(), b.representation().encoded());
          assertEquals(a.representation().sourceReceipt(), b.representation().sourceReceipt());
          assertEquals(first.slot(), a.representation().slot());
          assertEquals(second.slot(), b.representation().slot());
          assertEquals(first, a.descriptor().association());
          assertEquals(second, b.descriptor().association());
          assertEquals(2, f.files.associations.size());
          assertEquals(differentContent ? 2 : 1, f.files.slots.size());
        }
      }
  }

  @Test
  void cancellationOfOneConsumerCannotAttachItOrAffectSurvivingAssociation() throws Exception {
    var f = new SelectedFileFixture();
    var first = f.add("one", C);
    var second = f.add("two", C);
    var attaching = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    f.files.beforeAttach =
        value -> {
          if (value.snapshotId().equals(first.build().snapshotId())) {
            attaching.countDown();
            holdIgnoringInterrupts(release);
          }
        };
    try (var jobs = Executors.newFixedThreadPool(2);
        var service = f.service(jobs, Runnable::run)) {
      var a = await(service.planCandidates(first.build(), first, BUDGETS));
      var b = await(service.planCandidates(second.build(), second, BUDGETS));
      var firstPlan = located(service, a, 0);
      var secondPlan = located(service, b, 0);
      var pending = service.retain(firstPlan).toCompletableFuture();
      try {
        assertTrue(attaching.await(3, TimeUnit.SECONDS));
        assertTrue(pending.cancel(true));
        var survivor = await(service.retain(secondPlan));
        assertEquals(second, survivor.descriptor().association());
        assertEquals(1, f.gateway.acquisitions);
        assertEquals(1, f.files.associations.size());
      } finally {
        release.countDown();
      }
    }
    assertEquals(1, f.files.associations.size());
    assertTrue(
        f.files.associations.values().stream()
            .allMatch(value -> value.snapshotId().equals(second.build().snapshotId())));
  }

  @Test
  void eachExactContextFieldAndLocatorSizeReplaysBeforeAnyPin() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      var payload = located(service, plan, 0);
      var raw =
          new FileVariantAssociation(
              v.build(),
              v.rootSource(),
              v.fileDataId(),
              v.contentKey(),
              v.localeFlags(),
              v.contentFlags() ^ 1,
              v.rawFlagsFirst(),
              v.rawFlagsSecond(),
              v.rawFlagsThird(),
              v.nameHash());
      var otherBuild = variant(build("two"), C);
      // Keep the old root context so the constructor is structurally valid; gateway replay
      // must still reject changing the full build identity.
      var rebound =
          new FileVariantAssociation(
              otherBuild.build(),
              v.rootSource(),
              v.fileDataId(),
              v.contentKey(),
              v.localeFlags(),
              v.contentFlags(),
              v.rawFlagsFirst(),
              v.rawFlagsSecond(),
              v.rawFlagsThird(),
              v.nameHash());
      var proof =
          new FileAssociationProof(
              plan.proof().rootSource(),
              plan.proof().encodingBootstrapSource(),
              plan.proof().verifierRevision(),
              "0".repeat(64),
              plan.proof().declaredDecodedBytes(),
              plan.proof().authenticatedCandidates());
      var changedConfig = alias(plan.configuration().source(), 999, 20);
      var variations =
          List.of(
              new CandidatePlan(
                  plan.sessionId(),
                  raw,
                  plan.configuration(),
                  plan.root(),
                  plan.proof(),
                  plan.candidates(),
                  plan.budgets()),
              new CandidatePlan(
                  plan.sessionId(),
                  rebound,
                  plan.configuration(),
                  plan.root(),
                  plan.proof(),
                  plan.candidates(),
                  plan.budgets()),
              new CandidatePlan(
                  plan.sessionId(),
                  v,
                  changedConfig,
                  plan.root(),
                  plan.proof(),
                  plan.candidates(),
                  plan.budgets()),
              new CandidatePlan(
                  plan.sessionId(),
                  v,
                  plan.configuration(),
                  plan.root(),
                  proof,
                  plan.candidates(),
                  plan.budgets()),
              new CandidatePlan(
                  "unissued-session",
                  v,
                  plan.configuration(),
                  plan.root(),
                  plan.proof(),
                  plan.candidates(),
                  plan.budgets()));
      for (var changed : variations)
        assertEquals(INVALID_PLAN, failure(service.probeLoose(choose(changed, 0))));
      var original = payload.acquisition().orElseThrow();
      var changed =
          new LocatedFilePlan(
              original.sessionId(),
              original.choice(),
              new FileLocator(
                  FileLocator.Kind.LOOSE,
                  original.locator().resourceUrl(),
                  A,
                  13,
                  0,
                  13,
                  Optional.empty()),
              13,
              new AcquisitionCost(13, 13));
      assertEquals(
          INVALID_PLAN,
          failure(
              service.retain(
                  new SelectedFilePlan(
                      original.choice(), Optional.of(changed), Optional.empty(), changed.cost()))));
      assertTrue(f.pins.scopes.isEmpty());
      assertTrue(f.files.slots.isEmpty());
      assertEquals(0, f.gateway.acquisitions);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new CandidatePlan(
                  plan.sessionId(),
                  v,
                  plan.configuration(),
                  plan.root(),
                  plan.proof(),
                  List.of(plan.candidates().getLast()),
                  plan.budgets()));
    }
  }

  @Test
  void immutableOriginalPayloadOriginAndNewBuildAssociationOriginsStaySeparate() throws Exception {
    var f = new SelectedFileFixture();
    var one = f.add("one", C);
    var two = f.add("two", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var first = await(service.planCandidates(one.build(), one, BUDGETS));
      var second = await(service.planCandidates(two.build(), two, BUDGETS));
      var firstPayload = located(service, first, 0);
      var secondPayload = located(service, second, 0);
      assertNotEquals(
          firstPayload.acquisition().orElseThrow().locator().resourceUrl(),
          secondPayload.acquisition().orElseThrow().locator().resourceUrl());
      var a = await(service.retain(firstPayload));
      var b = await(service.retain(secondPayload));
      assertEquals(a.representation(), b.representation());
      assertTrue(b.representation().encoded().evidence().sourceUrl().contains("us.cdn"));
      assertTrue(b.root().evidence().sourceUrl().contains("kr.cdn"));
      assertNotEquals(a.configuration().source(), b.configuration().source());
      assertNotEquals(a.root().source(), b.root().source());
      assertNotEquals(a.proof().encodingBootstrapSource(), b.proof().encodingBootstrapSource());
      assertNotEquals(a.proof().proofDigest(), b.proof().proofDigest());
      var closure = f.files.attachedScopes.get(b.snapshotId() + b.role());
      assertEquals(b.snapshotId(), closure.snapshotId());
      assertEquals(
          Optional.of(b.configuration().source()),
          closure.requirements().stream()
              .filter(r -> r.role().equals("BUILD_CONFIG"))
              .findFirst()
              .orElseThrow()
              .source());
      assertEquals(
          Optional.of(b.root().source()),
          closure.requirements().stream()
              .filter(r -> r.role().equals("ROOT_MANIFEST"))
              .findFirst()
              .orElseThrow()
              .source());
      assertEquals(1, f.gateway.acquisitions);
    }
  }

  @Test
  void rejectedSourceWorkerReleasesUnpublishedSlotAndQueuedJobsStayBounded() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    Executor rejected =
        task -> {
          throw new RejectedExecutionException("No worker");
        };
    try (var service = f.service(Runnable::run, rejected)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      assertEquals(CAPACITY_EXCEEDED, failure(service.retain(located(service, plan, 0))));
      assertTrue(f.files.slots.isEmpty());
      assertEquals(1, f.files.released);
      assertEquals(0, f.gateway.acquisitions);
    }
    var jobs = new LinkedBlockingQueue<Runnable>();
    try (var service = f.service(jobs::add, Runnable::run)) {
      var pending = new ArrayList<CompletableFuture<CandidatePlan>>();
      for (int i = 0; i < 4; i++)
        pending.add(service.planCandidates(v.build(), v, BUDGETS).toCompletableFuture());
      assertEquals(CAPACITY_EXCEEDED, failure(service.planCandidates(v.build(), v, BUDGETS)));
      pending.forEach(task -> task.cancel(true));
      while (!jobs.isEmpty()) jobs.remove().run();
      assertEquals(1, f.gateway.preparations);
      service.close();
      assertEquals(CLOSED, failure(service.planCandidates(v.build(), v, BUDGETS)));
    }
  }

  @Test
  void completeEncodedReceiptWithoutMandatoryBeforePromotionGateIsNeverAttached() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    var gateway =
        new Gateway(f.files) {
          public ContentRepresentation acquireEncoded(
              LocatedFilePlan plan,
              EncodedPublicationObserver observer,
              io.vibeminer.application.CancellationToken token) {
            var value = representation(plan);
            files.physical.add(value.encoded().object().digest());
            return value; // deliberately violates the outbound completion contract
          }
        };
    try (var service =
        new DefaultSelectedFileService(
            gateway, f.builds, f.files, f.pins, Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      assertEquals(INTEGRITY_FAILURE, failure(service.retain(located(service, plan, 0))));
      assertTrue(f.files.associations.isEmpty());
      assertEquals(CanonicalReservation.Status.RECOVERY, f.files.slots.get(v.slot()).status());
      assertEquals(BUSY, failure(service.probeLoose(choose(plan, 1))));
    }
  }

  @Test
  void forged404CannotBypassItsLedgerEvenWhenCanonicalBytesBecomeReady() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var plan = await(service.planCandidates(v.build(), v, BUDGETS));
      f.gateway.looseMissing = true;
      var permit = await(service.probeLoose(choose(plan, 0))).missing().orElseThrow();
      f.gateway.looseMissing = false;
      await(service.retain(located(service, plan, 0)));
      assertEquals(
          INVALID_PLAN,
          failure(
              service.searchArchives(
                  new Loose404Permit("forged", permit.choice()), ArchiveSearchBudgets.defaults())));
      f.gateway.permits.clear();
      assertEquals(
          INVALID_PLAN, failure(service.searchArchives(permit, ArchiveSearchBudgets.defaults())));
      assertEquals(0, f.gateway.searches);
      for (var code :
          List.of(UNAVAILABLE, INTEGRITY_FAILURE, BUDGET_EXCEEDED, CANCELLED, UNSUPPORTED)) {
        // Use an empty content slot so a cached receipt cannot legitimately avoid the probe.
        var other = f.add("two", ("d".repeat(32)));
        var fresh = await(service.planCandidates(other.build(), other, BUDGETS));
        f.gateway.probeFailure = error(code);
        assertEquals(code, failure(service.probeLoose(choose(fresh, 0))));
      }
      assertEquals(0, f.gateway.searches);
    }
  }

  @Test
  void missingCanonicalBytesNeedNewKnownCostConsentAndRepairOnlyActualEncoding() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(service.planCandidates(v.build(), v, BUDGETS));
      var original = await(service.retain(located(service, candidates, 0)));
      var zeroCost = located(service, candidates, 0);
      assertEquals(0, zeroCost.cost().encodedTransferBytes());
      f.files.physical.remove(original.representation().encoded().object().digest());
      assertEquals(MISSING_LOCAL, failure(service.retain(zeroCost)));
      assertEquals(1, f.gateway.acquisitions);
      assertEquals(REPLAN_REQUIRED, failure(service.probeLoose(choose(candidates, 1))));
      var repair = located(service, candidates, 0);
      assertEquals(12, repair.cost().encodedTransferBytes());
      assertTrue(repair.acquisition().isPresent());
      var restored = await(service.retain(repair));
      assertEquals(original.representation(), restored.representation());
      assertEquals(2, f.gateway.acquisitions);
      assertEquals(2, f.gateway.probes);
      assertEquals(original, await(service.inspectOffline(original.descriptor(), BUDGETS)));
    }
  }

  @Test
  void repeatedObservationPreservesFirstAssociationWithoutFalseOfflineConflict() throws Exception {
    var f = new SelectedFileFixture();
    var v = f.add("one", C);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var first = await(service.planCandidates(v.build(), v, BUDGETS));
      var retained = await(service.retain(located(service, first, 0)));
      var old = v.build();
      var observed =
          new io.vibeminer.domain.BuildSnapshot(
              old.productId(),
              old.editionLabel(),
              old.channel(),
              old.region(),
              old.versionLabel(),
              old.buildConfig(),
              old.cdnConfig(),
              old.observedAt().plusSeconds(10),
              old.fixture(),
              old.evidence());
      var selected =
          new FileVariantAssociation(
              observed,
              v.rootSource(),
              v.fileDataId(),
              v.contentKey(),
              v.localeFlags(),
              v.contentFlags(),
              v.rawFlagsFirst(),
              v.rawFlagsSecond(),
              v.rawFlagsThird(),
              v.nameHash());
      var next = await(service.planCandidates(observed, selected, BUDGETS));
      var reused = await(service.retain(located(service, next, 0)));
      assertEquals(retained, reused);
      var requested =
          new SelectedFileDescriptor(selected, A, ContentRepresentation.VERIFIER_SEMANTICS);
      assertEquals(retained, await(service.inspectOffline(requested, BUDGETS)));
      assertEquals(1, f.gateway.acquisitions);
    }
  }

  @Test
  void stalePendingReservationRechecksCommittedSourceAfterPreviousFlightRetires() throws Exception {
    for (boolean differentContent : List.of(false, true)) {
      var f = new SelectedFileFixture();
      var one = f.add("one", C);
      var two = f.add("two", differentContent ? "d".repeat(32) : C);
      var sourceQueue = new LinkedBlockingQueue<Runnable>();
      var paused = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      try (var jobs = Executors.newFixedThreadPool(2);
          var service = f.service(jobs, sourceQueue::add)) {
        var a = await(service.planCandidates(one.build(), one, BUDGETS));
        var b = await(service.planCandidates(two.build(), two, BUDGETS));
        var aPlan = located(service, a, 0);
        var bPlan = located(service, b, 0);
        var first = service.retain(aPlan).toCompletableFuture();
        var firstWorker = sourceQueue.poll(3, TimeUnit.SECONDS);
        assertNotNull(firstWorker);
        var pauseOnce = new java.util.concurrent.atomic.AtomicBoolean();
        f.files.afterReserve =
            () -> {
              if (pauseOnce.compareAndSet(false, true)) {
                paused.countDown();
                holdIgnoringInterrupts(release);
              }
            };
        var second = service.retain(bPlan).toCompletableFuture();
        try {
          assertTrue(paused.await(3, TimeUnit.SECONDS));
          // B now holds an earlier PUBLISHING snapshot but has not joined the source flight.
          firstWorker.run();
          await(first);
          assertFalse(second.isDone());
        } finally {
          release.countDown();
        }
        var lateWorker = sourceQueue.poll(3, TimeUnit.SECONDS);
        assertNotNull(lateWorker);
        lateWorker.run();
        var original = await(first);
        var adopted = await(second);
        assertEquals(
            1,
            f.gateway.acquisitions,
            "A retired flight must be rechecked before any source fetch");
        assertEquals(original.representation().encoded(), adopted.representation().encoded());
        assertEquals(
            original.representation().sourceReceipt(), adopted.representation().sourceReceipt());
        assertEquals(two.slot(), adopted.representation().slot());
      }
    }
  }

  @Test
  void successfulClosingFlightWaitsForPublicationBeforeAdoptingOriginalSource() throws Exception {
    for (boolean differentContent : List.of(false, true)) {
      for (String outcome : List.of("success", "cancel", "failure")) {
        var f = new SelectedFileFixture();
        var one = f.add("one", C);
        var two = f.add("two", differentContent ? "d".repeat(32) : C);
        var publicationEntered = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var followerPublished = new CountDownLatch(1);
        var followerRetired = new CountDownLatch(1);
        var firstPublication = new java.util.concurrent.atomic.AtomicBoolean();
        var nextIsFollower = new java.util.concurrent.atomic.AtomicBoolean();
        var followerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var catalog =
            (SelectedFileCatalog)
                java.lang.reflect.Proxy.newProxyInstance(
                    SelectedFileCatalog.class.getClassLoader(),
                    new Class<?>[] {SelectedFileCatalog.class},
                    (proxy, method, arguments) -> {
                      if (method.getName().equals("completePublication")
                          && firstPublication.compareAndSet(false, true)) {
                        publicationEntered.countDown();
                        holdIgnoringInterrupts(releasePublication);
                        if (outcome.equals("failure"))
                          throw new IllegalStateException("Synthetic publication failure");
                      }
                      try {
                        return method.invoke(f.files, arguments);
                      } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        try (var jobWorkers = Executors.newFixedThreadPool(2);
            var sourceWorkers = Executors.newSingleThreadExecutor()) {
          Executor jobs =
              task -> {
                boolean follower = nextIsFollower.getAndSet(false);
                jobWorkers.execute(
                    () -> {
                      if (follower) followerThread.set(Thread.currentThread());
                      try {
                        task.run();
                      } finally {
                        if (follower) followerRetired.countDown();
                      }
                    });
              };
          try (var service =
              new DefaultSelectedFileService(
                  f.gateway, f.builds, catalog, f.pins, jobs, sourceWorkers)) {
            var a = await(service.planCandidates(one.build(), one, BUDGETS));
            var b = await(service.planCandidates(two.build(), two, BUDGETS));
            var aPlan = located(service, a, 0);
            var bPlan = located(service, b, 0);
            var first = service.retain(aPlan).toCompletableFuture();
            try {
              assertTrue(publicationEntered.await(3, TimeUnit.SECONDS));
              // The source has frozen its publication list, but cannot commit or retire yet.
              f.files.afterReserve = followerPublished::countDown;
              nextIsFollower.set(true);
              var second = service.retain(bPlan).toCompletableFuture();
              assertTrue(followerPublished.await(3, TimeUnit.SECONDS));
              // All follower ports before the join have returned and none block. Observe the
              // actual wait or actual completion, rather than assuming submission means joining.
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
              boolean waiting = false;
              while (followerRetired.getCount() != 0 && System.nanoTime() < deadline) {
                var state = followerThread.get().getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                  waiting = true;
                  break;
                }
                Thread.onSpinWait();
              }
              assertTrue(waiting, "A normal closing-flight follower must wait, not return BUSY");
              assertFalse(second.isDone(), "Publication is still blocked");
              if (outcome.equals("cancel")) assertTrue(second.cancel(true));
              releasePublication.countDown();
              if (outcome.equals("failure")) {
                assertEquals(STORAGE_UNAVAILABLE, failure(first));
                assertEquals(STORAGE_UNAVAILABLE, failure(second));
                assertTrue(f.files.associations.isEmpty());
                assertEquals(
                    CanonicalReservation.Status.RECOVERY, f.files.slots.get(one.slot()).status());
              } else {
                var original = await(first);
                if (outcome.equals("cancel")) {
                  assertThrows(CancellationException.class, () -> await(second));
                  assertTrue(followerRetired.await(3, TimeUnit.SECONDS));
                  if (differentContent) assertFalse(f.files.slots.containsKey(two.slot()));
                  else
                    assertEquals(
                        CanonicalReservation.Status.RETAINED,
                        f.files.slots.get(one.slot()).status());
                  assertEquals(1, f.files.associations.size());
                  assertEquals(original, f.files.associations.values().iterator().next());
                } else {
                  var adopted = await(second);
                  assertEquals(
                      original.representation().encoded(), adopted.representation().encoded());
                  assertEquals(
                      original.representation().sourceReceipt(),
                      adopted.representation().sourceReceipt());
                  assertEquals(two.slot(), adopted.representation().slot());
                  assertEquals(two, adopted.descriptor().association());
                  assertEquals(2, f.files.associations.size());
                }
              }
              assertEquals(1, f.gateway.acquisitions);
            } finally {
              releasePublication.countDown();
            }
          }
        }
      }
    }
  }

  static void holdIgnoringInterrupts(CountDownLatch release) {
    boolean interrupted = false;
    while (true) {
      try {
        release.await();
        break;
      } catch (InterruptedException cancelled) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
