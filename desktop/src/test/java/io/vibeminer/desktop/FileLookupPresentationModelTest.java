package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FileLookupPresentationModelTest {
  static BuildSnapshot build(String version) {
    return InstallPresentationModelTest.build(version);
  }

  static VerifiedSourceAlias proof(String source) {
    return new VerifiedSourceAlias(
        new ResolvedSourceIdentity("fixture", source, "fixture-verifier"),
        new StoredObject(new ObjectDigest("e".repeat(64)), 90),
        new SourceVerificationEvidence(
            "fixture-verifier", "https://original.example/" + source, Instant.EPOCH));
  }

  static RootPlan plan(BuildSnapshot b) {
    return new RootPlan(
        "session",
        b,
        proof("configuration"),
        "c".repeat(32),
        120,
        List.of(new RootCandidate("d".repeat(32), 90, 0), new RootCandidate("f".repeat(32), -1, 1)),
        LookupBudgets.defaults());
  }

  static RootLookupResult result(BuildSnapshot b, long id) {
    return new RootLookupResult(
        b,
        proof("root"),
        Set.of(id),
        List.of(
            new RootVariant(id, "1".repeat(32), 2, 3, 4, 5, 6, Optional.empty()),
            new RootVariant(id, "2".repeat(32), 8, 9, 10, 11, 12, Optional.of("raw-name-hash"))),
        500);
  }

  static final class Fake implements FileLookupService {
    int plans, retains, lookups;
    RootSelectionPlan selected;
    Set<Long> ids;
    LookupBudgets budgets;
    CompletableFuture<RootPlan> planning = new CompletableFuture<>();
    CompletableFuture<VerifiedSourceAlias> retaining = new CompletableFuture<>();
    CompletableFuture<RootLookupResult> looking = new CompletableFuture<>();

    public CompletionStage<RootPlan> planRoot(BuildSnapshot b, LookupBudgets budgets) {
      plans++;
      this.budgets = budgets;
      return planning;
    }

    public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan p) {
      retains++;
      selected = p;
      return retaining;
    }

    public CompletionStage<RootLookupResult> lookupOffline(
        BuildSnapshot b, Set<Long> ids, LookupBudgets budgets) {
      lookups++;
      this.ids = ids;
      return looking;
    }

    public void close() {
      throw new AssertionError("View must not close shared service");
    }
  }

  static FileLookupPresentationModel model(Fake f) {
    var m = new FileLookupPresentationModel(f, Runnable::run, s -> {});
    m.selectContext(build("A"), "enUS");
    return m;
  }

  @Test
  void selectionAndTypingNeverAcquire() {
    var f = new Fake();
    var m = model(f);
    m.setInput("123");
    assertEquals(0, f.plans + f.retains + f.lookups);
  }

  @Test
  void invalidInputCannotCallPort() {
    for (String input : List.of("", "-1", "1.5", "1 2", "4294967296", "999999999999999999999")) {
      var f = new Fake();
      var m = model(f);
      m.setInput(input);
      m.check();
      m.lookupOffline();
      assertEquals(0, f.plans + f.lookups);
      assertEquals(FileLookupPresentationModel.Stage.INVALID, m.state().stage());
    }
  }

  @Test
  void unsignedBoundaryAcceptedAndCheckHasEightMiBCeiling() {
    var f = new Fake();
    var m = model(f);
    m.setInput("4294967295");
    m.check();
    assertEquals(1, f.plans);
    assertEquals(8L << 20, f.budgets.maximumMetadataWireBytes());
    assertEquals(0, f.retains + f.lookups);
  }

  @Test
  void planShowsAllCandidatesAndNeverSelectsArbitrarily() {
    var f = new Fake();
    var m = model(f);
    m.setInput("123");
    m.check();
    f.planning.complete(plan(build("A")));
    assertEquals(2, m.state().plan().candidates().size());
    assertNull(m.state().candidate());
    m.download();
    assertEquals(0, f.retains);
    m.selectCandidate(m.state().plan().candidates().get(1));
    m.download();
    assertEquals(0, f.retains);
  }

  @Test
  void selectedKnownCandidateRetentionThenOfflineLookup() {
    var f = new Fake();
    var m = model(f);
    m.setInput("123");
    m.check();
    var p = plan(build("A"));
    f.planning.complete(p);
    m.selectCandidate(p.candidates().getFirst());
    m.download();
    assertEquals(new RootSelectionPlan(p, p.candidates().getFirst()), f.selected);
    assertEquals(0, f.lookups);
    f.retaining.complete(proof("root"));
    assertEquals(1, f.lookups);
    assertEquals(Set.of(123L), f.ids);
    f.looking.complete(result(build("A"), 123));
    assertEquals(2, m.state().result().variants().size());
    assertEquals("https://original.example/root", m.state().result().root().evidence().sourceUrl());
    assertEquals(FileLookupPresentationModel.Stage.READY, m.state().stage());
  }

  @Test
  void offlineLookupNeverPlansOrRetains() {
    var f = new Fake();
    var m = model(f);
    m.setInput("0");
    m.lookupOffline();
    assertEquals(Set.of(0L), f.ids);
    assertEquals(0, f.plans + f.retains);
  }

  @Test
  void completeAbsenceIsScopedToReturnedRequestedID() {
    var f = new Fake();
    var m = model(f);
    m.setInput("123");
    m.lookupOffline();
    f.looking.complete(
        new RootLookupResult(build("A"), proof("root"), Set.of(123L), List.of(), 500));
    assertTrue(m.state().result().absentIds().contains(123L));
    assertTrue(m.state().message().contains("verified root"));
  }

  @Test
  void changedInputInvalidatesPlanAndCancelsLateResponse() {
    var f = new Fake();
    f.planning =
        new CompletableFuture<>() {
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    var m = model(f);
    m.setInput("123");
    m.check();
    m.setInput("124");
    f.planning.complete(plan(build("A")));
    assertNull(m.state().plan());
    assertEquals("124", m.state().input());
    m.download();
    assertEquals(0, f.retains);
  }

  @Test
  void localeAndSourceSwitchRetirePendingRequest() {
    for (boolean locale : List.of(true, false)) {
      var f = new Fake();
      var m = model(f);
      m.setInput("123");
      m.check();
      m.selectContext(build(locale ? "A" : "B"), locale ? "deDE" : "enUS");
      assertTrue(f.planning.isCancelled());
      assertNull(m.state().plan());
      assertNull(m.state().result());
    }
  }

  @Test
  void wrongSourceOrIDCannotBecomeReady() {
    for (boolean wrongId : List.of(true, false)) {
      var f = new Fake();
      var m = model(f);
      m.setInput("123");
      m.lookupOffline();
      f.looking.complete(result(build(wrongId ? "A" : "B"), wrongId ? 124 : 123));
      assertNull(m.state().result());
      assertEquals(FileLookupException.Code.INTEGRITY_FAILURE, m.state().code());
    }
  }

  @Test
  void missingOrCorruptRecheckClearsPriorReadyProof() {
    for (var code :
        List.of(
            FileLookupException.Code.MISSING_LOCAL, FileLookupException.Code.INTEGRITY_FAILURE)) {
      var f = new Fake();
      var m = model(f);
      m.setInput("123");
      m.lookupOffline();
      f.looking.complete(result(build("A"), 123));
      f.looking = new CompletableFuture<>();
      m.lookupOffline();
      f.looking.completeExceptionally(
          new CompletionException(new FileLookupException(code, "private path")));
      assertNull(m.state().result());
      assertEquals(code, m.state().code());
      assertFalse(m.state().message().contains("private path"));
      assertEquals(0, f.plans + f.retains);
    }
  }

  @Test
  void cancelStopsThisRequestAndLateResultCannotPaint() {
    var f = new Fake();
    f.looking =
        new CompletableFuture<>() {
          public boolean cancel(boolean interrupt) {
            return false;
          }
        };
    var m = model(f);
    m.setInput("123");
    m.lookupOffline();
    m.cancel();
    f.looking.complete(result(build("A"), 123));
    assertEquals(FileLookupPresentationModel.Stage.CANCELLED, m.state().stage());
    assertNull(m.state().result());
  }

  @Test
  void wrongPlanOrExpandedBudgetIsRejected() {
    for (boolean budget : List.of(true, false)) {
      var f = new Fake();
      var m = model(f);
      m.setInput("123");
      m.check();
      var p = plan(build(budget ? "A" : "B"));
      var d = p.budgets();
      var expanded =
          new LookupBudgets(
              d.maximumConfigurationBytes(),
              d.maximumMetadataWireBytes() * 2,
              d.maximumMetadataRequests(),
              d.maximumMetadataDecodedBytes(),
              d.maximumIndexBytes(),
              d.maximumPageBytes(),
              d.maximumCandidates(),
              d.maximumRootEncodedBytes(),
              d.maximumRootDecodedBytes(),
              d.maximumHeaderBytes(),
              d.maximumChunks(),
              d.maximumEncodedChunkBytes(),
              d.maximumDecodedChunkBytes(),
              d.maximumDecodedWorkBytes(),
              d.maximumSelectedIds(),
              d.maximumMatches(),
              d.maximumRootRecords());
      f.planning.complete(
          budget
              ? new RootPlan(
                  p.sessionId(),
                  p.build(),
                  p.configuration(),
                  p.contentKey(),
                  p.decodedBytes(),
                  p.candidates(),
                  expanded)
              : p);
      assertNull(m.state().plan());
      assertEquals(FileLookupException.Code.INTEGRITY_FAILURE, m.state().code());
    }
  }

  @Test
  void capacityIsBusyNotDiskAndClosedViewSuppressesCallbacks() {
    var f = new Fake();
    var m = model(f);
    m.setInput("123");
    m.check();
    f.planning.completeExceptionally(
        new FileLookupException(FileLookupException.Code.CAPACITY_EXCEEDED, "disk?"));
    assertTrue(m.state().message().contains("busy"));
    m.close();
    assertDoesNotThrow(m::close);
  }
}
