package io.vibeminer.application.content;

import io.vibeminer.domain.content.MetadataContentProof;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact configuration-to-actual-content association facts. Creation alone proves no replay or
 * transaction; later policy must validate, attach and pin these facts atomically. Association
 * origin is separate from the unchanged original acquisition receipt and never invents an encoding
 * alias.
 */
public record MetadataBuildAssociation(
    MetadataDeclaration declaration,
    UUID canonicalReservationToken,
    MetadataContentProof actualRepresentation,
    Instant associatedAt) {
  public MetadataBuildAssociation {
    Objects.requireNonNull(declaration);
    Objects.requireNonNull(canonicalReservationToken);
    Objects.requireNonNull(associatedAt);
    declaration.requireStrongActual(actualRepresentation);
  }
}
