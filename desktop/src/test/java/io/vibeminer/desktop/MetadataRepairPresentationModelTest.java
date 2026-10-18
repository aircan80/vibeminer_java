package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MetadataRepairPresentationModelTest {
  @Test
  void rootCheckAndPositiveCostConsentAreSeparateAndNeverAutoOpen() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      assertEquals(0, f.rootChecks);
      m.download();
      assertEquals(0, f.executions);
      m.check();
      assertEquals(1, f.rootChecks);
      assertEquals(MetadataRepairPresentationModel.Stage.CHECKING, m.state().stage());
      var p = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(p);
      assertEquals(MetadataRepairPresentationModel.Stage.PLAN_READY, m.state().stage());
      assertEquals(90, m.state().plan().cost().encodedTransferBytes());
      assertEquals(0, f.executions);
      m.download();
      assertSame(p, f.chosen);
      assertEquals(MetadataRepairPresentationModel.Stage.REPAIRING, m.state().stage());
      f.executing.complete(MetadataRepairFixture.result(p, true));
      assertEquals(MetadataRepairPresentationModel.Stage.REPAIRED, m.state().stage());
      assertTrue(m.state().result().completedAudit().isPresent());
      assertEquals(1, f.executions);
    }
    assertEquals(0, f.closes);
  }

  @Test
  void installUsesItsExplicitOrdinaryPlanAndBudgets() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = model(f, Kind.INSTALL)) {
      m.selectContext(b, "enUS");
      var ordinary = MetadataRepairFixture.install(b);
      m.offerInstall(ordinary, MetadataRepairFixture.BUDGETS);
      m.check();
      assertSame(ordinary, f.install);
      assertSame(MetadataRepairFixture.BUDGETS, f.budgets);
      assertEquals(0, f.rootChecks);
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.INSTALL));
      assertEquals(MetadataRepairPresentationModel.Stage.PLAN_READY, m.state().stage());
    }
  }

  @Test
  void localeBuildSourceAndCloseDiscardConsentAndSuppressLateCallbacks() {
    var f = new MetadataRepairFixture.Fake();
    var queue = new ArrayDeque<Runnable>();
    var b = MetadataRepairFixture.build("one");
    var m =
        new MetadataRepairPresentationModel(
            f, Kind.ROOT, queue::add, s -> {}, r -> {}, new MetadataRepairFixture.MutableClock());
    m.selectContext(b, "enUS");
    m.offerRoot(MetadataRepairFixture.root(b));
    m.check();
    f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
    m.selectContext(b, "frFR");
    while (!queue.isEmpty()) queue.removeFirst().run();
    assertNull(m.state().plan());
    assertEquals("frFR", m.state().locale());
    assertEquals(0, f.executions);
    m.offerRoot(MetadataRepairFixture.root(b));
    f.checking = new CompletableFuture<>();
    m.check();
    m.selectContext(MetadataRepairFixture.build("two"), "enUS");
    assertTrue(f.checking.isCancelled());
    m.close();
    while (!queue.isEmpty()) queue.removeFirst().run();
    assertEquals(MetadataRepairPresentationModel.Stage.CLOSED, m.state().stage());
    assertEquals(0, f.closes);
  }

  @Test
  void cancellationInvalidatesOnlyOwnRequestAndNeverAssumesPublicationRetired() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
      m.download();
      m.cancel();
      assertTrue(f.executing.isCancelled());
      assertNull(m.state().plan());
      assertNull(m.state().result());
      assertEquals(MetadataRepairPresentationModel.Stage.CANCELLED, m.state().stage());
      assertEquals(0, f.closes);
      m.download();
      assertEquals(1, f.executions);
    }
  }

  @Test
  void expiryRequiresAnotherExplicitCheckWithoutExecutingOrRefreshing() {
    var f = new MetadataRepairFixture.Fake();
    var clock = new MetadataRepairFixture.MutableClock();
    var b = MetadataRepairFixture.build("one");
    try (var m =
        new MetadataRepairPresentationModel(f, Kind.ROOT, Runnable::run, s -> {}, r -> {}, clock)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      var p = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(p);
      clock.now = p.expiresAt();
      m.refreshExpiry();
      m.download();
      assertEquals(MetadataRepairPresentationModel.Stage.EXPIRED, m.state().stage());
      assertNull(m.state().plan());
      assertEquals(0, f.executions);
      assertEquals(1, f.rootChecks);
    }
  }

  @Test
  void typedMissingOrCorruptFailuresRevokeOnlyExactMetadataReadiness() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    var revoked = new ArrayList<MetadataRepairPresentationModel.ReadinessRevocation>();
    try (var m =
        new MetadataRepairPresentationModel(
            f,
            Kind.ROOT,
            Runnable::run,
            s -> {},
            revoked::add,
            new MetadataRepairFixture.MutableClock())) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      f.checking.completeExceptionally(
          new ContentClaimException(ContentClaimException.Code.MISSING_LOCAL, "private detail"));
      assertEquals(1, revoked.size());
      assertEquals(b.snapshotId(), revoked.getFirst().snapshotId());
      assertEquals(Kind.ROOT, revoked.getFirst().kind());
      assertEquals("enUS", revoked.getFirst().locale());
      assertEquals(ContentClaimException.Code.MISSING_LOCAL, m.state().claimCode());
      assertFalse(m.state().message().contains("private detail"));
      assertNull(m.state().plan());
    }
  }

  @Test
  void replanNeverClaimsHealthyOrStartsTheReuseBranch() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = model(f, Kind.INSTALL)) {
      m.selectContext(b, "enUS");
      m.offerInstall(MetadataRepairFixture.install(b), MetadataRepairFixture.BUDGETS);
      m.check();
      f.checking.completeExceptionally(
          new ContentClaimException(
              ContentClaimException.Code.REPLAN_REQUIRED, "healthy or B mismatch"));
      assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().result());
      assertNull(m.state().plan());
      assertFalse(m.state().message().contains("Already verified"));
      assertEquals(0, f.executions);
    }
  }

  @Test
  void headIntegrityAndUnknownErrorsDoNotInventMissingLocalComponents() {
    var head =
        new MetadataRepairException(MetadataRepairException.Code.INTEGRITY, "HEAD representation");
    for (RuntimeException failure :
        List.of(
            head,
            new IllegalStateException("MISSING_LOCAL, healthy zero GET: untrusted exception text"),
            new io.vibeminer.application.ObjectStoreException(
                io.vibeminer.application.ObjectStoreException.Code.MISSING_OBJECT,
                "unspecified component"),
            new io.vibeminer.application.catalog.CatalogException(
                io.vibeminer.application.catalog.CatalogException.Code.CORRUPT_METADATA,
                "unspecified component"),
            new io.vibeminer.application.lookup.FileLookupException(
                io.vibeminer.application.lookup.FileLookupException.Code.MISSING_LOCAL,
                "unspecified component"))) {
      var f = new MetadataRepairFixture.Fake();
      var revoked = new ArrayList<MetadataRepairPresentationModel.ReadinessRevocation>();
      var b = MetadataRepairFixture.build("one");
      try (var m =
          new MetadataRepairPresentationModel(
              f,
              Kind.ROOT,
              Runnable::run,
              s -> {},
              revoked::add,
              new MetadataRepairFixture.MutableClock())) {
        m.selectContext(b, "enUS");
        m.offerRoot(MetadataRepairFixture.root(b));
        m.check();
        f.checking.completeExceptionally(failure);
        assertTrue(revoked.isEmpty());
        assertNull(m.state().result());
        assertNull(m.state().plan());
        assertFalse(m.state().message().contains("Already verified"));
        assertFalse(m.state().message().contains("Current metadata readiness is cleared"));
        if (failure == head)
          assertEquals(MetadataRepairException.Code.INTEGRITY, m.state().repairCode());
        else assertEquals(ContentClaimException.Code.STORAGE_UNAVAILABLE, m.state().claimCode());
        assertEquals(0, f.executions);
      }
    }
  }

  @Test
  void changingSelectedRootSourceClearsConsentWithoutAnotherRequest() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
      var chosen = new io.vibeminer.application.lookup.RootCandidate("b".repeat(32), 91, 2);
      var plan =
          new io.vibeminer.application.lookup.RootPlan(
              "another-session",
              b,
              MetadataRepairFixture.config(b),
              MetadataRepairFixture.C,
              120,
              List.of(chosen),
              MetadataRepairFixture.BUDGETS);
      m.offerRoot(new io.vibeminer.application.lookup.RootSelectionPlan(plan, chosen));
      assertNull(m.state().plan());
      assertEquals(1, f.rootChecks);
      m.download();
      assertEquals(0, f.executions);
    }
  }

  @Test
  void headThenPayloadUnavailableRequiresFreshCheckAndNeverRetries() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
      m.download();
      f.executing.completeExceptionally(
          new MetadataRepairException(
              MetadataRepairException.Code.UNAVAILABLE, "HTTP404 exact A; never B"));
      assertEquals(MetadataRepairPresentationModel.Stage.UNAVAILABLE, m.state().stage());
      assertNull(m.state().result());
      assertNull(m.state().plan());
      m.download();
      assertEquals(1, f.executions);
      assertEquals(1, f.rootChecks);
    }
  }

  @Test
  void staleExecutionFailureCannotRevokeAnotherBuildOrLocale() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    var queue = new ArrayDeque<Runnable>();
    var revoked = new ArrayList<MetadataRepairPresentationModel.ReadinessRevocation>();
    try (var m =
        new MetadataRepairPresentationModel(
            f,
            Kind.ROOT,
            queue::add,
            s -> {},
            revoked::add,
            new MetadataRepairFixture.MutableClock())) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
      queue.removeFirst().run();
      m.download();
      f.executing.completeExceptionally(
          new ContentClaimException(
              ContentClaimException.Code.INTEGRITY_FAILURE, "old selected source"));
      m.selectContext(MetadataRepairFixture.build("two"), "frFR");
      while (!queue.isEmpty()) queue.removeFirst().run();
      assertEquals(1, revoked.size());
      assertEquals(
          MetadataRepairPresentationModel.Reason.REPAIR_REQUIRED, revoked.getFirst().reason());
      assertNull(revoked.getFirst().code());
      assertNull(m.state().result());
      assertNull(m.state().plan());
      assertEquals("frFR", m.state().locale());
    }
  }

  static MetadataRepairPresentationModel model(MetadataRepairFixture.Fake f, Kind kind) {
    return new MetadataRepairPresentationModel(
        f, kind, Runnable::run, s -> {}, r -> {}, new MetadataRepairFixture.MutableClock());
  }

  @Test
  void observerCancellationContextSwitchOrClosePreventsTheAnnouncedServiceCall() {
    for (boolean payload : new boolean[] {false, true}) {
      for (int action = 0; action < 3; action++) {
        var f = new MetadataRepairFixture.Fake();
        var b = MetadataRepairFixture.build("one");
        var reference =
            new java.util.concurrent.atomic.AtomicReference<MetadataRepairPresentationModel>();
        int chosenAction = action;
        var phase =
            payload
                ? MetadataRepairPresentationModel.Stage.REPAIRING
                : MetadataRepairPresentationModel.Stage.CHECKING;
        try (var m =
            new MetadataRepairPresentationModel(
                f,
                Kind.ROOT,
                Runnable::run,
                state -> {
                  if (state.stage() != phase) return;
                  var current = reference.get();
                  if (chosenAction == 0) current.cancel();
                  if (chosenAction == 1)
                    current.selectContext(MetadataRepairFixture.build("two"), "frFR");
                  if (chosenAction == 2) current.close();
                },
                r -> {},
                new MetadataRepairFixture.MutableClock())) {
          reference.set(m);
          m.selectContext(b, "enUS");
          m.offerRoot(MetadataRepairFixture.root(b));
          m.check();
          if (payload) {
            f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
            m.download();
          }
          assertEquals(payload ? 1 : 0, f.rootChecks);
          assertEquals(0, f.executions);
        }
      }
    }
  }

  @Test
  void serviceReentrancyCancelsAFutureReturnedAfterContextRetirement() {
    for (boolean payload : new boolean[] {false, true}) {
      for (int action = 0; action < 3; action++) {
        var reference =
            new java.util.concurrent.atomic.AtomicReference<MetadataRepairPresentationModel>();
        int chosenAction = action;
        Runnable retire =
            () -> {
              if (chosenAction == 0) reference.get().cancel();
              if (chosenAction == 1)
                reference.get().selectContext(MetadataRepairFixture.build("two"), "frFR");
              if (chosenAction == 2) reference.get().close();
            };
        var f =
            new MetadataRepairFixture.Fake() {
              @Override
              public CompletionStage<MetadataRepairPlan> prepareRoot(
                  io.vibeminer.application.lookup.RootSelectionPlan plan) {
                var future = super.prepareRoot(plan);
                if (!payload) retire.run();
                return future;
              }

              @Override
              public CompletionStage<MetadataRepairResult> execute(MetadataRepairPlan plan) {
                var future = super.execute(plan);
                retire.run();
                return future;
              }
            };
        var b = MetadataRepairFixture.build("one");
        try (var m = model(f, Kind.ROOT)) {
          reference.set(m);
          m.selectContext(b, "enUS");
          m.offerRoot(MetadataRepairFixture.root(b));
          m.check();
          if (payload) {
            f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
            m.download();
          }
          assertTrue((payload ? f.executing : f.checking).isCancelled());
          assertNull(m.state().plan());
          assertNull(m.state().result());
        }
      }
    }
  }

  @Test
  void readinessRevocationCallbackCannotBeOverwrittenAfterItRetiresTheContext() {
    for (int action = 0; action < 3; action++) {
      var reference =
          new java.util.concurrent.atomic.AtomicReference<MetadataRepairPresentationModel>();
      var f = new MetadataRepairFixture.Fake();
      var b = MetadataRepairFixture.build("one");
      int chosenAction = action;
      try (var m =
          new MetadataRepairPresentationModel(
              f,
              Kind.ROOT,
              Runnable::run,
              s -> {},
              event -> {
                if (chosenAction == 0) reference.get().cancel();
                if (chosenAction == 1)
                  reference.get().selectContext(MetadataRepairFixture.build("two"), "frFR");
                if (chosenAction == 2) reference.get().close();
              },
              new MetadataRepairFixture.MutableClock())) {
        reference.set(m);
        m.selectContext(b, "enUS");
        m.offerRoot(MetadataRepairFixture.root(b));
        m.check();
        f.checking.completeExceptionally(
            new ContentClaimException(
                ContentClaimException.Code.MISSING_LOCAL, "confirmed original source"));
        assertEquals(
            action == 0
                ? MetadataRepairPresentationModel.Stage.CANCELLED
                : action == 1
                    ? MetadataRepairPresentationModel.Stage.NEEDS_SOURCE
                    : MetadataRepairPresentationModel.Stage.CLOSED,
            m.state().stage());
        if (action == 1) {
          assertEquals(
              MetadataRepairFixture.build("two").snapshotId(), m.state().build().snapshotId());
          assertEquals("frFR", m.state().locale());
        }
      }
    }
  }

  @Test
  void malformedReturnedHeadPlanDoesNotDeclareLocalBytesCorrupt() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    var revoked = new ArrayList<MetadataRepairPresentationModel.ReadinessRevocation>();
    try (var m =
        new MetadataRepairPresentationModel(
            f,
            Kind.ROOT,
            Runnable::run,
            s -> {},
            revoked::add,
            new MetadataRepairFixture.MutableClock())) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      f.checking.complete(
          MetadataRepairFixture.plan(MetadataRepairFixture.build("another"), Kind.ROOT));
      assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().plan());
      assertNull(m.state().result());
      assertTrue(revoked.isEmpty());
    }
  }

  @Test
  void conclusivelyUnpublishedRepairDoesNotMeanZeroPayloadOrLocalCorruption() {
    var state =
        publicationFailure(
            MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
            new io.vibeminer.application.lookup.FileLookupException(
                io.vibeminer.application.lookup.FileLookupException.Code.INTEGRITY_FAILURE,
                "remote representation failed"));
    assertTrue(state.message().contains("before a replacement was promoted"));
    assertTrue(state.message().contains("Payload may have been requested or received"));
    assertFalse(state.message().contains("No payload download was needed"));
  }

  @Test
  void uncertainRepairPreservesBoundaryWithoutRevokingFromANestedIntegrityCause() {
    var state =
        publicationFailure(
            MetadataAcquisitionFailure.Publication.UNCERTAIN,
            new ContentClaimException(
                ContentClaimException.Code.INTEGRITY_FAILURE,
                "nested transfer verification, not confirmed local evidence"));
    assertTrue(state.message().contains("bytes may be present or restored"));
    assertTrue(state.message().contains("Verify retained bytes explicitly"));
    assertFalse(state.message().contains("readiness is cleared"));
  }

  @Test
  void cancellationWrappedInUncertainPublicationNeverClaimsRollback() {
    var state =
        publicationFailure(
            MetadataAcquisitionFailure.Publication.UNCERTAIN,
            new CancellationException("cancelled after possible publication"));
    assertEquals(MetadataRepairPresentationModel.Stage.FAILED, state.stage());
    assertTrue(state.message().contains("current readiness is unknown"));
    assertFalse(state.message().contains("No payload download was needed"));
    assertFalse(state.message().contains("rollback"));
  }

  private static MetadataRepairPresentationModel.State publicationFailure(
      MetadataAcquisitionFailure.Publication publication, RuntimeException cause) {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    var revoked = new ArrayList<MetadataRepairPresentationModel.ReadinessRevocation>();
    try (var m =
        new MetadataRepairPresentationModel(
            f,
            Kind.ROOT,
            Runnable::run,
            s -> {},
            revoked::add,
            new MetadataRepairFixture.MutableClock())) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
      m.download();
      f.executing.completeExceptionally(new MetadataAcquisitionFailure(publication, cause));
      assertEquals(1, revoked.size());
      assertEquals(
          MetadataRepairPresentationModel.Reason.REPAIR_REQUIRED, revoked.getFirst().reason());
      assertNull(revoked.getFirst().code());
      assertNull(m.state().plan());
      assertNull(m.state().result());
      assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
      assertEquals(publication, m.state().publication());
      assertNull(m.state().claimCode());
      m.download();
      assertEquals(1, f.executions);
      assertEquals(1, f.rootChecks);
      return m.state();
    }
  }
}
