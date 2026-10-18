package io.vibeminer.application.lookup;

import static io.vibeminer.application.lookup.DefaultFileLookupServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FileLookupRetentionTest {
  static final class Pins implements RetentionRepository {
    final List<RetentionScope> scopes = new CopyOnWriteArrayList<>();
    Runnable beforePin = () -> {};
    Runnable afterPin = () -> {};
    final Map<String, RetentionScope> active = new ConcurrentHashMap<>();

    public RetentionScope pin(RetentionScope scope, CancellationToken token) {
      token.throwIfCancellationRequested();
      beforePin.run();
      scopes.add(scope);
      active.put(scope.snapshotId(), scope);
      afterPin.run();
      return scope;
    }

    public RetentionScope replaceUnfulfilledRoot(RetentionScope scope, CancellationToken token) {
      return pin(scope, token);
    }

    public void release(String scopeId, CancellationToken token) {
      fail("No automatic release");
    }

    public RetentionView readView(String id, RetentionLimits limits, CancellationToken token) {
      throw new AssertionError("No reporting in acquisition");
    }
  }

  @Test
  void planningAndOfflineDoNotPinAndAcquisitionFollowsValidatedExactScope() {
    var pins = new Pins();
    var catalog = new Catalog();
    var build = build("one");
    var gateway =
        new Gateway() {
          public VerifiedSourceAlias acquireRoot(
              RootSelectionPlan selection, CancellationToken token) {
            assertEquals(1, pins.scopes.size());
            assertTrue(validations > 0);
            return super.acquireRoot(selection, token);
          }
        };
    pins.beforePin = () -> assertFalse(catalog.listReferences(build.snapshotId()).isEmpty());
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
      var plan = service.planRoot(build, BUDGETS).toCompletableFuture().join();
      assertTrue(pins.scopes.isEmpty());
      service.retainRoot(select(plan)).toCompletableFuture().join();
      var scope = pins.scopes.getFirst();
      assertEquals(ScopeKind.ROOT_METADATA, scope.kind());
      assertEquals(build.snapshotId(), scope.snapshotId());
      assertEquals(
          List.of(
              new ScopeRequirement(
                  "BUILD_CONFIG", Optional.of(CONFIG), OptionalLong.of(8), OptionalLong.of(8)),
              new ScopeRequirement(
                  "ROOT_MANIFEST", Optional.of(ROOT), OptionalLong.of(8), OptionalLong.of(10))),
          scope.requirements());
      service.lookupOffline(build, Set.of(7L), BUDGETS).toCompletableFuture().join();
      assertEquals(1, pins.scopes.size());
    }
  }

  @Test
  void invalidProofAndConflictNeverPin() {
    var pins = new Pins();
    var gateway = new Gateway();
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      gateway.expired = true;
      assertEquals(
          FileLookupException.Code.INVALID_PLAN, failure(service.retainRoot(select(plan))).code());
      gateway.expired = false;
      var other = receipt(new ResolvedSourceIdentity("other", "key", "v1"), 9);
      catalog.attachVerifiedObject(
          plan.build().snapshotId(),
          "ROOT_MANIFEST",
          other.source(),
          other.object(),
          other.evidence(),
          () -> false);
      assertEquals(
          FileLookupException.Code.INVALID_PLAN, failure(service.retainRoot(select(plan))).code());
      assertTrue(pins.scopes.isEmpty());
    }
  }

  @Test
  void failureKeepsIntentAndSeparateBuildsPinSeparateScopesOverSharedBytes() {
    var pins = new Pins();
    var catalog = new Catalog();
    var gateway =
        new Gateway() {
          boolean failOnce = true;

          public VerifiedSourceAlias acquireRoot(
              RootSelectionPlan selection, CancellationToken token) {
            if (failOnce) {
              failOnce = false;
              throw new FileLookupException(FileLookupException.Code.UNAVAILABLE, "Missing object");
            }
            return super.acquireRoot(selection, token);
          }
        };
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
      var first = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      assertEquals(
          FileLookupException.Code.UNAVAILABLE, failure(service.retainRoot(select(first))).code());
      assertEquals(1, pins.scopes.size());
      assertEquals(1, catalog.listReferences(first.build().snapshotId()).size());
      service.retainRoot(select(first)).toCompletableFuture().join();
      var second = service.planRoot(build("two"), BUDGETS).toCompletableFuture().join();
      service.retainRoot(select(second)).toCompletableFuture().join();
      assertNotEquals(pins.scopes.getFirst().scopeId(), pins.scopes.getLast().scopeId());
      assertEquals(pins.scopes.getFirst().requirements(), pins.scopes.getLast().requirements());
      assertEquals(1, gateway.acquisitions);
    }
  }

  @Test
  void cancellationAfterCommittedPinKeepsPartialScopeWithoutTransfer() throws Exception {
    var pins = new Pins();
    var catalog = new Catalog();
    var gateway = new Gateway();
    var queued = new CountDownLatch(1);
    Executor acquisition = task -> queued.countDown();
    try (var jobs = Executors.newSingleThreadExecutor();
        var service =
            new DefaultFileLookupService(gateway, catalog, STORE, jobs, acquisition, pins)) {
      var plan =
          service.planRoot(build("one"), BUDGETS).toCompletableFuture().get(3, TimeUnit.SECONDS);
      var retain = service.retainRoot(select(plan)).toCompletableFuture();
      assertTrue(queued.await(3, TimeUnit.SECONDS));
      assertTrue(retain.cancel(true));
      assertEquals(1, pins.scopes.size());
      assertEquals(0, gateway.acquisitions);
      assertEquals(1, catalog.listReferences(plan.build().snapshotId()).size());
    }
  }

  @Test
  void excessiveDecodedWorkAndPinFailureCannotStartAcquisition() {
    var pins = new Pins();
    var catalog = new Catalog();
    var gateway =
        new Gateway() {
          public RootPlan prepareRoot(
              BuildSnapshot build,
              Optional<VerifiedSourceAlias> retained,
              LookupBudgets budgets,
              CancellationToken token) {
            var value = super.prepareRoot(build, retained, budgets, token);
            canonical =
                new RootPlan(
                    value.sessionId(),
                    build,
                    value.configuration(),
                    value.contentKey(),
                    budgets.maximumRootDecodedBytes() + 1,
                    value.candidates(),
                    budgets);
            return canonical;
          }
        };
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      assertEquals(
          FileLookupException.Code.BUDGET_EXCEEDED,
          failure(service.retainRoot(select(plan))).code());
      assertTrue(pins.scopes.isEmpty());
      assertEquals(0, gateway.acquisitions);
    }
    var valid = new Gateway();
    pins.beforePin =
        () -> {
          throw new RetentionException(RetentionException.Code.STORAGE_UNAVAILABLE, "Cannot pin");
        };
    try (var service =
        new DefaultFileLookupService(valid, catalog, STORE, Runnable::run, Runnable::run, pins)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      assertEquals(
          FileLookupException.Code.STORAGE_FAILURE,
          failure(service.retainRoot(select(plan))).code());
      assertTrue(pins.scopes.isEmpty());
      assertEquals(0, valid.acquisitions);
    }
  }

  @Test
  void retentionFailuresKeepTypedMeaningAndNeverAcquire() {
    var pins = new Pins();
    var catalog = new Catalog();
    var gateway = new Gateway();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      var mappings =
          Map.of(
              RetentionException.Code.INTEGRITY_FAILURE,
              FileLookupException.Code.INTEGRITY_FAILURE,
              RetentionException.Code.STORAGE_UNAVAILABLE,
              FileLookupException.Code.STORAGE_FAILURE,
              RetentionException.Code.CLOSED,
              FileLookupException.Code.CLOSED,
              RetentionException.Code.CAPACITY_EXCEEDED,
              FileLookupException.Code.CAPACITY_EXCEEDED);
      mappings.forEach(
          (code, expected) -> {
            pins.beforePin =
                () -> {
                  throw new RetentionException(code, "Pin failed");
                };
            assertEquals(expected, failure(service.retainRoot(select(plan))).code());
          });
      assertTrue(pins.scopes.isEmpty());
      assertEquals(0, gateway.acquisitions);
    }
  }

  static final class Choices extends Gateway {
    boolean unavailableA;

    public RootPlan prepareRoot(
        BuildSnapshot build,
        Optional<VerifiedSourceAlias> retained,
        LookupBudgets budgets,
        CancellationToken token) {
      var base = super.prepareRoot(build, retained, budgets, token);
      canonical =
          new RootPlan(
              base.sessionId(),
              build,
              base.configuration(),
              base.contentKey(),
              base.decodedBytes(),
              List.of(base.candidates().getFirst(), new RootCandidate("e".repeat(32), 8, 1)),
              budgets);
      return canonical;
    }

    public ResolvedSourceIdentity rootIdentity(RootSelectionPlan selected) {
      return new ResolvedSourceIdentity(
          ROOT.namespace(),
          ROOT.immutableSourceKey() + ":" + selected.candidate().encodingKey(),
          ROOT.verificationSemantics());
    }

    public VerifiedSourceAlias acquireRoot(RootSelectionPlan selected, CancellationToken token) {
      acquisitions++;
      if (unavailableA && selected.candidate().encodingKey().startsWith("d"))
        throw new FileLookupException(
            FileLookupException.Code.UNAVAILABLE, "Candidate A unavailable");
      return receipt(rootIdentity(selected), 2);
    }
  }

  @Test
  void unavailableCandidateAllowsLaterExplicitAlternativeButAttachedRootStaysImmutable() {
    var pins = new Pins();
    var catalog = new Catalog();
    var gateway = new Choices();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      var a = new RootSelectionPlan(plan, plan.candidates().getFirst());
      var b = new RootSelectionPlan(plan, plan.candidates().getLast());
      gateway.unavailableA = true;
      assertEquals(FileLookupException.Code.UNAVAILABLE, failure(service.retainRoot(a)).code());
      var historical = pins.scopes.getFirst();
      var result = service.retainRoot(b).toCompletableFuture().join();
      assertEquals(gateway.rootIdentity(b), result.source());
      assertEquals(2, pins.scopes.size());
      assertNotEquals(historical, pins.active.get(plan.build().snapshotId()));
      assertTrue(pins.scopes.contains(historical));
      assertEquals(FileLookupException.Code.INVALID_PLAN, failure(service.retainRoot(a)).code());
      assertEquals(2, pins.scopes.size());
      assertEquals(2, gateway.acquisitions);
    }
  }

  @Test
  void cancelledFacadeCannotSwitchCandidateUntilItsWorkerActuallyRetires() throws Exception {
    var pins = new Pins();
    var catalog = new Catalog();
    var gateway = new Choices();
    var pinned = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var retired = new CountDownLatch(1);
    var target = new java.util.concurrent.atomic.AtomicReference<Thread>();
    pins.afterPin =
        () -> {
          if (target.compareAndSet(null, Thread.currentThread())) {
            pinned.countDown();
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
        };
    try (var workers = Executors.newFixedThreadPool(2)) {
      Executor jobs =
          task ->
              workers.execute(
                  () -> {
                    try {
                      task.run();
                    } finally {
                      if (Thread.currentThread() == target.get()) retired.countDown();
                    }
                  });
      try (var service =
          new DefaultFileLookupService(gateway, catalog, STORE, jobs, Runnable::run, pins)) {
        var plan =
            service.planRoot(build("one"), BUDGETS).toCompletableFuture().get(3, TimeUnit.SECONDS);
        var a = new RootSelectionPlan(plan, plan.candidates().getFirst());
        var b = new RootSelectionPlan(plan, plan.candidates().getLast());
        var pending = service.retainRoot(a).toCompletableFuture();
        try {
          assertTrue(pinned.await(3, TimeUnit.SECONDS));
          assertTrue(pending.cancel(true));
          var busy =
              assertThrows(
                  ExecutionException.class,
                  () -> service.retainRoot(b).toCompletableFuture().get(3, TimeUnit.SECONDS));
          assertEquals(
              FileLookupException.Code.CAPACITY_EXCEEDED,
              assertInstanceOf(FileLookupException.class, busy.getCause()).code());
          assertEquals(1, pins.scopes.size());
          assertEquals(0, gateway.acquisitions);
        } finally {
          release.countDown();
        }
        assertTrue(retired.await(3, TimeUnit.SECONDS));
        var result = service.retainRoot(b).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(gateway.rootIdentity(b), result.source());
        assertEquals(2, pins.scopes.size());
        assertEquals(1, gateway.acquisitions);
      }
    }
  }

  @Test
  void sameCandidateConsumersShareTransferWhileAlternativeIsBusy() throws Exception {
    var pins = new Pins();
    var catalog = new Catalog();
    var gateway = new Choices();
    var transfers = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
    try (var jobs = Executors.newFixedThreadPool(3);
        var service =
            new DefaultFileLookupService(gateway, catalog, STORE, jobs, transfers::add, pins)) {
      var plan =
          service.planRoot(build("one"), BUDGETS).toCompletableFuture().get(3, TimeUnit.SECONDS);
      var a = new RootSelectionPlan(plan, plan.candidates().getFirst());
      var b = new RootSelectionPlan(plan, plan.candidates().getLast());
      var first = service.retainRoot(a).toCompletableFuture();
      var transfer = transfers.poll(3, TimeUnit.SECONDS);
      assertNotNull(transfer);
      var second = service.retainRoot(a).toCompletableFuture();
      var busy =
          assertThrows(
              ExecutionException.class,
              () -> service.retainRoot(b).toCompletableFuture().get(3, TimeUnit.SECONDS));
      assertEquals(
          FileLookupException.Code.CAPACITY_EXCEEDED,
          assertInstanceOf(FileLookupException.class, busy.getCause()).code());
      transfer.run();
      assertEquals(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
      assertEquals(1, gateway.acquisitions);
      assertTrue(transfers.isEmpty());
    }
  }
}
