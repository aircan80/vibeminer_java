package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.ContentClaimException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MetadataClaimPresentationTest {
  @Test
  void installTypedClaimsRemainDistinctAndClearCostAndReadableClaims() {
    for (var code : ContentClaimException.Code.values()) {
      var f = new InstallPresentationModelTest.Fake();
      var m = new InstallPresentationModel(f, Runnable::run, s -> {});
      var b = InstallPresentationModelTest.build("A");
      m.selectBuild(b);
      m.check();
      f.checking.complete(InstallPresentationModelTest.plan(b));
      m.download();
      f.downloading.complete(InstallPresentationModelTest.inspection(b));
      assertNotNull(m.state().inspection());
      m.reopen();
      f.reopening.completeExceptionally(
          new CompletionException(new ContentClaimException(code, "private diagnostic")));
      assertEquals(code, m.state().claimCode());
      assertEquals(InstallPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().inspection());
      assertNull(m.state().plan());
      assertFalse(m.state().message().contains("private diagnostic"));
      assertEquals(MetadataClaimMessages.message(code), m.state().message());
      assertEquals(1, f.downloads);
    }
  }

  @Test
  void lookupTypedClaimsNeverBecomeAbsenceOrGenericStorage() {
    for (var code : ContentClaimException.Code.values()) {
      var f = new FileLookupPresentationModelTest.Fake();
      var m = FileLookupPresentationModelTest.model(f);
      m.setInput("123");
      m.check();
      f.planning.complete(
          FileLookupPresentationModelTest.plan(FileLookupPresentationModelTest.build("A")));
      m.lookupOffline();
      f.looking.completeExceptionally(
          new ExecutionException(new ContentClaimException(code, "private diagnostic")));
      assertEquals(code, m.state().claimCode());
      assertEquals(FileLookupPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().plan());
      assertNull(m.state().candidate());
      assertNull(m.state().result());
      assertEquals(MetadataClaimMessages.message(code), m.state().message());
      assertEquals(0, f.retains);
    }
  }

  @Test
  void messagesSeparateRecoveryBusyLegacyAndWeakReuseWithoutDestructiveAdvice() {
    var codes = ContentClaimException.Code.values();
    assertEquals(
        codes.length,
        java.util.Arrays.stream(codes).map(MetadataClaimMessages::message).distinct().count());
    assertTrue(MetadataClaimMessages.message(ContentClaimException.Code.BUSY).contains("busy"));
    assertTrue(
        MetadataClaimMessages.message(ContentClaimException.Code.LEGACY_CONFLICT)
            .contains("historical"));
    assertTrue(
        MetadataClaimMessages.message(ContentClaimException.Code.RECOVERY_REQUIRED)
            .contains("recovery"));
    assertTrue(
        MetadataClaimMessages.message(ContentClaimException.Code.UNSUPPORTED_REUSE)
            .contains("verification"));
    for (var c : codes) {
      String text = MetadataClaimMessages.message(c);
      assertFalse(text.contains("delete the"));
      assertFalse(text.contains("not found"));
    }
  }

  @Test
  void oldContextClaimFailuresCannotPaintIntoAnotherBuild() {
    var f = new InstallPresentationModelTest.Fake();
    f.reopening =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = new InstallPresentationModel(f, Runnable::run, s -> {});
    m.selectBuild(InstallPresentationModelTest.build("A"));
    m.reopen();
    m.selectBuild(InstallPresentationModelTest.build("B"));
    f.reopening.completeExceptionally(
        new ContentClaimException(ContentClaimException.Code.LEGACY_CONFLICT, "old"));
    assertNull(m.state().claimCode());
    assertEquals(InstallPresentationModel.Stage.UNCHECKED, m.state().stage());
    var g = new FileLookupPresentationModelTest.Fake();
    g.looking =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var n = FileLookupPresentationModelTest.model(g);
    n.setInput("123");
    n.lookupOffline();
    n.selectContext(FileLookupPresentationModelTest.build("B"), "enUS");
    g.looking.completeExceptionally(
        new ContentClaimException(ContentClaimException.Code.BUSY, "old"));
    assertNull(n.state().claimCode());
    assertEquals(FileLookupPresentationModel.Stage.UNCHECKED, n.state().stage());
  }

  @Test
  void conflictRecoveryAndWeakReuseHeadingsNeverMasqueradeAsAbsence() {
    assertTrue(
        MetadataClaimMessages.heading(ContentClaimException.Code.LEGACY_CONFLICT)
            .contains("conflict"));
    assertTrue(
        MetadataClaimMessages.heading(ContentClaimException.Code.RECOVERY_REQUIRED)
            .contains("recovery"));
    assertTrue(
        MetadataClaimMessages.heading(ContentClaimException.Code.UNSUPPORTED_REUSE)
            .contains("cannot be reused"));
    assertTrue(MetadataClaimMessages.heading(ContentClaimException.Code.BUSY).contains("busy"));
    for (var code : ContentClaimException.Code.values()) {
      assertFalse(MetadataClaimMessages.heading(code).contains("Install list unavailable"));
      assertFalse(MetadataClaimMessages.heading(code).contains("not found"));
    }
  }
}
