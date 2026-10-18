package io.vibeminer.application.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataRepairResultTest {
  final MetadataRepairPlan plan = new MetadataRepairContractsTest().plan();
  final UUID attempt = UUID.randomUUID();
  final Instant time = MetadataRepairContractsTest.NOW;

  MetadataRepairObservation observation(boolean grammar) {
    var original = plan.originalProof();
    var fresh =
        new VerifiedSourceAlias(
            original.originalAlias().source(),
            original.object(),
            new SourceVerificationEvidence(
                original.originalAlias().evidence().verifierSemantics(),
                plan.sourceObservation().resourceUrl(),
                time.plusSeconds(1)));
    return new MetadataRepairObservation(
        attempt,
        original,
        fresh,
        new DecodedContentAttestation(original.contentKey(), 100, "fresh-decoded"),
        grammar
            ? Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "fresh-grammar"))
            : Optional.empty(),
        1,
        80,
        "2".repeat(64));
  }

  MetadataRepairAudit audit() {
    return new MetadataRepairAudit(
        attempt,
        plan,
        time,
        List.of(
            new MetadataRepairAuditEvent(
                1,
                MetadataRepairAuditEvent.Kind.PROSPECTIVE,
                time.plusSeconds(1),
                Optional.of(observation(false))),
            new MetadataRepairAuditEvent(
                2,
                MetadataRepairAuditEvent.Kind.STRUCTURE,
                time.plusSeconds(2),
                Optional.of(observation(true))),
            new MetadataRepairAuditEvent(
                3,
                MetadataRepairAuditEvent.Kind.COMPLETED,
                time.plusSeconds(2),
                Optional.of(observation(true)))));
  }

  MetadataBuildAssociation association() {
    var r = plan.request();
    var proof =
        plan.originalProof()
            .withAttestations(
                Optional.of(observation(true).decodedAttestation()),
                observation(true).structuralAttestation());
    return new MetadataBuildAssociation(
        new MetadataDeclaration(
            r.build(),
            r.configuration(),
            r.kind(),
            r.slot(),
            Optional.empty(),
            OptionalLong.empty(),
            r.declaredDecodedBytes()),
        plan.reservationToken(),
        proof,
        time.minusSeconds(60));
  }

  @Test
  void carriesExactAuditAndSeparateFreshGetReceiptWithoutChangingFirstAssociationTime() {
    var a = association();
    var audit = audit();
    var result = new MetadataRepairResult(a, Optional.of(audit));
    assertSame(a, result.association());
    assertSame(audit, result.completedAudit().orElseThrow());
    assertTrue(a.associatedAt().isBefore(audit.begunAt()));
    assertNotEquals(
        a.actualRepresentation().originalAlias().evidence(),
        audit.events().getLast().observation().orElseThrow().actualTransfer().evidence());
    assertEquals(80, audit.events().getLast().observation().orElseThrow().payloadResponseBytes());
    assertNotEquals(
        audit.issued().sourceObservation().observedAt(),
        audit
            .events()
            .getLast()
            .observation()
            .orElseThrow()
            .actualTransfer()
            .evidence()
            .verifiedAt());
  }

  @Test
  void localRaceResultHasNoInventedAudit() {
    var a = association();
    assertEquals(Optional.empty(), new MetadataRepairResult(a, Optional.empty()).completedAudit());
  }

  @Test
  void incompleteAuditAndMismatchedReservationOrContextFail() {
    var audit = audit();
    var incomplete = new MetadataRepairAudit(attempt, plan, time, audit.events().subList(0, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MetadataRepairResult(association(), Optional.of(incomplete)));
    var a = association();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairResult(
                new MetadataBuildAssociation(
                    a.declaration(), UUID.randomUUID(), a.actualRepresentation(), a.associatedAt()),
                Optional.of(audit)));
    var d = a.declaration();
    var b = d.build();
    var other =
        new BuildSnapshot(
            b.productId(),
            b.editionLabel(),
            b.channel(),
            b.region(),
            "different",
            b.buildConfig(),
            b.cdnConfig(),
            b.observedAt(),
            b.fixture());
    var different =
        new MetadataDeclaration(
            other,
            d.configuration(),
            d.kind(),
            d.slot(),
            d.preferredEncodingKey(),
            d.declaredEncodedBytes(),
            d.declaredDecodedBytes());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairResult(
                new MetadataBuildAssociation(
                    different,
                    a.canonicalReservationToken(),
                    a.actualRepresentation(),
                    a.associatedAt()),
                Optional.of(audit)));
  }

  @Test
  void alteredOriginalReceiptOrFreshCapabilityCannotMasqueradeAsCompletedRepair() {
    var a = association();
    var old = a.actualRepresentation();
    var alias = old.originalAlias();
    var changed =
        new VerifiedSourceAlias(
            alias.source(),
            alias.object(),
            new SourceVerificationEvidence(
                alias.evidence().verifierSemantics(),
                alias.evidence().sourceUrl(),
                alias.evidence().verifiedAt().plusSeconds(1)));
    var rewritten =
        new MetadataContentProof(
            changed,
            old.actualEncodingKey(),
            old.contentKey(),
            old.declaredDecodedBytes(),
            old.decodedAttestation(),
            old.structuralAttestation());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairResult(
                new MetadataBuildAssociation(
                    a.declaration(), a.canonicalReservationToken(), rewritten, a.associatedAt()),
                Optional.of(audit())));
    var different =
        new MetadataContentProof(
            old.originalAlias(),
            old.actualEncodingKey(),
            old.contentKey(),
            old.declaredDecodedBytes(),
            Optional.of(
                new DecodedContentAttestation(old.contentKey(), 100, "unverified-revision")),
            old.structuralAttestation());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairResult(
                new MetadataBuildAssociation(
                    a.declaration(), a.canonicalReservationToken(), different, a.associatedAt()),
                Optional.of(audit())));
  }

  @Test
  void sameSnapshotFirstObservationNormalizationRemainsValidButKnownBDeclarationDoesNot() {
    var a = association();
    var d = a.declaration();
    var b = d.build();
    var normalized =
        new BuildSnapshot(
            b.productId(),
            "First display",
            b.channel(),
            b.region(),
            b.versionLabel(),
            b.buildConfig(),
            b.cdnConfig(),
            Instant.EPOCH,
            b.fixture());
    var first =
        new MetadataDeclaration(
            normalized,
            d.configuration(),
            d.kind(),
            d.slot(),
            d.preferredEncodingKey(),
            d.declaredEncodedBytes(),
            d.declaredDecodedBytes());
    assertDoesNotThrow(
        () ->
            new MetadataRepairResult(
                new MetadataBuildAssociation(
                    first, a.canonicalReservationToken(), a.actualRepresentation(), Instant.EPOCH),
                Optional.of(audit())));
    var bDeclaration =
        new MetadataDeclaration(
            b,
            d.configuration(),
            d.kind(),
            d.slot(),
            Optional.of("f".repeat(32)),
            OptionalLong.of(99),
            d.declaredDecodedBytes());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairResult(
                new MetadataBuildAssociation(
                    bDeclaration,
                    a.canonicalReservationToken(),
                    a.actualRepresentation(),
                    a.associatedAt()),
                Optional.of(audit())));
  }

  @Test
  void originalSomeRevisionsStayOriginalEvenThoughAuditContainsNewVerifierFacts() {
    var initial = association();
    var original =
        plan.originalProof()
            .withAttestations(
                Optional.of(
                    new DecodedContentAttestation(
                        plan.originalProof().contentKey(), 100, "original-decoded")),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.ROOT, "original-grammar")));
    var issued =
        new MetadataRepairPlan(
            plan.issuanceId(),
            plan.reservationToken(),
            plan.request(),
            original,
            plan.sourceObservation(),
            plan.cost(),
            plan.issuedAt(),
            plan.expiresAt());
    var raw = observation(true);
    var prospective =
        new MetadataRepairObservation(
            attempt,
            original,
            raw.actualTransfer(),
            raw.decodedAttestation(),
            Optional.empty(),
            1,
            80,
            raw.observationDigest());
    var complete = prospective.withStructuralAttestation(raw.structuralAttestation().orElseThrow());
    var audit =
        new MetadataRepairAudit(
            attempt,
            issued,
            time,
            List.of(
                new MetadataRepairAuditEvent(
                    1,
                    MetadataRepairAuditEvent.Kind.PROSPECTIVE,
                    time.plusSeconds(1),
                    Optional.of(prospective)),
                new MetadataRepairAuditEvent(
                    2,
                    MetadataRepairAuditEvent.Kind.STRUCTURE,
                    time.plusSeconds(2),
                    Optional.of(complete)),
                new MetadataRepairAuditEvent(
                    3,
                    MetadataRepairAuditEvent.Kind.COMPLETED,
                    time.plusSeconds(2),
                    Optional.of(complete))));
    var unchanged =
        new MetadataBuildAssociation(
            initial.declaration(), issued.reservationToken(), original, initial.associatedAt());
    assertDoesNotThrow(() -> new MetadataRepairResult(unchanged, Optional.of(audit)));
    var restamped =
        new MetadataContentProof(
            original.originalAlias(),
            original.actualEncodingKey(),
            original.declaredContentKey(),
            original.declaredDecodedBytes(),
            Optional.of(complete.decodedAttestation()),
            complete.structuralAttestation());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataRepairResult(
                new MetadataBuildAssociation(
                    initial.declaration(),
                    issued.reservationToken(),
                    restamped,
                    initial.associatedAt()),
                Optional.of(audit)));
  }
}
