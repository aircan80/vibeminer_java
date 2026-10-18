package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataRepairAuditRecordsTest {
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00.123456789Z");

  static String url(String key) {
    return "https://us.cdn.blizzard.com/tpr/wow/data/"
        + key.substring(0, 2)
        + "/"
        + key.substring(2, 4)
        + "/"
        + key;
  }

  static MetadataRepairPlan plan() {
    var a = MetadataAssociationRecordsTest.association();
    var d = a.declaration();
    var old = a.actualRepresentation();
    var original =
        new MetadataContentProof(
            old.originalAlias(),
            old.actualEncodingKey(),
            old.contentKey(),
            old.declaredDecodedBytes(),
            Optional.empty(),
            Optional.empty());
    var request =
        new MetadataContentRequest(
            d.build(),
            d.configuration(),
            d.kind(),
            d.slot(),
            original.actualEncodingKey(),
            original.encodedBytes(),
            OptionalLong.of(64));
    return new MetadataRepairPlan(
        UUID.randomUUID(),
        UUID.randomUUID(),
        request,
        original,
        new MetadataRepairSourceObservation(
            url(original.actualEncodingKey()),
            original.actualEncodingKey(),
            80,
            NOW,
            "1".repeat(64)),
        new MetadataRepairCost(80, 80, 4, 128, Duration.ofMinutes(2)),
        NOW,
        NOW.plusSeconds(300));
  }

  static MetadataRepairObservation observation(
      UUID attempt, MetadataRepairPlan plan, boolean grammar) {
    var original = plan.originalProof();
    var transfer =
        new VerifiedSourceAlias(
            original.originalAlias().source(),
            original.object(),
            new SourceVerificationEvidence(
                original.originalAlias().evidence().verifierSemantics(),
                plan.sourceObservation().resourceUrl(),
                NOW.plusSeconds(1)));
    return new MetadataRepairObservation(
        attempt,
        original,
        transfer,
        new DecodedContentAttestation(original.contentKey(), 64, "fresh-decoded-v1"),
        grammar
            ? Optional.of(
                new MetadataStructureAttestation(plan.request().kind(), "fresh-grammar-v1"))
            : Optional.empty(),
        1,
        80,
        "2".repeat(64));
  }

  @Test
  void boundedBaseAndFreshEventRoundtripKeepsOriginalProofAndReceiptsSeparate() {
    var p = plan();
    var attempt = UUID.randomUUID();
    assertEquals(
        p,
        MetadataRepairAuditRecords.plan(MetadataRepairAuditRecords.plan(p), p.request().build()));
    var event =
        new MetadataRepairAuditEvent(
            1,
            MetadataRepairAuditEvent.Kind.PROSPECTIVE,
            NOW.plusSeconds(1),
            Optional.of(observation(attempt, p, false)));
    assertEquals(event, MetadataRepairAuditRecords.event(MetadataRepairAuditRecords.event(event)));
    assertNotEquals(
        p.originalProof().originalAlias().evidence(),
        event.observation().get().actualTransfer().evidence());
    assertTrue(p.originalProof().decodedAttestation().isEmpty());
  }

  @Test
  void everyTruncationTrailingWrongBuildAndOversizedBaseFail() {
    var p = plan();
    var bytes = MetadataRepairAuditRecords.plan(p);
    for (int i = 0; i < bytes.length; i++) {
      var prefix = Arrays.copyOf(bytes, i);
      assertThrows(
          IllegalArgumentException.class,
          () -> MetadataRepairAuditRecords.plan(prefix, p.request().build()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetadataRepairAuditRecords.plan(
                Arrays.copyOf(bytes, bytes.length + 1), p.request().build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetadataRepairAuditRecords.event(new byte[MetadataRepairAuditRecords.MAX_BYTES + 1]));
    bytes[4] = (byte) 255;
    assertThrows(
        IllegalArgumentException.class,
        () -> MetadataRepairAuditRecords.plan(bytes, p.request().build()));
  }

  @Test
  void everyEventTruncationGrammarAndTerminalFailureRoundtripAreStrict() {
    var p = plan();
    var attempt = UUID.randomUUID();
    var event =
        new MetadataRepairAuditEvent(
            2,
            MetadataRepairAuditEvent.Kind.STRUCTURE,
            NOW.plusSeconds(2),
            Optional.of(observation(attempt, p, true)));
    var bytes = MetadataRepairAuditRecords.event(event);
    assertEquals(event, MetadataRepairAuditRecords.event(bytes));
    for (int i = 0; i < bytes.length; i++) {
      var prefix = Arrays.copyOf(bytes, i);
      assertThrows(IllegalArgumentException.class, () -> MetadataRepairAuditRecords.event(prefix));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> MetadataRepairAuditRecords.event(Arrays.copyOf(bytes, bytes.length + 1)));
    var failed =
        new MetadataRepairAuditEvent(
            1, MetadataRepairAuditEvent.Kind.UNCERTAIN, NOW.plusSeconds(2), Optional.empty());
    assertEquals(
        failed, MetadataRepairAuditRecords.event(MetadataRepairAuditRecords.event(failed)));
  }

  @Test
  void nestedLossyUtf16AndWrongBuildCannotNormalizeAuditProvenance() {
    var p = plan();
    var original = p.originalProof();
    var old = original.originalAlias();
    var hostile =
        new VerifiedSourceAlias(
            old.source(),
            old.object(),
            new SourceVerificationEvidence(
                old.evidence().verifierSemantics(),
                "https://us.cdn.blizzard.com/" + (char) 0xd800,
                old.evidence().verifiedAt()));
    var proof =
        new MetadataContentProof(
            hostile,
            original.actualEncodingKey(),
            original.contentKey(),
            original.declaredDecodedBytes(),
            original.decodedAttestation(),
            original.structuralAttestation());
    var other =
        new MetadataRepairPlan(
            p.issuanceId(),
            p.reservationToken(),
            p.request(),
            proof,
            p.sourceObservation(),
            p.cost(),
            p.issuedAt(),
            p.expiresAt());
    assertThrows(IllegalArgumentException.class, () -> MetadataRepairAuditRecords.plan(other));
    var b = p.request().build();
    var wrong =
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
    assertThrows(
        IllegalArgumentException.class,
        () -> MetadataRepairAuditRecords.plan(MetadataRepairAuditRecords.plan(p), wrong));
  }
}
