package io.vibeminer.domain.selectedfile;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SelectedFileContractsTest {
  static final String CKEY = "a".repeat(32), EKEY = "b".repeat(32);

  static BuildSnapshot build(String product) {
    return new BuildSnapshot(
        product,
        "Fixture",
        "current",
        "us",
        "1",
        "c".repeat(32),
        "d".repeat(32),
        Instant.EPOCH,
        true);
  }

  static FileVariantAssociation variant() {
    return new FileVariantAssociation(
        build("wow"),
        new ResolvedSourceIdentity("fixture", "root", "root.v1"),
        42,
        CKEY,
        1,
        2,
        3,
        4,
        5,
        Optional.empty());
  }

  static ContentRepresentation representation() {
    String url = "https://us.cdn.blizzard.com/fixture";
    var receipt =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            url,
            EKEY,
            10,
            0,
            10,
            Instant.EPOCH,
            1,
            10,
            "e".repeat(64),
            Optional.empty());
    var source = ContentRepresentation.encodedIdentity(EKEY);
    var alias =
        new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest("f".repeat(64)), 10),
            new SourceVerificationEvidence(source.verificationSemantics(), url, Instant.EPOCH));
    return new ContentRepresentation(
        new ContentSlotKey("wow", CKEY), alias, EncodedScope.ZERO_HEADER_WHOLE_ENCODED, receipt);
  }

  @Test
  void exactRolesAreStableFullDigestAndRepresentationsHaveDifferentRoles() {
    var a = new SelectedFileDescriptor(variant(), EKEY, ContentRepresentation.VERIFIER_SEMANTICS);
    var b =
        new SelectedFileDescriptor(
            variant(), "1".repeat(32), ContentRepresentation.VERIFIER_SEMANTICS);
    assertEquals(47, a.role().length());
    assertEquals(
        a.role(),
        new SelectedFileDescriptor(variant(), EKEY, ContentRepresentation.VERIFIER_SEMANTICS)
            .role());
    assertNotEquals(a.role(), b.role());
  }

  @Test
  void onlyExplicitNamespaceAndProductAreSupported() {
    assertThrows(IllegalArgumentException.class, () -> new ContentSlotKey("wowf", CKEY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FileVariantAssociation(
                build("wowf"), variant().rootSource(), 42, CKEY, 1, 2, 3, 4, 5, Optional.empty()));
  }

  @Test
  void prospectiveRecoveryAndRetainedReceiptsKeepExactSource() {
    var value = representation();
    var token = UUID.randomUUID();
    assertEquals(
        value,
        new CanonicalReservation(
                value.slot(),
                EKEY,
                10,
                token,
                CanonicalReservation.Status.RECOVERY,
                Optional.of(value))
            .representation()
            .orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanonicalReservation(
                value.slot(),
                EKEY,
                10,
                token,
                CanonicalReservation.Status.RETAINED,
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanonicalReservation(
                value.slot(),
                "1".repeat(32),
                10,
                token,
                CanonicalReservation.Status.RECOVERY,
                Optional.of(value)));
  }

  @Test
  void aliasCannotRelabelArchiveOrLooseObservation() {
    var value = representation();
    var wrong =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            value.sourceReceipt().actualResourceUrl(),
            "1".repeat(32),
            10,
            0,
            10,
            Instant.EPOCH,
            1,
            10,
            "e".repeat(64),
            Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContentRepresentation(value.slot(), value.encoded(), value.scope(), wrong));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncodedSourceReceipt(
                EncodedSourceReceipt.Kind.ARCHIVE,
                value.sourceReceipt().actualResourceUrl(),
                EKEY,
                Long.MAX_VALUE,
                Long.MAX_VALUE - 1,
                10,
                Instant.EPOCH,
                1,
                10,
                "e".repeat(64),
                Optional.empty()));
  }

  @Test
  void encodedRetentionScopeRequiresExplicitDependencyClosure() {
    var descriptor =
        new SelectedFileDescriptor(variant(), EKEY, ContentRepresentation.VERIFIER_SEMANTICS);
    var requirements =
        java.util.List.of(
            new io.vibeminer.domain.retention.ScopeRequirement(
                "BUILD_CONFIG",
                Optional.empty(),
                java.util.OptionalLong.empty(),
                java.util.OptionalLong.empty()),
            new io.vibeminer.domain.retention.ScopeRequirement(
                "ROOT_MANIFEST",
                Optional.empty(),
                java.util.OptionalLong.empty(),
                java.util.OptionalLong.empty()),
            new io.vibeminer.domain.retention.ScopeRequirement(
                descriptor.role(),
                Optional.empty(),
                java.util.OptionalLong.of(10),
                java.util.OptionalLong.empty()));
    assertEquals(
        3,
        new io.vibeminer.domain.retention.RetentionScope(
                variant().build().snapshotId(),
                io.vibeminer.domain.retention.ScopeKind.ENCODED_FILE,
                requirements)
            .requirements()
            .size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new io.vibeminer.domain.retention.RetentionScope(
                variant().build().snapshotId(),
                io.vibeminer.domain.retention.ScopeKind.ENCODED_FILE,
                requirements.subList(0, 2)));
  }

  @Test
  void archiveHintConflictIsPreservedAndUnknownGeometryIsExplicit() {
    var proof =
        new ArchiveMappingProof(
            EKEY,
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "archive-v1",
            java.util.OptionalLong.of(12388),
            4148);
    assertEquals(12388, proof.advertisedIndexBytes().orElseThrow());
    assertEquals(4148, proof.authenticatedIndexBytes());
    assertTrue(proof.hasAuthenticatedIndexGeometry());
    var legacy =
        new ArchiveMappingProof(EKEY, "1".repeat(64), "2".repeat(64), "3".repeat(64), "archive-v1");
    assertFalse(legacy.hasAuthenticatedIndexGeometry());
    assertTrue(legacy.advertisedIndexBytes().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArchiveMappingProof(
                EKEY,
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "archive-v1",
                java.util.OptionalLong.of(0),
                4148));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArchiveMappingProof(
                EKEY,
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "archive-v1",
                java.util.OptionalLong.of(12388),
                0));
  }

  @Test
  void associationProofRetainsBoundedImmutableCandidateDeclarations() {
    var source = variant().rootSource();
    var candidates = new java.util.ArrayList<EncodedCandidate>();
    var candidate =
        new EncodedCandidate(EKEY, java.util.OptionalLong.of(10), java.util.OptionalLong.empty());
    candidates.add(candidate);
    var proof =
        new FileAssociationProof(
            source,
            source,
            "association-v2",
            "1".repeat(64),
            java.util.OptionalLong.of(42),
            candidates);
    candidates.clear();
    assertEquals(1, proof.authenticatedCandidates().size());
    assertEquals(42, proof.declaredDecodedBytes().orElseThrow());
    assertThrows(
        UnsupportedOperationException.class, () -> proof.authenticatedCandidates().clear());
    assertTrue(
        new FileAssociationProof(source, source, "legacy", "1".repeat(64))
            .authenticatedCandidates()
            .isEmpty());
    assertTrue(
        new FileAssociationProof(source, source, "legacy", "1".repeat(64))
            .declaredDecodedBytes()
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FileAssociationProof(
                source,
                source,
                "v2",
                "1".repeat(64),
                java.util.OptionalLong.of(-1),
                java.util.List.of(candidate)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FileAssociationProof(
                source,
                source,
                "v2",
                "1".repeat(64),
                java.util.OptionalLong.empty(),
                java.util.List.of(candidate, candidate)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FileAssociationProof(
                source,
                source,
                "v2",
                "1".repeat(64),
                java.util.OptionalLong.empty(),
                java.util.Collections.nCopies(256, candidate)));
  }
}
