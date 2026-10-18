package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataRepairResultPresentationTest {
  @Test
  void successfulLocalRaceHasNoInventedFreshTransfer() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = MetadataRepairPresentationModelTest.model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      var p = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(p);
      m.download();
      f.executing.complete(MetadataRepairFixture.result(p, false));
      assertEquals(MetadataRepairPresentationModel.Stage.VERIFIED_LOCAL, m.state().stage());
      assertTrue(m.state().result().completedAudit().isEmpty());
      assertTrue(m.state().message().contains("No payload download"));
      assertEquals(p.originalProof(), m.state().result().association().actualRepresentation());
    }
  }

  @Test
  void freshAuditOriginalHistoryAndHeadRemainThreeSeparateFacts() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = MetadataRepairPresentationModelTest.model(f, Kind.INSTALL)) {
      m.selectContext(b, "enUS");
      m.offerInstall(MetadataRepairFixture.install(b), MetadataRepairFixture.BUDGETS);
      m.check();
      var p = MetadataRepairFixture.plan(b, Kind.INSTALL);
      f.checking.complete(p);
      m.download();
      var r = MetadataRepairFixture.result(p, true);
      f.executing.complete(r);
      var fresh =
          m.state()
              .result()
              .completedAudit()
              .orElseThrow()
              .events()
              .getLast()
              .observation()
              .orElseThrow();
      assertEquals(p, m.state().plan());
      assertEquals(
          p.sourceObservation().resourceUrl(), fresh.actualTransfer().evidence().sourceUrl());
      assertNotEquals(
          p.originalProof().originalAlias().evidence(), fresh.actualTransfer().evidence());
      assertEquals(
          p.originalProof().originalAlias(),
          r.association().actualRepresentation().originalAlias());
      assertEquals(MetadataRepairPresentationModel.Stage.REPAIRED, m.state().stage());
    }
  }

  @Test
  void wrongBuildKindEncodingOrHeadConsentNeverEnablesDownload() {
    for (int mode = 0; mode < 3; mode++) {
      var f = new MetadataRepairFixture.Fake();
      var b = MetadataRepairFixture.build("one");
      try (var m = MetadataRepairPresentationModelTest.model(f, Kind.ROOT)) {
        m.selectContext(b, "enUS");
        m.offerRoot(MetadataRepairFixture.root(b));
        m.check();
        var p =
            MetadataRepairFixture.plan(
                mode == 0 ? MetadataRepairFixture.build("other") : b,
                mode == 1 ? Kind.INSTALL : Kind.ROOT);
        if (mode == 2)
          p =
              new MetadataRepairPlan(
                  p.issuanceId(),
                  p.reservationToken(),
                  p.request(),
                  p.originalProof(),
                  p.sourceObservation(),
                  new MetadataRepairCost(
                      90,
                      90,
                      MetadataRepairFixture.BUDGETS.maximumChunks() + 3,
                      240,
                      java.time.Duration.ofSeconds(60)),
                  p.issuedAt(),
                  p.expiresAt());
        f.checking.complete(p);
        m.download();
        assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
        assertNull(m.state().plan());
        assertEquals(0, f.executions);
      }
    }
  }

  @Test
  void anotherIssuanceOrAnotherOriginalReceiptCannotBecomeSuccess() {
    for (boolean audit : new boolean[] {false, true}) {
      var f = new MetadataRepairFixture.Fake();
      var b = MetadataRepairFixture.build("one");
      try (var m = MetadataRepairPresentationModelTest.model(f, Kind.ROOT)) {
        m.selectContext(b, "enUS");
        m.offerRoot(MetadataRepairFixture.root(b));
        m.check();
        var p = MetadataRepairFixture.plan(b, Kind.ROOT);
        f.checking.complete(p);
        m.download();
        var other = MetadataRepairFixture.plan(MetadataRepairFixture.build("other"), Kind.ROOT);
        f.executing.complete(MetadataRepairFixture.result(other, audit));
        assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
        assertNull(m.state().result());
        assertNull(m.state().plan());
      }
    }
  }

  @Test
  void changedOriginalReceiptOnAnEmptyAuditResultIsRejected() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = MetadataRepairPresentationModelTest.model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      var p = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(p);
      m.download();
      var old = p.originalProof();
      var alias = old.originalAlias();
      var changed =
          new io.vibeminer.domain.VerifiedSourceAlias(
              alias.source(),
              alias.object(),
              new io.vibeminer.domain.SourceVerificationEvidence(
                  alias.evidence().verifierSemantics(),
                  "https://substitute.example/data/aa/aa/" + old.actualEncodingKey(),
                  alias.evidence().verifiedAt()));
      var proof =
          new io.vibeminer.domain.content.MetadataContentProof(
              changed,
              old.actualEncodingKey(),
              old.declaredContentKey(),
              old.declaredDecodedBytes(),
              old.decodedAttestation(),
              old.structuralAttestation());
      var correct = MetadataRepairFixture.association(p);
      var bad =
          new MetadataBuildAssociation(
              correct.declaration(),
              correct.canonicalReservationToken(),
              proof,
              correct.associatedAt());
      f.executing.complete(new MetadataRepairResult(bad, Optional.empty()));
      assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().result());
      assertEquals(MetadataRepairException.Code.INTEGRITY, m.state().repairCode());
    }
  }

  @Test
  void completedAuditFromAnotherIssuanceWithSameSourceIsNotThisConsent() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = MetadataRepairPresentationModelTest.model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      var p = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(p);
      m.download();
      var other =
          new MetadataRepairPlan(
              new UUID(0, 999),
              p.reservationToken(),
              p.request(),
              p.originalProof(),
              p.sourceObservation(),
              p.cost(),
              p.issuedAt(),
              p.expiresAt());
      f.executing.complete(MetadataRepairFixture.result(other, true));
      assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().result());
    }
  }

  @Test
  void existingVerificationCapabilityCannotBeReplacedByAHealthyRaceResult() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = MetadataRepairPresentationModelTest.model(f, Kind.ROOT)) {
      m.selectContext(b, "enUS");
      m.offerRoot(MetadataRepairFixture.root(b));
      m.check();
      var p = MetadataRepairFixture.plan(b, Kind.ROOT);
      f.checking.complete(p);
      m.download();
      var old = p.originalProof();
      var changed =
          new io.vibeminer.domain.content.MetadataContentProof(
              old.originalAlias(),
              old.actualEncodingKey(),
              old.declaredContentKey(),
              old.declaredDecodedBytes(),
              Optional.of(
                  new io.vibeminer.domain.content.DecodedContentAttestation(
                      old.contentKey(), 120, "substituted-verifier")),
              old.structuralAttestation());
      var original = MetadataRepairFixture.association(p);
      var bad =
          new MetadataBuildAssociation(
              original.declaration(),
              original.canonicalReservationToken(),
              changed,
              original.associatedAt());
      f.executing.complete(new MetadataRepairResult(bad, Optional.empty()));
      assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().result());
      assertEquals(MetadataRepairException.Code.INTEGRITY, m.state().repairCode());
    }
  }

  @Test
  void installResultCannotDropItsKnownOrdinaryDeclarationFacts() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = MetadataRepairPresentationModelTest.model(f, Kind.INSTALL)) {
      m.selectContext(b, "enUS");
      m.offerInstall(MetadataRepairFixture.install(b), MetadataRepairFixture.BUDGETS);
      m.check();
      var p = MetadataRepairFixture.plan(b, Kind.INSTALL);
      f.checking.complete(p);
      m.download();
      var ordinary = MetadataRepairFixture.association(p);
      var declaration =
          new MetadataDeclaration(
              b,
              p.request().configuration(),
              Kind.INSTALL,
              p.request().slot(),
              Optional.empty(),
              OptionalLong.empty(),
              OptionalLong.empty());
      var omitted =
          new MetadataBuildAssociation(
              declaration,
              ordinary.canonicalReservationToken(),
              ordinary.actualRepresentation(),
              ordinary.associatedAt());
      f.executing.complete(new MetadataRepairResult(omitted, Optional.empty()));
      assertEquals(MetadataRepairPresentationModel.Stage.FAILED, m.state().stage());
      assertNull(m.state().result());
    }
  }

  @Test
  void installDeclaredAlternativeCannotConsentToOriginalAWithAnOrdinaryBPlan() {
    var f = new MetadataRepairFixture.Fake();
    var b = MetadataRepairFixture.build("one");
    try (var m = MetadataRepairPresentationModelTest.model(f, Kind.INSTALL)) {
      m.selectContext(b, "enUS");
      var ordinary = MetadataRepairFixture.install(b);
      var alternative =
          new io.vibeminer.application.bootstrap.InstallPlan(
              b,
              ordinary.configuration(),
              ordinary.contentKey(),
              "b".repeat(32),
              ordinary.decodedBytes(),
              ordinary.encodedBytes() + 1);
      m.offerInstall(alternative, MetadataRepairFixture.BUDGETS);
      m.check();
      f.checking.complete(MetadataRepairFixture.plan(b, Kind.INSTALL));
      m.download();
      assertNull(m.state().plan());
      assertNull(m.state().result());
      assertEquals(0, f.executions);
    }
  }
}
