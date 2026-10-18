package io.vibeminer.application.selectedfile;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.retention.RetentionScope;
import io.vibeminer.domain.selectedfile.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable canonical representation policy. Metadata alone never proves current local availability.
 */
public interface SelectedFileCatalog {
  Optional<CanonicalReservation> lookup(ContentSlotKey slot, CancellationToken cancellation);

  CanonicalReservation reserve(
      ContentSlotKey slot, String encodingKey, long encodedBytes, CancellationToken cancellation);

  void markPublishing(UUID reservationToken, CancellationToken cancellation);

  /**
   * Called from the completing verifier gate BEFORE CAS promotion; persists prospective SHA/proof.
   */
  void recordVerifiedCompletion(
      UUID reservationToken, ContentRepresentation representation, CancellationToken cancellation);

  /**
   * Revalidates physical bytes and atomically commits canonical publication and the encoded alias.
   */
  ContentRepresentation completePublication(
      UUID reservationToken, ContentRepresentation representation, CancellationToken cancellation);

  /**
   * Only the retired source worker may release a conclusively unpublished attempt without proof.
   */
  void releaseUnpublished(UUID reservationToken, CancellationToken cancellation);

  /** Keep the actual candidate exclusive whenever publication is uncertain. */
  void markUncertain(UUID reservationToken, CancellationToken cancellation);

  /**
   * Per surviving consumer, atomically attach typed association, exact membership and dependency
   * pin.
   */
  SelectedFileAssociation attachAssociation(
      SelectedFileAssociation association, RetentionScope scope, CancellationToken cancellation);

  /** Typed historical metadata, including when its physical bytes are now missing or corrupt. */
  Optional<SelectedFileAssociation> findAssociation(
      String snapshotId, String role, CancellationToken cancellation);
}
