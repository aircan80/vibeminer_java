package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataAssociationRecordsTest {
  static BuildSnapshot build() {
    return new BuildSnapshot(
        "wow",
        "Retail",
        "live",
        "us",
        "fixture",
        "1".repeat(32),
        "2".repeat(32),
        Instant.EPOCH,
        true);
  }

  static MetadataBuildAssociation association() {
    var build = build();
    var source =
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1");
    var config =
        new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest("d".repeat(64)), 24),
            new SourceVerificationEvidence(
                source.verificationSemantics(),
                "https://us.cdn.blizzard.com/config",
                Instant.EPOCH));
    var actual =
        CanonicalContentRecordsTest.historical()
            .withAttestations(
                Optional.of(
                    new DecodedContentAttestation(CanonicalContentRecordsTest.C, 64, "decoded-v1")),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.ROOT, "root-v1")));
    var declaration =
        new MetadataDeclaration(
            build,
            config,
            MetadataStructureAttestation.Kind.ROOT,
            new ContentSlotKey("wow", actual.contentKey()),
            Optional.of("b".repeat(32)),
            OptionalLong.of(100),
            OptionalLong.of(64));
    return new MetadataBuildAssociation(
        declaration, UUID.randomUUID(), actual, Instant.parse("2026-10-18T12:30:00.123456789Z"));
  }

  @Test
  void preservesDeclaredBAndOriginalAWithoutRestamping() {
    var value = association();
    assertEquals(
        value,
        MetadataAssociationRecords.decode(MetadataAssociationRecords.encode(value), build()));
    assertNotEquals(
        value.declaration().preferredEncodingKey().orElseThrow(),
        value.actualRepresentation().actualEncodingKey());
  }

  @Test
  void rejectsEveryTruncationTrailingWrongBuildAndBounds() {
    var value = association();
    var bytes = MetadataAssociationRecords.encode(value);
    for (int i = 0; i < bytes.length; i++) {
      final var prefix = Arrays.copyOf(bytes, i);
      assertThrows(
          IllegalArgumentException.class, () -> MetadataAssociationRecords.decode(prefix, build()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> MetadataAssociationRecords.decode(Arrays.copyOf(bytes, bytes.length + 1), build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetadataAssociationRecords.decode(
                new byte[MetadataAssociationRecords.MAX_BYTES + 1], build()));
    var wrong =
        new BuildSnapshot(
            "wow",
            "Retail",
            "live",
            "us",
            "other",
            "1".repeat(32),
            "2".repeat(32),
            Instant.EPOCH,
            true);
    assertThrows(
        IllegalArgumentException.class, () -> MetadataAssociationRecords.decode(bytes, wrong));
    bytes[4] = (byte) 255;
    assertThrows(
        IllegalArgumentException.class, () -> MetadataAssociationRecords.decode(bytes, build()));
  }

  @Test
  void preservesUnknownDeclarationsAndRejectsLossyNestedUtf16() {
    var a = association();
    var d = a.declaration();
    var unknown =
        new MetadataBuildAssociation(
            new MetadataDeclaration(
                d.build(),
                d.configuration(),
                d.kind(),
                d.slot(),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty()),
            a.canonicalReservationToken(),
            a.actualRepresentation(),
            a.associatedAt());
    assertEquals(
        unknown,
        MetadataAssociationRecords.decode(MetadataAssociationRecords.encode(unknown), build()));
    var old = a.actualRepresentation();
    var original = old.originalAlias();
    var hostile =
        new VerifiedSourceAlias(
            original.source(),
            original.object(),
            new SourceVerificationEvidence(
                original.evidence().verifierSemantics(),
                "https://us.cdn.blizzard.com/" + (char) 0xd800,
                original.evidence().verifiedAt()));
    var proof =
        new MetadataContentProof(
            hostile,
            old.actualEncodingKey(),
            old.contentKey(),
            old.declaredDecodedBytes(),
            old.decodedAttestation(),
            old.structuralAttestation());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MetadataAssociationRecords.encode(
                new MetadataBuildAssociation(
                    d, a.canonicalReservationToken(), proof, a.associatedAt())));
  }
}
