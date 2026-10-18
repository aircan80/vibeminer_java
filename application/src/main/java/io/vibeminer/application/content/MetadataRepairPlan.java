package io.vibeminer.application.content;

import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable proposed consent for exact original A. Issuers must mint a fresh issuance ID and replay
 * their canonical bounded session; constructors and clock checks do not authenticate issuance,
 * source observations or configuration. Ordinary/zero-cost reuse plans grant no repair consent.
 */
public record MetadataRepairPlan(
    UUID issuanceId,
    UUID reservationToken,
    MetadataContentRequest request,
    MetadataContentProof originalProof,
    MetadataRepairSourceObservation sourceObservation,
    MetadataRepairCost cost,
    Instant issuedAt,
    Instant expiresAt) {
  public static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(10);

  public MetadataRepairPlan {
    Objects.requireNonNull(issuanceId);
    Objects.requireNonNull(reservationToken);
    Objects.requireNonNull(request);
    Objects.requireNonNull(originalProof);
    Objects.requireNonNull(sourceObservation);
    Objects.requireNonNull(cost);
    Objects.requireNonNull(issuedAt);
    Objects.requireNonNull(expiresAt);
    if (request.kind() != MetadataStructureAttestation.Kind.ROOT
        && request.kind() != MetadataStructureAttestation.Kind.INSTALL)
      throw new ContentClaimException(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          "Metadata repair supports only ROOT and INSTALL");
    String namespace =
        request.kind() == MetadataStructureAttestation.Kind.ROOT
            ? "wow.root.tsfm.v1v2"
            : "tact.install.v1";
    var tuple = originalProof.originalAlias().source().immutableSourceKey().split(";", -1);
    if (!originalProof.originalAlias().source().namespace().equals(namespace)
        || !request.slot().contentKey().equals(originalProof.declaredContentKey())
        || !request.requestedEncodingKey().equals(originalProof.actualEncodingKey())
        || request.requestedEncodedBytes() != originalProof.object().length()
        || request.declaredDecodedBytes().isEmpty()
        || !tuple[2].equals(Long.toString(request.declaredDecodedBytes().getAsLong()))
        || !sourceObservation.resourceKey().equals(originalProof.actualEncodingKey())
        || sourceObservation.wholeEncodedBytes() != originalProof.object().length()
        || cost.encodedTransferBytes() != originalProof.object().length()
        || cost.maximumAdditionalPhysicalBytes() != originalProof.object().length()
        || request.declaredDecodedBytes().getAsLong() > Long.MAX_VALUE / 2
        || cost.maximumDecodedWorkBytes() < request.declaredDecodedBytes().getAsLong() * 2)
      throw new IllegalArgumentException(
          "Repair must bind original A, known cost and complete verification work");
    if (!expiresAt.isAfter(issuedAt)
        || Duration.between(issuedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0
        || sourceObservation.observedAt().isAfter(issuedAt)
        || Duration.between(sourceObservation.observedAt(), issuedAt).compareTo(MAXIMUM_LIFETIME)
            > 0)
      throw new IllegalArgumentException(
          "Fresh bounded repair issuance and HEAD observation required");
  }

  /** Temporal shape only; callers must also replay the exact issued plan and current authority. */
  public boolean isUsableAt(Instant now) {
    Objects.requireNonNull(now);
    return !now.isBefore(issuedAt) && now.isBefore(expiresAt);
  }
}
