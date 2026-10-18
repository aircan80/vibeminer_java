package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import io.vibeminer.domain.retention.RetentionScope;
import java.util.Optional;

/** Durable per-build declarations associated with unchanged original metadata representations. */
public interface MetadataAssociationRepository {
  /**
   * Caller must authenticate the exact configuration declaration and replay local protocol/grammar
   * before attachment. DTO construction alone is not authentication. Atomically attaches immutable
   * memberships, association and actual-content retention scope under verified local leases. This
   * operation neither owns nor mutates the canonical publisher claim.
   */
  MetadataBuildAssociation attach(
      MetadataBuildAssociation association, RetentionScope scope, CancellationToken cancellation);

  /**
   * Historical original association, never a claim of physical readiness or permission to fetch.
   */
  Optional<MetadataBuildAssociation> find(
      String snapshotId, MetadataStructureAttestation.Kind kind, CancellationToken cancellation);
}
