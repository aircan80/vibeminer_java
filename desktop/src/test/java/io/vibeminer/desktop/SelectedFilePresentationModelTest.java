package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SelectedFilePresentationModelTest {
  static BuildSnapshot build(String name) {
    return new BuildSnapshot(
        "wow", "Retail", "Live", "us", name, "a".repeat(32), "b".repeat(32), Instant.EPOCH, true);
  }

  static VerifiedSourceAlias alias(String id) {
    return new VerifiedSourceAlias(
        new ResolvedSourceIdentity("fixture", id, "fixture-verifier"),
        new StoredObject(new ObjectDigest("e".repeat(64)), 90),
        new SourceVerificationEvidence(
            "fixture-verifier", "https://original.example/" + id, Instant.EPOCH));
  }

  static FileVariantAssociation variant(String version, long id) {
    return new FileVariantAssociation(
        build(version),
        alias("root").source(),
        id,
        "c".repeat(32),
        2,
        3,
        4,
        5,
        6,
        Optional.empty());
  }

  static CandidatePlan plan(FileVariantAssociation v, SelectedFileBudgets b) {
    return new CandidatePlan(
        "session",
        v,
        alias("config"),
        alias("root"),
        new FileAssociationProof(
            alias("root").source(), alias("encoding").source(), "fixture-v1", "e".repeat(64)),
        List.of(
            new EncodedCandidate("1".repeat(32), OptionalLong.of(90), OptionalLong.of(0)),
            new EncodedCandidate("2".repeat(32), OptionalLong.empty(), OptionalLong.of(1))),
        b);
  }

  static class Fake implements SelectedFileService {
    CompletableFuture<CandidatePlan> pending = new CompletableFuture<>();
    int calls;
    BuildSnapshot build;
    FileVariantAssociation variant;
    SelectedFileBudgets budgets;

    public CompletionStage<CandidatePlan> planCandidates(
        BuildSnapshot b, FileVariantAssociation v, SelectedFileBudgets l) {
      calls++;
      build = b;
      variant = v;
      budgets = l;
      return pending;
    }

    public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice c) {
      throw new AssertionError("No locator action in candidate slice");
    }

    public CompletionStage<FileSelectionSearch> searchArchives(
        Loose404Permit p, ArchiveSearchBudgets b) {
      throw new AssertionError("No archive search");
    }

    public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan p) {
      throw new AssertionError("No payload acquisition");
    }

    public CompletionStage<SelectedFileAssociation> inspectOffline(
        SelectedFileDescriptor d, SelectedFileBudgets b) {
      throw new AssertionError("No offline acquisition action");
    }

    public void close() {
      throw new AssertionError("Shared service owned by application");
    }
  }

  static SelectedFilePresentationModel model(Fake f) {
    return new SelectedFilePresentationModel(f, Runnable::run, s -> {});
  }

  @Test
  void explicitCheckOnlyNoAutomaticIoOrCandidateChoice() {
    var f = new Fake();
    var m = model(f);
    m.check();
    assertEquals(0, f.calls);
    var v = variant("A", 12);
    m.select(v, "enUS");
    assertEquals(0, f.calls);
    m.check();
    assertEquals(v.build(), f.build);
    assertEquals(v, f.variant);
    assertTrue(m.state().busy());
    f.pending.complete(plan(v, f.budgets));
    assertEquals(2, m.state().plan().candidates().size());
    assertTrue(m.state().plan().candidates().get(1).encodedBytes().isEmpty());
    assertNull(m.state().candidate());
    assertEquals(SelectedFilePresentationModel.Stage.CANDIDATES, m.state().stage());
  }

  @Test
  void selectionMustBeOneOfActualReturnedCandidates() {
    var f = new Fake();
    var m = model(f);
    var v = variant("A", 1);
    m.select(v, "enUS");
    m.check();
    f.pending.complete(plan(v, f.budgets));
    m.selectCandidate(
        new EncodedCandidate("3".repeat(32), OptionalLong.of(1), OptionalLong.empty()));
    assertNull(m.state().candidate());
    var actual = m.state().plan().candidates().get(1);
    m.selectCandidate(actual);
    assertEquals(actual, m.state().candidate());
    assertEquals(1, f.calls);
  }

  @Test
  void changedVariantRetiresConsumerAndIgnoresLateResult() {
    var f = new Fake();
    f.pending =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = model(f);
    var a = variant("A", 1);
    m.select(a, "enUS");
    m.check();
    m.select(variant("A", 2), "enUS");
    f.pending.complete(plan(a, f.budgets));
    assertNull(m.state().plan());
    assertEquals(2, m.state().variant().fileDataId());
  }

  @Test
  void changedLocaleClearsConsentAndSameContextDoesNot() {
    var f = new Fake();
    var m = model(f);
    var v = variant("A", 1);
    m.select(v, "enUS");
    m.check();
    f.pending.complete(plan(v, f.budgets));
    m.selectCandidate(m.state().plan().candidates().getFirst());
    m.select(v, "enUS");
    assertNotNull(m.state().candidate());
    m.select(v, "deDE");
    assertNull(m.state().candidate());
    assertNull(m.state().plan());
  }

  @Test
  void wrongExactVariantResponseFailsClosed() {
    var f = new Fake();
    var m = model(f);
    m.select(variant("A", 1), "enUS");
    m.check();
    f.pending.complete(plan(variant("B", 1), f.budgets));
    assertEquals(SelectedFileException.Code.INVALID_PLAN, m.state().code());
    assertNull(m.state().plan());
  }

  @Test
  void cancelDetachesAndDuplicateCheckIsIgnored() {
    var f = new Fake();
    var m = model(f);
    m.select(variant("A", 1), "enUS");
    m.check();
    m.check();
    assertEquals(1, f.calls);
    m.cancel();
    assertTrue(f.pending.isCancelled());
    assertEquals(SelectedFilePresentationModel.Stage.CANCELLED, m.state().stage());
    assertNull(m.state().plan());
  }

  @Test
  void failuresKeepTypedMeaningAndClearCandidateProof() {
    for (var code :
        List.of(
            SelectedFileException.Code.BUSY,
            SelectedFileException.Code.RECOVERY_REQUIRED,
            SelectedFileException.Code.UNSUPPORTED,
            SelectedFileException.Code.INTEGRITY_FAILURE)) {
      var f = new Fake();
      var m = model(f);
      m.select(variant("A", 1), "enUS");
      m.check();
      f.pending.completeExceptionally(new SelectedFileException(code, "unsafe diagnostic"));
      assertEquals(code, m.state().code());
      assertFalse(m.state().message().contains("unsafe diagnostic"));
      assertNull(m.state().plan());
    }
  }

  @Test
  void completionUsesUiExecutorAndCloseSuppressesQueuedResult() {
    var f = new Fake();
    Queue<Runnable> ui = new ArrayDeque<>();
    var m = new SelectedFilePresentationModel(f, ui::add, s -> {});
    var v = variant("A", 1);
    m.select(v, "enUS");
    m.check();
    f.pending.complete(plan(v, f.budgets));
    assertTrue(m.state().busy());
    m.close();
    ui.remove().run();
    assertNull(m.state().plan());
  }

  @Test
  void clearingSelectionDropsAllScope() {
    var f = new Fake();
    var m = model(f);
    m.select(variant("A", 1), "enUS");
    m.check();
    m.select(null, "enUS");
    assertTrue(f.pending.isCancelled());
    assertEquals(SelectedFilePresentationModel.Stage.NO_SELECTION, m.state().stage());
    assertNull(m.state().variant());
  }

  @Test
  void observerContextSwitchBeforeDispatchDoesNotStartObsoleteRequest() {
    var f = new Fake();
    var holder = new SelectedFilePresentationModel[1];
    holder[0] =
        new SelectedFilePresentationModel(
            f,
            Runnable::run,
            s -> {
              if (s.busy()) holder[0].select(variant("B", 2), "enUS");
            });
    holder[0].select(variant("A", 1), "enUS");
    holder[0].check();
    assertEquals(0, f.calls);
    assertEquals("B", holder[0].state().variant().build().versionLabel());
  }

  @Test
  void typedCancellationAndCapacityRemainDistinct() {
    var f = new Fake();
    var m = model(f);
    m.select(variant("A", 1), "enUS");
    m.check();
    f.pending.completeExceptionally(
        new SelectedFileException(SelectedFileException.Code.CANCELLED, "worker"));
    assertEquals(SelectedFilePresentationModel.Stage.CANCELLED, m.state().stage());
    var g = new Fake();
    var n = model(g);
    n.select(variant("A", 1), "enUS");
    n.check();
    g.pending.completeExceptionally(
        new SelectedFileException(SelectedFileException.Code.CAPACITY_EXCEEDED, "worker"));
    assertTrue(n.state().message().contains("busy"));
  }

  @Test
  void mismatchedBudgetPlanFailsClosed() {
    var f = new Fake();
    var m = model(f);
    var v = variant("A", 1);
    m.select(v, "enUS");
    m.check();
    var b = f.budgets;
    var changed =
        new SelectedFileBudgets(
            b.lookup(),
            b.maximumEncodedFileBytes() + 1,
            b.maximumPayloadRangeBytes(),
            b.maximumPayloadRequests(),
            b.maximumPayloadWireBytes(),
            b.maximumPayloadDuration());
    f.pending.complete(plan(v, changed));
    assertEquals(SelectedFileException.Code.INVALID_PLAN, m.state().code());
    assertNull(m.state().plan());
  }

  @Test
  void wrappedTypedFailuresPreservePublicMeaning() {
    for (var code :
        List.of(
            SelectedFileException.Code.CANCELLED, SelectedFileException.Code.CAPACITY_EXCEEDED)) {
      var f = new Fake();
      var m = model(f);
      m.select(variant("A", 1), "enUS");
      m.check();
      f.pending.completeExceptionally(
          new CompletionException(new SelectedFileException(code, "diagnostic")));
      assertEquals(code, m.state().code());
      assertFalse(m.state().message().contains("diagnostic"));
    }
  }
}
