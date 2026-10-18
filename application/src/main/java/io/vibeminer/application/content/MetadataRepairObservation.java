package io.vibeminer.application.content;

import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.DecodedContentAttestation;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Separate verifier-issued repair facts. Historical source evidence/capabilities remain untouched;
 * no fresh decoded/grammar capability follows merely from construction or a HEAD observation.
 */
public record MetadataRepairObservation(
    UUID repairAttemptId,
    MetadataContentProof originalProof,
    VerifiedSourceAlias actualTransfer,
    DecodedContentAttestation decodedAttestation,
    Optional<MetadataStructureAttestation> structuralAttestation,
    int payloadRequestCount,
    long payloadResponseBytes,
    String observationDigest) {
  public MetadataRepairObservation {
    Objects.requireNonNull(repairAttemptId);
    Objects.requireNonNull(originalProof);
    Objects.requireNonNull(actualTransfer);
    Objects.requireNonNull(decodedAttestation);
    Objects.requireNonNull(structuralAttestation);
    var namespace = originalProof.originalAlias().source().namespace();
    if (!namespace.equals("wow.root.tsfm.v1v2") && !namespace.equals("tact.install.v1"))
      throw new ContentClaimException(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          "Metadata repair observations support only ROOT and INSTALL");
    var tuple = originalProof.originalAlias().source().immutableSourceKey().split(";", -1);
    var kind =
        originalProof.originalAlias().source().namespace().equals("wow.root.tsfm.v1v2")
            ? MetadataStructureAttestation.Kind.ROOT
            : MetadataStructureAttestation.Kind.INSTALL;
    if (!originalProof.originalAlias().source().equals(actualTransfer.source())
        || !originalProof.object().equals(actualTransfer.object())
        || !decodedAttestation.contentKey().equals(originalProof.declaredContentKey())
        || !tuple[2].equals(Long.toString(decodedAttestation.actualDecodedBytes()))
        || structuralAttestation.isPresent() && structuralAttestation.get().kind() != kind
        || payloadRequestCount < 1
        || payloadResponseBytes != originalProof.object().length()
        || payloadRequestCount > payloadResponseBytes
        || observationDigest == null
        || !observationDigest.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException(
          "Repair facts must verify exactly the original physical and decoded object");
    MetadataRepairSourceObservation.requireResourceUrl(
        actualTransfer.evidence().sourceUrl(), originalProof.actualEncodingKey());
  }

  /**
   * Adds only the completing parser's fact for this repair; never rewrites the historical proof.
   */
  public MetadataRepairObservation withStructuralAttestation(
      MetadataStructureAttestation structure) {
    Objects.requireNonNull(structure);
    if (structuralAttestation.isPresent() && !structuralAttestation.get().equals(structure))
      throw new IllegalArgumentException("Repair grammar attestation cannot be replaced");
    return new MetadataRepairObservation(
        repairAttemptId,
        originalProof,
        actualTransfer,
        decodedAttestation,
        Optional.of(structure),
        payloadRequestCount,
        payloadResponseBytes,
        observationDigest);
  }
}
