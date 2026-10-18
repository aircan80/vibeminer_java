package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MetadataRepairRequiredTest {
  @Test
  void acceptedPositivePlanRevokesScopedReadinessWithoutInventingPhysicalCause() {
    var f = new MetadataRepairFixture.Fake();
    var events = new ArrayList<MetadataRepairPresentationModel.ReadinessRevocation>();
    var b = MetadataRepairFixture.build("one");
    try (var m =
        new MetadataRepairPresentationModel(
            f,
            Kind.ROOT,
            Runnable::run,
            s -> {},
            events::add,
            new MetadataRepairFixture.MutableClock())) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      assertTrue(events.isEmpty());
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
      assertEquals(1, events.size());
      assertEquals(
          MetadataRepairPresentationModel.Reason.REPAIR_REQUIRED, events.getFirst().reason());
      assertNull(events.getFirst().code());
      assertEquals(b.snapshotId(), events.getFirst().snapshotId());
      assertEquals("enUS", events.getFirst().locale());
      assertEquals(Kind.ROOT, events.getFirst().kind());
      assertEquals(MetadataRepairPresentationModel.Stage.PLAN_READY, m.state().stage());
      assertEquals(0, f.executions);
    }
  }

  @Test
  void reentrantRequiredCallbackCannotPublishConsentOverNewContextOrClosedState() {
    for (boolean close : List.of(false, true)) {
      var f = new MetadataRepairFixture.Fake();
      var b = MetadataRepairFixture.build("one");
      var ref = new AtomicReference<MetadataRepairPresentationModel>();
      try (var m =
          new MetadataRepairPresentationModel(
              f,
              Kind.ROOT,
              Runnable::run,
              s -> {},
              event -> {
                if (close) ref.get().close();
                else ref.get().selectContext(MetadataRepairFixture.build("two"), "deDE");
              },
              new MetadataRepairFixture.MutableClock())) {
        ref.set(m);
        m.selectContext(b, "enUS");
        m.offerRoot(MetadataRepairFixture.root(b));
        m.check();
        f.checking.complete(MetadataRepairFixture.plan(b, Kind.ROOT));
        assertEquals(
            close
                ? MetadataRepairPresentationModel.Stage.CLOSED
                : MetadataRepairPresentationModel.Stage.NEEDS_SOURCE,
            m.state().stage());
        assertNull(m.state().plan());
      }
    }
  }

  @Test
  void positiveRepairInvalidationClearsReadableInstallButPreservesHistoryWithoutCause() {
    var f = new InstallPresentationModelTest.Fake();
    var b = InstallPresentationModelTest.build("A");
    try (var m = new InstallPresentationModel(f, Runnable::run, s -> {})) {
      m.selectBuild(b);
      m.reopen();
      var original = InstallPresentationModelTest.inspection(b);
      f.reopening.complete(original);
      assertNotNull(m.state().inspection());
      m.invalidateForRepair();
      assertNull(m.state().inspection());
      assertEquals(original, m.historicalInspection().orElseThrow());
      assertNull(m.state().claimCode());
      assertNull(m.state().reason());
      assertTrue(m.state().message().contains("needs verification or repair"));
      assertEquals(0, f.downloads);
    }
  }

  @Test
  void positiveRepairInvalidationClearsReadableRootButPreservesHistoricalReceiptWithoutCause() {
    var f = new FileLookupPresentationModelTest.Fake();
    var b = FileLookupPresentationModelTest.build("A");
    try (var m = new FileLookupPresentationModel(f, Runnable::run, s -> {})) {
      m.selectContext(b, "enUS");
      m.setInput("123");
      m.lookupOffline();
      var original = FileLookupPresentationModelTest.result(b, 123);
      f.looking.complete(original);
      assertNotNull(m.state().result());
      m.invalidateForRepair();
      assertNull(m.state().result());
      assertEquals(original.root(), m.historicalRoot().orElseThrow());
      assertNull(m.state().claimCode());
      assertNull(m.state().code());
      assertTrue(m.state().message().contains("needs verification or repair"));
      assertEquals(0, f.retains);
    }
  }
}
