package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class SelectedFileRetentionPresentationModelTest {
  static CandidateChoice choice() {
    var v = SelectedFilePresentationModelTest.variant("A", 1);
    var p = SelectedFilePresentationModelTest.plan(v, SelectedFileBudgets.defaults());
    return new CandidateChoice(p, p.candidates().getFirst());
  }

  static SelectedFilePlan plan(CandidateChoice c) {
    var cost = new AcquisitionCost(90, 90);
    var loc =
        new FileLocator(
            FileLocator.Kind.LOOSE,
            "https://fixture.example/file",
            c.candidate().encodingKey(),
            90,
            0,
            90,
            Optional.empty());
    return new SelectedFilePlan(
        c, Optional.of(new LocatedFilePlan("location", c, loc, 90, cost)), Optional.empty(), cost);
  }

  static class Fake extends SelectedFilePresentationModelTest.Fake {
    CompletableFuture<FileSelectionProbe> probe = new CompletableFuture<>();
    CompletableFuture<FileSelectionSearch> search = new CompletableFuture<>();
    CompletableFuture<SelectedFileAssociation> retaining = new CompletableFuture<>();
    CompletableFuture<SelectedFileAssociation> inspecting = new CompletableFuture<>();
    int probes, searches, retains, opens;

    public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice c) {
      probes++;
      return probe;
    }

    public CompletionStage<FileSelectionSearch> searchArchives(
        Loose404Permit p, ArchiveSearchBudgets b) {
      searches++;
      return search;
    }

    public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan p) {
      retains++;
      return retaining;
    }

    public CompletionStage<SelectedFileAssociation> inspectOffline(
        FileVariantAssociation v, SelectedFileBudgets b) {
      opens++;
      return inspecting;
    }

    public CompletionStage<SelectedFileAssociation> inspectOffline(
        SelectedFileDescriptor d, SelectedFileBudgets b) {
      opens++;
      return inspecting;
    }
  }

  static SelectedFileRetentionPresentationModel model(Fake f) {
    return new SelectedFileRetentionPresentationModel(f, Runnable::run, s -> {});
  }

  @Test
  void selectionAndCandidateChoiceAuthorizeNoIo() {
    var f = new Fake();
    var m = model(f);
    m.select(choice(), "enUS");
    m.searchArchives();
    m.retain();
    assertEquals(0, f.probes + f.searches + f.retains + f.opens);
    m.locate();
    assertEquals(1, f.probes);
    assertEquals(0, f.searches + f.retains);
  }

  @Test
  void onlyTyped404AllowsSeparateArchiveConsent() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    f.probe.complete(
        new FileSelectionProbe(Optional.empty(), Optional.of(new Loose404Permit("404", c))));
    assertEquals(0, f.searches);
    assertEquals(SelectedFileRetentionPresentationModel.Stage.LOOSE_MISSING, m.state().stage());
    m.searchArchives();
    assertEquals(1, f.searches);
    f.search.complete(new FileSelectionSearch(Optional.empty(), 1, 4, false));
    assertEquals(SelectedFileRetentionPresentationModel.Stage.SEARCH_INCOMPLETE, m.state().stage());
    assertNull(m.state().plan());
  }

  @Test
  void transportFailureNeverEnablesArchiveSearch() {
    var f = new Fake();
    var m = model(f);
    m.select(choice(), "enUS");
    m.locate();
    f.probe.completeExceptionally(
        new SelectedFileException(SelectedFileException.Code.UNAVAILABLE, "timeout"));
    m.searchArchives();
    assertEquals(0, f.searches);
    assertNull(m.state().missing());
  }

  @Test
  void payloadRequiresCurrentExplicitPlanAndCost() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    f.probe.complete(new FileSelectionProbe(Optional.of(plan(c)), Optional.empty()));
    assertEquals(90, m.state().plan().cost().encodedTransferBytes());
    assertEquals(0, f.retains);
    m.retain();
    assertEquals(1, f.retains);
    assertTrue(m.state().busy());
  }

  @Test
  void changingSelectionClearsConsentAndIgnoresLatePlan() {
    var f = new Fake();
    f.probe =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = model(f);
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    m.select(c, "deDE");
    f.probe.complete(new FileSelectionProbe(Optional.of(plan(c)), Optional.empty()));
    assertNull(m.state().plan());
    assertNull(m.state().missing());
    m.retain();
    assertEquals(0, f.retains);
  }

  @Test
  void forgedChoiceResponseFailsClosed() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    var different = new CandidateChoice(c.plan(), c.plan().candidates().get(1));
    f.probe.complete(new FileSelectionProbe(Optional.of(plan(different)), Optional.empty()));
    assertEquals(SelectedFileException.Code.INVALID_PLAN, m.state().code());
    assertNull(m.state().plan());
  }

  @Test
  void cancelDropsConsentAndDoesNotClaimWorkerRetirement() {
    var f = new Fake();
    var m = model(f);
    m.select(choice(), "enUS");
    m.locate();
    m.cancel();
    assertTrue(f.probe.isCancelled());
    assertEquals(SelectedFileRetentionPresentationModel.Stage.CANCELLED, m.state().stage());
    assertNull(m.state().plan());
  }

  @Test
  void canonicalStatesRemainDistinctAndNeverFallback() {
    for (var code :
        List.of(
            SelectedFileException.Code.BUSY,
            SelectedFileException.Code.REPLAN_REQUIRED,
            SelectedFileException.Code.RECOVERY_REQUIRED)) {
      var f = new Fake();
      var m = model(f);
      m.select(choice(), "enUS");
      m.locate();
      f.probe.completeExceptionally(new SelectedFileException(code, "internal"));
      assertEquals(code, m.state().code());
      assertNull(m.state().missing());
      assertEquals(0, f.searches + f.retains);
    }
  }

  static SelectedFileAssociation receipt(CandidateChoice c) {
    var p = c.plan();
    var key = c.candidate().encodingKey();
    String url = "https://original.example/encoded";
    var observed =
        new io.vibeminer.domain.selectedfile.EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            url,
            key,
            90,
            0,
            90,
            java.time.Instant.EPOCH,
            1,
            90,
            "e".repeat(64),
            Optional.empty());
    var alias =
        new io.vibeminer.domain.VerifiedSourceAlias(
            ContentRepresentation.sourceIdentity(key),
            new io.vibeminer.domain.StoredObject(
                new io.vibeminer.domain.ObjectDigest("f".repeat(64)), 90),
            new io.vibeminer.domain.SourceVerificationEvidence(
                ContentRepresentation.VERIFIER_SEMANTICS, url, java.time.Instant.EPOCH));
    var rep =
        new ContentRepresentation(
            p.variant().slot(), alias, EncodedScope.ZERO_HEADER_WHOLE_ENCODED, observed);
    return new SelectedFileAssociation(
        new SelectedFileDescriptor(p.variant(), key, ContentRepresentation.VERIFIER_SEMANTICS),
        p.configuration(),
        p.root(),
        p.proof(),
        rep);
  }

  @Test
  void reuseStillNeedsExplicitConsentAndPreservesOriginalReceipt() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    var expected = receipt(c);
    m.select(c, "enUS");
    m.locate();
    f.probe.complete(
        new FileSelectionProbe(
            Optional.of(
                new SelectedFilePlan(
                    c,
                    Optional.empty(),
                    Optional.of(expected.representation()),
                    new AcquisitionCost(0, 0))),
            Optional.empty()));
    assertEquals(0, f.retains);
    assertEquals(0, m.state().plan().cost().encodedTransferBytes());
    m.retain();
    f.retaining.complete(expected);
    assertSame(expected, m.state().receipt());
    assertEquals(
        "https://original.example/encoded",
        m.state().receipt().representation().sourceReceipt().actualResourceUrl());
  }

  @Test
  void failedOfflineVerificationClearsCurrentReadyClaim() {
    for (var code :
        List.of(
            SelectedFileException.Code.MISSING_LOCAL,
            SelectedFileException.Code.INTEGRITY_FAILURE)) {
      var f = new Fake();
      var m = model(f);
      var c = choice();
      m.select(c, "enUS");
      m.locate();
      f.probe.complete(new FileSelectionProbe(Optional.of(plan(c)), Optional.empty()));
      m.retain();
      f.retaining.complete(receipt(c));
      m.reopen();
      assertNull(m.state().receipt());
      assertEquals(1, f.opens);
      f.inspecting.completeExceptionally(new SelectedFileException(code, "disk"));
      assertNull(m.state().receipt());
      assertEquals(code, m.state().code());
      assertEquals(1, f.probes);
    }
  }

  @Test
  void closedModelIgnoresQueuedUiCompletionAndNeverClosesBorrowedService() {
    var f = new Fake();
    Queue<Runnable> ui = new ArrayDeque<>();
    var m = new SelectedFileRetentionPresentationModel(f, ui::add, s -> {});
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    f.probe.complete(new FileSelectionProbe(Optional.of(plan(c)), Optional.empty()));
    m.close();
    ui.remove().run();
    assertNull(m.state().plan());
  }

  @Test
  void mismatchedKnownLengthCannotBecomeCostConsent() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    var cost = new AcquisitionCost(91, 91);
    var loc =
        new FileLocator(
            FileLocator.Kind.LOOSE,
            "https://fixture.example/file",
            c.candidate().encodingKey(),
            91,
            0,
            91,
            Optional.empty());
    var bad =
        new SelectedFilePlan(
            c, Optional.of(new LocatedFilePlan("bad", c, loc, 91, cost)), Optional.empty(), cost);
    f.probe.complete(new FileSelectionProbe(Optional.of(bad), Optional.empty()));
    assertEquals(SelectedFileException.Code.INVALID_PLAN, m.state().code());
    assertNull(m.state().plan());
  }

  @Test
  void restartOfflineUsesOnlyExactVariantWithoutCandidateSession() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    m.selectVariant(c.plan().variant(), "deDE");
    assertEquals(0, f.probes + f.calls + f.opens);
    m.reopen();
    assertEquals(1, f.opens);
    assertEquals(0, f.probes + f.calls + f.retains);
    f.inspecting.complete(receipt(c));
    assertEquals(SelectedFileRetentionPresentationModel.Stage.OFFLINE_VERIFIED, m.state().stage());
    assertEquals(c.plan().variant(), m.state().receipt().descriptor().association());
    assertNull(m.state().choice());
  }

  @Test
  void offlineMembershipMissingIsNotMissingPhysicalBytes() {
    var f = new Fake();
    var m = model(f);
    m.selectVariant(choice().plan().variant(), "enUS");
    m.reopen();
    f.inspecting.completeExceptionally(
        new SelectedFileException(SelectedFileException.Code.NOT_FOUND, "catalog"));
    assertEquals(SelectedFileException.Code.NOT_FOUND, m.state().code());
    assertTrue(m.state().message().contains("membership"));
    assertNull(m.state().receipt());
  }

  @Test
  void retainedReceiptMustMatchConsentedAssociationEvidence() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    f.probe.complete(new FileSelectionProbe(Optional.of(plan(c)), Optional.empty()));
    m.retain();
    var r = receipt(c);
    f.retaining.complete(
        new SelectedFileAssociation(
            r.descriptor(),
            SelectedFilePresentationModelTest.alias("different-config"),
            r.root(),
            r.proof(),
            r.representation()));
    assertEquals(SelectedFileException.Code.INVALID_PLAN, m.state().code());
    assertNull(m.state().receipt());
  }

  @Test
  void reuseCannotSubstituteUnselectedCanonicalEncoding() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    m.select(c, "enUS");
    m.locate();
    var other = new CandidateChoice(c.plan(), c.plan().candidates().get(1));
    var alternate = receipt(other).representation();
    f.probe.complete(
        new FileSelectionProbe(
            Optional.of(
                new SelectedFilePlan(
                    c, Optional.empty(), Optional.of(alternate), new AcquisitionCost(0, 0))),
            Optional.empty()));
    assertEquals(SelectedFileException.Code.INVALID_PLAN, m.state().code());
    assertNull(m.state().plan());
  }

  @Test
  void offlineRestartResponseCannotRepaintAnotherRawVariant() {
    var f = new Fake();
    f.inspecting =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = model(f);
    var c = choice();
    m.selectVariant(c.plan().variant(), "enUS");
    m.reopen();
    m.selectVariant(SelectedFilePresentationModelTest.variant("B", 2), "enUS");
    f.inspecting.complete(receipt(c));
    assertNull(m.state().receipt());
    assertEquals("B", m.variant().build().versionLabel());
  }

  @Test
  void reuseReceiptCannotBeRewrittenUnderSameEncodingAndLength() {
    var f = new Fake();
    var m = model(f);
    var c = choice();
    var expected = receipt(c);
    m.select(c, "enUS");
    m.locate();
    f.probe.complete(
        new FileSelectionProbe(
            Optional.of(
                new SelectedFilePlan(
                    c,
                    Optional.empty(),
                    Optional.of(expected.representation()),
                    new AcquisitionCost(0, 0))),
            Optional.empty()));
    m.retain();
    var old = expected.representation().sourceReceipt();
    var changed =
        new EncodedSourceReceipt(
            old.kind(),
            old.actualResourceUrl(),
            old.resourceKey(),
            old.parentWholeBytes(),
            old.childOffset(),
            old.childEncodedBytes(),
            old.observedAt(),
            old.payloadRequestCount() + 1,
            old.payloadResponseBytes() + 1,
            "a".repeat(64),
            old.archiveMapping());
    var replacement =
        new ContentRepresentation(
            expected.representation().slot(),
            expected.representation().encoded(),
            expected.representation().scope(),
            changed);
    f.retaining.complete(
        new SelectedFileAssociation(
            expected.descriptor(),
            expected.configuration(),
            expected.root(),
            expected.proof(),
            replacement));
    assertEquals(SelectedFileException.Code.INVALID_PLAN, m.state().code());
    assertNull(m.state().receipt());
  }

  @Test
  void offlineBeforeEncodingFailureRestoresAnExistingRetryAction() {
    for (var code :
        List.of(
            SelectedFileException.Code.NOT_FOUND,
            SelectedFileException.Code.MISSING_LOCAL,
            SelectedFileException.Code.INTEGRITY_FAILURE)) {
      var f = new Fake();
      var m = model(f);
      m.selectVariant(choice().plan().variant(), "enUS");
      m.reopen();
      assertEquals("selected-retention-cancel", FileLookupPane.retentionFocusTarget(m.state()));
      f.inspecting.completeExceptionally(new SelectedFileException(code, "local"));
      assertNull(m.state().choice());
      assertEquals("selected-retention-offline", FileLookupPane.retentionFocusTarget(m.state()));
    }
  }
}
