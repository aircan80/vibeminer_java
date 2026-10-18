package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.ContentClaimException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MetadataReuseEvidenceRevocationTest {
  @Test
  void installRevocationPreservesDeclaredCostAndHistoricalReceiptWithoutRepair() {
    var f = new InstallPresentationModelTest.Fake();
    var m = new InstallPresentationModel(f, Runnable::run, s -> {});
    var b = InstallPresentationModelTest.build("A");
    m.selectBuild(b);
    m.check();
    var declared = InstallPresentationModelTest.plan(b);
    f.checking.complete(declared);
    m.reopen();
    var old = InstallPresentationModelTest.inspection(b);
    f.reopening.complete(old);
    var before = m.state().plan();
    m.invalidateRetainedEvidence(ContentClaimException.Code.INTEGRITY_FAILURE);
    assertSame(before, m.state().plan());
    assertNull(m.state().inspection());
    assertSame(old, m.historicalInspection().orElseThrow());
    assertEquals(0, f.downloads);
    assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, m.state().claimCode());
  }

  @Test
  void rootRevocationPreservesDeclaredPlanButDetachesOldReadiness() {
    var f = new FileLookupPresentationModelTest.Fake();
    var m = FileLookupPresentationModelTest.model(f);
    m.setInput("123");
    m.check();
    var declared = FileLookupPresentationModelTest.plan(FileLookupPresentationModelTest.build("A"));
    f.planning.complete(declared);
    m.selectCandidate(declared.candidates().getFirst());
    m.lookupOffline();
    var result =
        FileLookupPresentationModelTest.result(FileLookupPresentationModelTest.build("A"), 123);
    f.looking.complete(result);
    m.invalidateRetainedEvidence(ContentClaimException.Code.MISSING_LOCAL);
    assertSame(declared, m.state().plan());
    assertEquals(declared.candidates().getFirst(), m.state().candidate());
    assertNull(m.state().result());
    assertSame(result.root(), m.historicalRoot().orElseThrow());
    assertEquals(0, f.retains);
  }

  @Test
  void busyAndExpiryAreNotSourceFailureAndOldRepliesCannotRestoreReadiness() {
    var f = new FileLookupPresentationModelTest.Fake();
    f.looking =
        new CompletableFuture<>() {
          public boolean cancel(boolean x) {
            return false;
          }
        };
    var m = FileLookupPresentationModelTest.model(f);
    m.setInput("123");
    m.lookupOffline();
    m.invalidateRetainedEvidence(ContentClaimException.Code.BUSY);
    assertTrue(m.state().busy());
    m.invalidateRetainedEvidence(ContentClaimException.Code.MISSING_LOCAL);
    f.looking.complete(
        FileLookupPresentationModelTest.result(FileLookupPresentationModelTest.build("A"), 123));
    assertNull(m.state().result());
    assertEquals(ContentClaimException.Code.MISSING_LOCAL, m.state().claimCode());
  }
}
