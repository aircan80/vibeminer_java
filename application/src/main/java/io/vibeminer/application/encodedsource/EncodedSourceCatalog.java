package io.vibeminer.application.encodedsource;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.content.ContentWorkerFinishOutcome;
import io.vibeminer.domain.content.ContentWorkerPermit;
import io.vibeminer.domain.selectedfile.*;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable canonical representation policy. Metadata alone never proves current local availability.
 */
public interface EncodedSourceCatalog {
  Optional<CanonicalReservation> lookup(ContentSlotKey slot, CancellationToken cancellation);

  CanonicalReservation reserve(
      ContentSlotKey slot, String encodingKey, long encodedBytes, CancellationToken cancellation);

  /** Legacy operation: any active nonce must cause a fail-closed rejection. */
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

  /** Exclusive actual source-flight authority; never acquired separately by every consumer. */
  default ContentWorkerPermit claimWorker(UUID reservationToken, CancellationToken cancellation) {
    throw workerUnsupported();
  }

  default void markPublishing(ContentWorkerPermit permit, CancellationToken cancellation) {
    throw workerUnsupported();
  }

  default void recordVerifiedCompletion(
      ContentWorkerPermit permit,
      ContentRepresentation representation,
      CancellationToken cancellation) {
    throw workerUnsupported();
  }

  default ContentRepresentation completePublication(
      ContentWorkerPermit permit,
      ContentRepresentation representation,
      CancellationToken cancellation) {
    throw workerUnsupported();
  }

  /** Actual flight finally, after all IO cleanup, for every permit held by its frozen fanout. */
  default void finishWorker(
      ContentWorkerPermit permit,
      ContentWorkerFinishOutcome outcome,
      CancellationToken cancellation) {
    throw workerUnsupported();
  }

  private static EncodedSourceException workerUnsupported() {
    return new EncodedSourceException(
        EncodedSourceException.Code.UNSUPPORTED,
        "Catalog does not implement fenced publisher authority");
  }
}
