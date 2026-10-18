package io.vibeminer.desktop;

import static io.vibeminer.desktop.ItemNamesPresentationModelTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.itemexport.*;
import io.vibeminer.desktop.itemexport.ItemEvidenceExportPresentationModel;
import io.vibeminer.domain.item.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ItemEvidenceExportPresentationModelTest {
  @Test
  void captureSourceFailuresRevokeOnlyActualSelectedScope() {
    for (var code :
        List.of(
            ItemEvidenceException.Code.MISSING_LOCAL,
            ItemEvidenceException.Code.CORRUPT,
            ItemEvidenceException.Code.STALE_SELECTION)) {
      for (boolean paired : List.of(false, true)) {
        var f = new Fake();
        var revoked = new ArrayList<ItemEvidenceSelection>();
        var m =
            new ItemEvidenceExportPresentationModel(f, Runnable::run, state -> {}, revoked::add);
        m.open(PAIR.item(), 101, Optional.of(PAIR));
        m.includeNames(paired);
        m.verifyEvidence();
        f.capturing.completeExceptionally(
            new CompletionException(new ItemEvidenceException(code, "private diagnostic")));
        assertEquals(List.of(f.requested), revoked);
        assertEquals(paired, revoked.getFirst().names().isPresent());
        assertNull(m.state().snapshot());
      }
    }
  }

  @Test
  void destinationFailureNeverRevokesResearchEvidence() {
    var f = new Fake();
    var revoked = new ArrayList<ItemEvidenceSelection>();
    var m = new ItemEvidenceExportPresentationModel(f, Runnable::run, state -> {}, revoked::add);
    m.open(PAIR.item(), 101, Optional.empty());
    saving(m, f);
    f.operation.terminal.completeExceptionally(
        new ItemEvidenceException(ItemEvidenceException.Code.DESTINATION_REFUSED, "private"));
    assertTrue(revoked.isEmpty());
    assertNotNull(m.state().snapshot());
  }

  @Test
  void reentrantScopeRevocationCannotPublishOldFailureOverNewContext() {
    var f = new Fake();
    var ref = new AtomicReference<ItemEvidenceExportPresentationModel>();
    var m =
        new ItemEvidenceExportPresentationModel(
            f,
            Runnable::run,
            state -> {},
            selection -> ref.get().open(PAIR.item(), 102, Optional.empty()));
    ref.set(m);
    m.open(PAIR.item(), 101, Optional.empty());
    m.verifyEvidence();
    f.capturing.completeExceptionally(
        new ItemEvidenceException(ItemEvidenceException.Code.CORRUPT, "private"));
    assertEquals(102, m.state().selection().recordId());
    assertEquals(ItemEvidenceExportPresentationModel.Stage.REVIEW, m.state().stage());
    assertNull(m.state().code());
  }

  static final String PATH =
      Path.of(System.getProperty("java.io.tmpdir"), "item-evidence.json")
          .toAbsolutePath()
          .normalize()
          .toString();
  static final String OTHER =
      Path.of(System.getProperty("java.io.tmpdir"), "other-evidence.json")
          .toAbsolutePath()
          .normalize()
          .toString();
  static final ItemEvidenceReceipt RECEIPT =
      new ItemEvidenceReceipt(1200, "a".repeat(64), Instant.EPOCH.plusSeconds(20));

  static class Operation implements ItemEvidenceService.SaveOperation {
    int cancellationRequests;
    final CompletableFuture<ItemEvidenceReceipt> terminal = new CompletableFuture<>();

    public CompletionStage<ItemEvidenceReceipt> completion() {
      return terminal.minimalCompletionStage();
    }

    public void requestCancellation() {
      cancellationRequests++;
    }
  }

  static class Fake implements ItemEvidenceService {
    int captures, saves;
    ItemEvidenceSelection requested;
    ItemEvidenceSnapshot saved;
    String path;
    CompletableFuture<ItemEvidenceSnapshot> capturing = new CompletableFuture<>();
    Operation operation = new Operation();

    public CompletionStage<ItemEvidenceSnapshot> capture(ItemEvidenceSelection selection) {
      captures++;
      requested = selection;
      return capturing;
    }

    public SaveOperation save(ItemEvidenceSnapshot snapshot, String path) {
      saves++;
      saved = snapshot;
      this.path = path;
      return operation;
    }

    public void close() {
      throw new AssertionError("Borrowed service owner must not be closed");
    }
  }

  static ItemEvidenceExportPresentationModel model(Fake f) {
    var m = new ItemEvidenceExportPresentationModel(f, Runnable::run, s -> {});
    m.open(PAIR.item(), 101, Optional.of(PAIR));
    return m;
  }

  static ItemEvidenceSnapshot snapshot(
      ItemEvidenceSelection s, ItemNameMatch.Outcome name, String value) {
    var r = row(s.recordId(), 0, name, value);
    return new ItemEvidenceSnapshot(
        s,
        r.item(),
        s.names().isPresent() ? Optional.of(r.name()) : Optional.empty(),
        Instant.EPOCH.plusSeconds(10),
        Instant.EPOCH.plusSeconds(12),
        "b".repeat(64));
  }

  static void verified(ItemEvidenceExportPresentationModel m, Fake f) {
    m.verifyEvidence();
    f.capturing.complete(snapshot(f.requested, ItemNameMatch.Outcome.PHYSICAL_NAME, "雪"));
  }

  static void saving(ItemEvidenceExportPresentationModel m, Fake f) {
    verified(m, f);
    assertTrue(m.chooseDestination(PATH));
    m.saveWithReplacementAuthorization(PATH);
  }

  @Test
  void openingScopeAndDestinationCannotStartHiddenWork() {
    var f = new Fake();
    var m = model(f);
    assertTrue(m.state().selection().names().isEmpty());
    assertNull(m.state().snapshot());
    assertTrue(m.includeNames(true));
    assertEquals(PAIR, m.state().selection().names().orElseThrow());
    assertFalse(m.chooseDestination(PATH));
    m.saveWithReplacementAuthorization(PATH);
    assertEquals(0, f.captures + f.saves);
  }

  @Test
  void captureIsExplicitAndKeepsOriginalProofSeparateFromNewInterval() {
    var f = new Fake();
    var m = model(f);
    verified(m, f);
    var actual = m.state().snapshot();
    assertEquals(101, actual.row().recordId());
    assertTrue(actual.name().isEmpty());
    assertSame(PAIR.item(), actual.selection().item());
    assertEquals(
        Instant.EPOCH, actual.selection().item().evidence().localVerification().verifiedAt());
    assertEquals(Instant.EPOCH.plusSeconds(10), actual.verificationStartedAt());
    assertEquals(0, f.saves);
  }

  @Test
  void allPairedNameStatesRemainExplicitIncludingEmptyPhysicalCandidate() {
    for (var outcome : ItemNameMatch.Outcome.values()) {
      var f = new Fake();
      var m = model(f);
      m.includeNames(true);
      m.verifyEvidence();
      f.capturing.complete(snapshot(f.requested, outcome, ""));
      assertEquals(outcome, m.state().snapshot().name().orElseThrow().outcome());
      assertEquals(PAIR, m.state().snapshot().selection().names().orElseThrow());
      if (outcome == ItemNameMatch.Outcome.PHYSICAL_NAME)
        assertEquals(
            "", m.state().snapshot().name().orElseThrow().candidate().orElseThrow().value());
    }
  }

  @Test
  void scopeChangeAndEveryCaptureAttemptInvalidateOldPreview() {
    var f = new Fake();
    var m = model(f);
    verified(m, f);
    m.chooseDestination(PATH);
    m.includeNames(true);
    assertNull(m.state().snapshot());
    assertFalse(m.canSave());
    assertEquals(1, f.captures);
    f.capturing = new CompletableFuture<>();
    m.verifyEvidence();
    f.capturing.completeExceptionally(
        new ItemEvidenceException(ItemEvidenceException.Code.BUSY, "private"));
    assertNull(m.state().snapshot());
    assertEquals(0, f.saves);
  }

  @Test
  void substitutedSelectionCannotBecomeAnAuthorizedPreview() {
    var f = new Fake();
    var m = model(f);
    m.verifyEvidence();
    var wrong = new ItemEvidenceSelection(PAIR.item(), 102, Optional.empty());
    f.capturing.complete(snapshot(wrong, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, ""));
    assertEquals(ItemEvidenceException.Code.CORRUPT, m.state().code());
    assertNull(m.state().snapshot());
    assertFalse(m.canSave());
  }

  @Test
  void captureContextChangeSuppressesUncancellableLateCompletion() {
    var f = new Fake();
    f.capturing =
        new CompletableFuture<>() {
          public boolean cancel(boolean b) {
            return false;
          }
        };
    var m = model(f);
    m.verifyEvidence();
    var request = f.requested;
    m.invalidateContext();
    f.capturing.complete(snapshot(request, ItemNameMatch.Outcome.PHYSICAL_NAME, "A"));
    assertNull(m.state().snapshot());
    assertFalse(m.state().contextActive());
    assertEquals(0, f.saves);
  }

  @Test
  void reentrantCaptureCannotBindAConsumerAfterContextRetires() {
    var ref = new AtomicReference<ItemEvidenceExportPresentationModel>();
    var f =
        new Fake() {
          @Override
          public CompletionStage<ItemEvidenceSnapshot> capture(ItemEvidenceSelection s) {
            var result = super.capture(s);
            ref.get().invalidateContext();
            return result;
          }
        };
    var m = model(f);
    ref.set(m);
    m.verifyEvidence();
    assertTrue(f.capturing.isCancelled());
    assertNull(m.state().snapshot());
  }

  @Test
  void destinationTextIsPreservedAndExactAuthorizationDoesNotRecapture() {
    var f = new Fake();
    var m = model(f);
    verified(m, f);
    var original = m.state().snapshot();
    assertFalse(m.chooseDestination(null));
    assertFalse(m.chooseDestination(""));
    assertTrue(m.chooseDestination(PATH));
    assertTrue(m.chooseDestination(OTHER));
    assertSame(original, m.state().snapshot());
    assertEquals(1, f.captures);
    m.saveWithReplacementAuthorization(PATH);
    assertEquals(0, f.saves);
    m.saveWithReplacementAuthorization(OTHER);
    assertEquals(1, f.saves);
    assertEquals(OTHER, f.path);
    assertSame(original, f.saved);
  }

  @Test
  void explicitCommitIsSingleAndConfirmedSuccessWinsCancel() {
    var f = new Fake();
    var m = model(f);
    saving(m, f);
    m.saveWithReplacementAuthorization(PATH);
    assertEquals(1, f.saves);
    m.cancel();
    m.cancel();
    assertEquals(1, f.operation.cancellationRequests);
    assertFalse(f.operation.terminal.isCancelled());
    f.operation.terminal.complete(RECEIPT);
    assertEquals(ItemEvidenceExportPresentationModel.Stage.SAVED, m.state().stage());
    assertSame(RECEIPT, m.state().receipt());
    assertTrue(m.state().cancellationRequested());
  }

  @Test
  void cancelledBeforePublicationAndUncertainOutcomeStayDifferent() {
    for (boolean uncertain : List.of(false, true)) {
      var f = new Fake();
      var m = model(f);
      saving(m, f);
      m.cancel();
      f.operation.terminal.completeExceptionally(
          uncertain
              ? new ItemEvidenceException(ItemEvidenceException.Code.COMMIT_UNCERTAIN, "private")
              : new CancellationException());
      assertEquals(
          uncertain
              ? ItemEvidenceExportPresentationModel.Stage.COMMIT_UNCERTAIN
              : ItemEvidenceExportPresentationModel.Stage.CANCELLED,
          m.state().stage());
      assertNull(m.state().receipt());
      assertFalse(m.canSave());
      assertFalse(m.state().message().contains("private"));
    }
  }

  @Test
  void contextChangeOrCloseDuringSavePreservesOriginalTerminalReceiptWithoutActiveInspector() {
    for (boolean close : List.of(false, true)) {
      var f = new Fake();
      var seen = new ArrayList<ItemEvidenceExportPresentationModel.State>();
      var m = new ItemEvidenceExportPresentationModel(f, Runnable::run, seen::add);
      m.open(PAIR.item(), 101, Optional.of(PAIR));
      saving(m, f);
      if (close) m.close();
      else m.open(PAIR.item(), 102, Optional.of(PAIR));
      assertFalse(m.state().contextActive());
      assertEquals(101, m.state().selection().recordId());
      assertEquals(1, f.operation.cancellationRequests);
      f.operation.terminal.complete(RECEIPT);
      assertSame(RECEIPT, m.state().receipt());
      assertFalse(m.state().contextActive());
      assertEquals(101, seen.getLast().selection().recordId());
      assertEquals(close, m.state().closed());
    }
  }

  @Test
  void reentrantSaveCancellationBeforeOperationBindingStillObservesCommit() {
    var ref = new AtomicReference<ItemEvidenceExportPresentationModel>();
    var f =
        new Fake() {
          @Override
          public SaveOperation save(ItemEvidenceSnapshot s, String path) {
            var result = super.save(s, path);
            ref.get().close();
            return result;
          }
        };
    var m = model(f);
    ref.set(m);
    saving(m, f);
    assertEquals(1, f.operation.cancellationRequests);
    assertFalse(f.operation.terminal.isCancelled());
    f.operation.terminal.complete(RECEIPT);
    assertSame(RECEIPT, m.state().receipt());
    assertFalse(m.state().contextActive());
  }

  @Test
  void destinationFailureKeepsPreviewButSourceFailureRevokesIt() {
    for (var code :
        List.of(
            ItemEvidenceException.Code.DESTINATION_REFUSED,
            ItemEvidenceException.Code.ATOMIC_PUBLICATION_UNSUPPORTED,
            ItemEvidenceException.Code.STALE_SELECTION,
            ItemEvidenceException.Code.MISSING_LOCAL,
            ItemEvidenceException.Code.CORRUPT)) {
      var f = new Fake();
      var m = model(f);
      saving(m, f);
      var original = m.state().snapshot();
      f.operation.terminal.completeExceptionally(
          new ItemEvidenceException(code, "private path and detail"));
      if (code == ItemEvidenceException.Code.DESTINATION_REFUSED
          || code == ItemEvidenceException.Code.ATOMIC_PUBLICATION_UNSUPPORTED) {
        assertSame(original, m.state().snapshot());
        assertTrue(m.chooseDestination(OTHER));
      } else assertNull(m.state().snapshot());
      assertNull(m.state().receipt());
      assertFalse(m.state().message().contains("private path"));
    }
  }

  @Test
  void observerRetirementBeforeSaveActionDoesNotStartPublication() {
    var ref = new AtomicReference<ItemEvidenceExportPresentationModel>();
    var f = new Fake();
    var m =
        new ItemEvidenceExportPresentationModel(
            f,
            Runnable::run,
            s -> {
              if (s.stage() == ItemEvidenceExportPresentationModel.Stage.SAVING) ref.get().cancel();
            });
    ref.set(m);
    m.open(PAIR.item(), 101, Optional.of(PAIR));
    saving(m, f);
    assertEquals(0, f.saves);
    assertEquals(ItemEvidenceExportPresentationModel.Stage.CANCELLED, m.state().stage());
  }

  @Test
  void lateCancelCloseOrContextChangeCannotRelabelConfirmedOrUncertainPublication() {
    for (boolean uncertain : List.of(false, true)) {
      for (String action : List.of("cancel", "close", "context")) {
        var f = new Fake();
        var m = model(f);
        saving(m, f);
        if (uncertain)
          f.operation.terminal.completeExceptionally(
              new ItemEvidenceException(ItemEvidenceException.Code.COMMIT_UNCERTAIN, "private"));
        else f.operation.terminal.complete(RECEIPT);
        var actual = m.state().stage();
        switch (action) {
          case "cancel" -> m.cancel();
          case "close" -> m.close();
          default -> m.invalidateContext();
        }
        assertEquals(actual, m.state().stage());
        assertEquals(PATH, m.state().destination());
        if (!uncertain) assertSame(RECEIPT, m.state().receipt());
      }
    }
  }

  @Test
  void applicationOwnsPlatformPathValidationWithoutUiParsingOrNormalization() {
    var f =
        new Fake() {
          @Override
          public SaveOperation save(ItemEvidenceSnapshot snapshot, String path) {
            var result = super.save(snapshot, path);
            operation.terminal.completeExceptionally(
                new ItemEvidenceException(
                    ItemEvidenceException.Code.INVALID_REQUEST, "platform lexical rejection"));
            return result;
          }
        };
    var m = model(f);
    verified(m, f);
    assertTrue(m.chooseDestination("relative/../input.json"));
    assertEquals(0, f.saves);
    m.saveWithReplacementAuthorization("relative/../input.json");
    assertEquals("relative/../input.json", f.path);
    assertEquals(ItemEvidenceException.Code.INVALID_REQUEST, m.state().code());
    assertNull(m.state().receipt());
  }
}
