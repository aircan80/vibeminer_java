package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.DefaultSelectedFileServiceTest.*;
import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;
import static io.vibeminer.application.selectedfile.SelectedFileFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.content.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SelectedFileWorkerOwnershipTest {
  @Test
  void actualSourceOwnsPermitThroughProspectiveGateAndRetiresAfterPublication() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    f.gateway.afterProspectiveGate =
        () -> {
          assertEquals(1, f.files.workers.size());
          assertTrue(f.files.slots.get(variant.slot()).representation().isPresent());
          assertTrue(f.files.physical.isEmpty());
        };
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(service.planCandidates(variant.build(), variant, BUDGETS));
      await(service.retain(located(service, candidates, 0)));
      assertTrue(f.files.workers.isEmpty());
      assertEquals(1, f.files.retiredWorkers.size());
      assertEquals(1, f.gateway.acquisitions);
    }
  }

  @Test
  void competingPublisherCannotAcquireMutateOrRetireAnotherWorkersPermit() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    var reservation = f.files.reserve(variant.slot(), A, 12, () -> false);
    var foreign = f.files.claimWorker(reservation.token(), () -> false);
    f.files.markPublishing(foreign, () -> false);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(service.planCandidates(variant.build(), variant, BUDGETS));
      assertEquals(BUSY, failure(service.retain(located(service, candidates, 0))));
      assertEquals(foreign, f.files.workers.get(reservation.token()));
      assertEquals(0, f.gateway.acquisitions);
      assertEquals(0, f.files.released);
      assertTrue(f.files.retiredWorkers.isEmpty());
      f.files.finishWorker(
          foreign, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, () -> false);
      await(service.retain(located(service, candidates, 0)));
      assertEquals(1, f.gateway.acquisitions);
      assertTrue(f.files.workers.isEmpty());
    }
  }

  @Test
  void everyJoinedSlotHasProspectiveProofBeforeCasEvenWhenOriginalConsumerCancels()
      throws Exception {
    var f = new SelectedFileFixture();
    var first = f.add("one", C);
    var second = f.add("two", "d".repeat(32));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var joined = new CountDownLatch(1);
    f.gateway.enteringAcquisition =
        () -> {
          entered.countDown();
          holdIgnoringInterrupts(release);
        };
    f.files.afterWorkerClaim =
        permit -> {
          if (f.files.find(permit.reservationToken()).slot().equals(second.slot()))
            joined.countDown();
        };
    f.gateway.afterProspectiveGate =
        () -> {
          assertEquals(2, f.files.workers.size());
          assertTrue(f.files.physical.isEmpty());
          for (var slot : f.files.slots.values()) assertTrue(slot.representation().isPresent());
        };
    try (var jobs = Executors.newFixedThreadPool(2);
        var sources = Executors.newSingleThreadExecutor();
        var service = f.service(jobs, sources)) {
      var a = await(service.planCandidates(first.build(), first, BUDGETS));
      var b = await(service.planCandidates(second.build(), second, BUDGETS));
      var aPlan = located(service, a, 0);
      var bPlan = located(service, b, 0);
      var original = service.retain(aPlan).toCompletableFuture();
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var survivor = service.retain(bPlan).toCompletableFuture();
        assertTrue(joined.await(3, TimeUnit.SECONDS));
        assertTrue(original.cancel(true));
        release.countDown();
        assertEquals(second, await(survivor).descriptor().association());
        assertEquals(1, f.files.associations.size());
        assertEquals(1, f.gateway.acquisitions);
        assertEquals(2, f.files.retiredWorkers.size());
        assertTrue(f.files.workers.isEmpty());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void foreignFanoutSlotRejectsOnlyJoiningConsumerAndPrimarySourceStillCompletes()
      throws Exception {
    var f = new SelectedFileFixture();
    var first = f.add("one", C);
    var second = f.add("two", "d".repeat(32));
    var occupied = f.files.reserve(second.slot(), A, 12, () -> false);
    var foreign = f.files.claimWorker(occupied.token(), () -> false);
    f.files.markPublishing(foreign, () -> false);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    f.gateway.enteringAcquisition =
        () -> {
          entered.countDown();
          holdIgnoringInterrupts(release);
        };
    try (var jobs = Executors.newFixedThreadPool(2);
        var sources = Executors.newSingleThreadExecutor();
        var service = f.service(jobs, sources)) {
      var a = await(service.planCandidates(first.build(), first, BUDGETS));
      var b = await(service.planCandidates(second.build(), second, BUDGETS));
      var aPlan = located(service, a, 0);
      var bPlan = located(service, b, 0);
      var primary = service.retain(aPlan).toCompletableFuture();
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(BUSY, failure(service.retain(bPlan)));
        assertEquals(foreign, f.files.workers.get(occupied.token()));
        assertFalse(primary.isDone());
        assertEquals(0, f.files.released);
        release.countDown();
        await(primary);
        assertEquals(foreign, f.files.workers.get(occupied.token()));
        assertEquals(1, f.files.retiredWorkers.size());
        f.files.finishWorker(
            foreign, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, () -> false);
        await(service.retain(bPlan));
        assertEquals(1, f.gateway.acquisitions);
        assertEquals(2, f.files.associations.size());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void staleNonceCannotRecordOrRetireReplacementWorker() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("one", C);
    var reservation = f.files.reserve(variant.slot(), A, 12, () -> false);
    var old = f.files.claimWorker(reservation.token(), () -> false);
    f.files.markPublishing(old, () -> false);
    f.files.finishWorker(old, ContentWorkerFinishOutcome.UNCERTAIN, () -> false);
    var replacement = f.files.claimWorker(reservation.token(), () -> false);
    try (var service = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(service.planCandidates(variant.build(), variant, BUDGETS));
      var proof = representation(f.gateway.locate(choose(candidates, 0)));
      assertThrows(
          SelectedFileException.class,
          () -> f.files.recordVerifiedCompletion(old, proof, () -> false));
      assertThrows(
          SelectedFileException.class,
          () ->
              f.files.finishWorker(
                  old, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, () -> false));
      assertEquals(replacement, f.files.workers.get(reservation.token()));
      assertTrue(f.files.slots.get(variant.slot()).representation().isEmpty());
      f.files.finishWorker(replacement, ContentWorkerFinishOutcome.UNCERTAIN, () -> false);
    }
  }

  @Test
  void fanoutProofRefusalAbortsCasAndRetiresEveryHeldPermitAsUncertain() throws Exception {
    var f = new SelectedFileFixture();
    var first = f.add("one", C);
    var second = f.add("two", "d".repeat(32));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var admitted = new CountDownLatch(1);
    f.gateway.enteringAcquisition =
        () -> {
          entered.countDown();
          holdIgnoringInterrupts(release);
        };
    f.files.afterWorkerClaim =
        permit -> {
          if (f.files.find(permit.reservationToken()).slot().equals(second.slot()))
            admitted.countDown();
        };
    f.gateway.afterProspectiveGate =
        () -> fail("A refused fanout proof cannot reach CAS promotion");
    var catalog =
        (SelectedFileCatalog)
            java.lang.reflect.Proxy.newProxyInstance(
                SelectedFileCatalog.class.getClassLoader(),
                new Class<?>[] {SelectedFileCatalog.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("recordVerifiedCompletion")
                      && arguments[0] instanceof ContentWorkerPermit permit
                      && f.files.find(permit.reservationToken()).slot().equals(second.slot()))
                    throw error(INTEGRITY_FAILURE);
                  try {
                    return method.invoke(f.files, arguments);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    try (var jobs = Executors.newFixedThreadPool(2);
        var sources = Executors.newSingleThreadExecutor();
        var service =
            new DefaultSelectedFileService(f.gateway, f.builds, catalog, f.pins, jobs, sources)) {
      var a = await(service.planCandidates(first.build(), first, BUDGETS));
      var b = await(service.planCandidates(second.build(), second, BUDGETS));
      var aPlan = located(service, a, 0);
      var bPlan = located(service, b, 0);
      var original = service.retain(aPlan);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var follower = service.retain(bPlan);
        assertTrue(admitted.await(3, TimeUnit.SECONDS));
        release.countDown();
        assertEquals(INTEGRITY_FAILURE, failure(original));
        assertEquals(INTEGRITY_FAILURE, failure(follower));
        assertTrue(f.files.physical.isEmpty());
        assertTrue(f.files.associations.isEmpty());
        assertEquals(2, f.files.retiredWorkers.size());
        assertTrue(f.files.workers.isEmpty());
        assertTrue(
            f.files.slots.values().stream()
                .allMatch(
                    slot ->
                        slot.status()
                            == io.vibeminer.domain.selectedfile.CanonicalReservation.Status
                                .RECOVERY));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void concreteFlightFanoutIsBoundedAndCanceledSubscribersCannotRetireItsPermits()
      throws Exception {
    var f = new SelectedFileFixture();
    var primaryVariant = f.add("primary", C);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var nextRetirement = new java.util.concurrent.atomic.AtomicReference<CountDownLatch>();
    f.gateway.enteringAcquisition =
        () -> {
          entered.countDown();
          holdIgnoringInterrupts(release);
        };
    f.gateway.afterProspectiveGate =
        () -> {
          assertEquals(32, f.files.workers.size());
          assertTrue(f.files.physical.isEmpty());
          assertTrue(
              f.files.slots.values().stream().allMatch(slot -> slot.representation().isPresent()));
        };
    try (var jobWorkers = Executors.newFixedThreadPool(2);
        var sources = Executors.newSingleThreadExecutor()) {
      Executor jobs =
          task -> {
            var retired = nextRetirement.getAndSet(null);
            jobWorkers.execute(
                () -> {
                  try {
                    task.run();
                  } finally {
                    if (retired != null) retired.countDown();
                  }
                });
          };
      try (var service = f.service(jobs, sources)) {
        var firstPlan =
            await(service.planCandidates(primaryVariant.build(), primaryVariant, BUDGETS));
        var primary = service.retain(located(service, firstPlan, 0));
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          for (int index = 1; index < 32; index++) {
            var variant = f.add("fanout-" + index, String.format("%032x", index));
            var candidates = await(service.planCandidates(variant.build(), variant, BUDGETS));
            var payload = located(service, candidates, 0);
            var admitted = new CountDownLatch(1);
            var retired = new CountDownLatch(1);
            f.files.afterWorkerClaim =
                permit -> {
                  if (f.files.find(permit.reservationToken()).slot().equals(variant.slot()))
                    admitted.countDown();
                };
            nextRetirement.set(retired);
            var subscriber = service.retain(payload).toCompletableFuture();
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertTrue(subscriber.cancel(true));
            assertTrue(retired.await(3, TimeUnit.SECONDS));
          }
          assertEquals(32, f.files.workers.size());
          var excess = f.add("excess", "f".repeat(32));
          var candidates = await(service.planCandidates(excess.build(), excess, BUDGETS));
          assertEquals(CAPACITY_EXCEEDED, failure(service.retain(located(service, candidates, 0))));
          assertFalse(f.files.slots.containsKey(excess.slot()));
          assertEquals(32, f.files.workers.size());
          release.countDown();
          await(primary);
          assertEquals(1, f.gateway.acquisitions);
          assertEquals(1, f.files.associations.size());
          assertEquals(33, f.files.retiredWorkers.size());
          assertTrue(f.files.workers.isEmpty());
        } finally {
          release.countDown();
        }
      }
    }
  }
}
