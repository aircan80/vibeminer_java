package io.vibeminer.application.content;

import io.vibeminer.domain.content.MetadataContentProof;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable local-replay proposal, not current readability or authority to attach a membership.
 * Previous full attestations remain required; execution must revalidate exact config and physical
 * SHA, decoded CKey/length and supported grammar under the canonical worker/lease boundary.
 */
public record MetadataReusePlan(
    UUID issuanceId,
    UUID canonicalReservationToken,
    MetadataDeclaration declaration,
    MetadataContentProof actualRepresentation) {
  public enum State {
    LOCAL_REPLAY_REQUIRED
  }

  public MetadataReusePlan {
    Objects.requireNonNull(issuanceId);
    Objects.requireNonNull(canonicalReservationToken);
    Objects.requireNonNull(declaration);
    declaration.requireStrongActual(actualRepresentation);
  }

  public State state() {
    return State.LOCAL_REPLAY_REQUIRED;
  }

  /** New configuration acquisition and local verification work are separate costs. */
  public long additionalPayloadTransferBytes() {
    return 0;
  }
}
