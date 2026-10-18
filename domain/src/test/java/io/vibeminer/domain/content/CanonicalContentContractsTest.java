package io.vibeminer.domain.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalContentContractsTest {
  static final String C = "a".repeat(32), E = "b".repeat(32);

  static VerifiedSourceAlias root() {
    var source =
        new ResolvedSourceIdentity("wow.root.tsfm.v1v2", C + ";" + E + ";100;80", "legacy-root-v1");
    return new VerifiedSourceAlias(
        source,
        new StoredObject(new ObjectDigest("c".repeat(64)), 80),
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/fixture", Instant.EPOCH));
  }

  static MetadataContentProof historical() {
    return new MetadataContentProof(
        root(), E, C, OptionalLong.empty(), Optional.empty(), Optional.empty());
  }

  @Test
  void historicalIdentityNeverImplicitlyGrantsDecodedOrGrammarCapability() {
    var proof = historical();
    assertTrue(proof.decodedAttestation().isEmpty());
    assertTrue(proof.structuralAttestation().isEmpty());
    assertTrue(proof.declaredDecodedBytes().isEmpty());
    var claim =
        new ContentClaim(
            new ContentSlotKey("wow", C),
            E,
            80,
            UUID.randomUUID(),
            CanonicalReservation.Status.RETAINED,
            Optional.of(proof));
    assertEquals(root().object(), claim.proof().orElseThrow().object());
  }

  @Test
  void fullDecodedThenGrammarAttestationsAreExplicitAndMonotonic() {
    var decoded = new DecodedContentAttestation(C, 100, "full-md5-v1");
    var grammar =
        new MetadataStructureAttestation(MetadataStructureAttestation.Kind.ROOT, "root-v2");
    var protocol = historical().withAttestations(Optional.of(decoded), Optional.empty());
    assertTrue(protocol.structuralAttestation().isEmpty());
    var complete = protocol.withAttestations(Optional.of(decoded), Optional.of(grammar));
    assertEquals(root(), complete.originalAlias());
    assertEquals(grammar, complete.structuralAttestation().orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () -> complete.withAttestations(Optional.of(decoded), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            protocol.withAttestations(
                Optional.of(new DecodedContentAttestation(C, 100, "different-v2")),
                Optional.empty()));
  }

  @Test
  void inconsistentAliasKeyLengthOrCapabilityIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                root(),
                "d".repeat(32),
                C,
                OptionalLong.empty(),
                Optional.empty(),
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                root(), E, C, OptionalLong.of(101), Optional.empty(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                root(),
                E,
                C,
                OptionalLong.empty(),
                Optional.of(new DecodedContentAttestation(C, 101, "v1")),
                Optional.empty()));
    var grammar = new MetadataStructureAttestation(MetadataStructureAttestation.Kind.ROOT, "v1");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                root(), E, C, OptionalLong.empty(), Optional.empty(), Optional.of(grammar)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                root(),
                E,
                C,
                OptionalLong.empty(),
                Optional.of(new DecodedContentAttestation(C, 100, "v1")),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.INSTALL, "v1"))));
  }

  @Test
  void claimRejectsMissingRetainedProofAndWrongSlotOrActualObjectLength() {
    var slot = new ContentSlotKey("wow", C);
    var token = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ContentClaim(
                slot, E, 80, token, CanonicalReservation.Status.RETAINED, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ContentClaim(
                slot,
                E,
                81,
                token,
                CanonicalReservation.Status.RECOVERY,
                Optional.of(historical())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ContentClaim(
                new ContentSlotKey("wow", "d".repeat(32)),
                E,
                80,
                token,
                CanonicalReservation.Status.RECOVERY,
                Optional.of(historical())));
    assertTrue(
        new ContentClaim(slot, E, 80, token, CanonicalReservation.Status.PENDING, Optional.empty())
            .proof()
            .isEmpty());
  }

  @Test
  void encodedProofPreservesActualReceiptWithoutMetadataUpgrade() {
    var source = ContentRepresentation.encodedIdentity(E);
    var evidence =
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/fixture", Instant.EPOCH);
    var alias =
        new VerifiedSourceAlias(
            source, new StoredObject(new ObjectDigest("d".repeat(64)), 80), evidence);
    var receipt =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            evidence.sourceUrl(),
            E,
            80,
            0,
            80,
            Instant.EPOCH,
            1,
            80,
            "e".repeat(64),
            Optional.empty());
    var representation =
        new ContentRepresentation(
            new ContentSlotKey("wow", C), alias, EncodedScope.ZERO_HEADER_WHOLE_ENCODED, receipt);
    var proof = new EncodedContentProof(representation);
    assertSame(representation, proof.receipt());
    assertEquals(alias.object(), proof.object());
    assertEquals(E, proof.actualEncodingKey());
    assertEquals(
        proof,
        new ContentClaim(
                representation.slot(),
                E,
                80,
                UUID.randomUUID(),
                CanonicalReservation.Status.RETAINED,
                Optional.of(proof))
            .proof()
            .orElseThrow());
    assertFalse(((CanonicalContentProof) proof) instanceof MetadataContentProof);
  }

  @Test
  void unknownAndMalformedMetadataSourcesAreNotInferredFromNamespaceStrings() {
    var original = root();
    var wrongLength =
        new VerifiedSourceAlias(
            original.source(),
            new StoredObject(original.object().digest(), 79),
            original.evidence());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                wrongLength, E, C, OptionalLong.empty(), Optional.empty(), Optional.empty()));
    var unknownSource =
        new ResolvedSourceIdentity("wow.unrecognized", C + ";" + E + ";100;80", "v1");
    var unknown =
        new VerifiedSourceAlias(
            unknownSource,
            original.object(),
            new SourceVerificationEvidence("v1", original.evidence().sourceUrl(), Instant.EPOCH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                unknown, E, C, OptionalLong.empty(), Optional.empty(), Optional.empty()));
    var overflowSource =
        new ResolvedSourceIdentity(
            original.source().namespace(), C + ";" + E + ";9999999999999999999;80", "v1");
    var overflow =
        new VerifiedSourceAlias(
            overflowSource,
            original.object(),
            new SourceVerificationEvidence("v1", original.evidence().sourceUrl(), Instant.EPOCH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentProof(
                overflow, E, C, OptionalLong.empty(), Optional.empty(), Optional.empty()));
    assertThrows(IllegalArgumentException.class, () -> new DecodedContentAttestation(C, -1, "v1"));
    assertThrows(IllegalArgumentException.class, () -> new DecodedContentAttestation(C, 100, ""));
  }
}
