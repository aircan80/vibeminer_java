package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.content.CanonicalContentProof;
import io.vibeminer.domain.content.ContentClaim;
import io.vibeminer.domain.content.ContentWorkerFinishOutcome;
import io.vibeminer.domain.content.ContentWorkerPermit;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking port to the one authoritative content claim, shared by metadata and encoded files.
 * Lookup reports ledger state, never present/readable bytes. Legacy conflicts fail explicitly.
 * Implementations own transactions and physical verification leases; callers own source workers.
 */
public interface CanonicalContentRepository {
  Optional<ContentClaim> lookup(ContentSlotKey slot, CancellationToken cancellation);

  /**
   * Same EKey/length may join; alternate pending is BUSY, retained requires replan, uncertain
   * recovery stays exclusive.
   */
  ContentClaim reserve(
      ContentSlotKey slot,
      String actualEncodingKey,
      long encodedBytes,
      CancellationToken cancellation);

  /**
   * Legacy operation: implementations must reject any active worker nonce, including same session.
   */
  void markPublishing(UUID token, CancellationToken cancellation);

  /**
   * Durable prospective SHA and scoped proof, recorded in the verifier gate before CAS promotion.
   */
  void recordVerifiedCompletion(
      UUID token, CanonicalContentProof proof, CancellationToken cancellation);

  /**
   * Physical lease + complete proof required. Does not attach a consumer's build membership or pin.
   */
  ContentClaim completePublication(
      UUID token, CanonicalContentProof proof, CancellationToken cancellation);

  /**
   * Same immutable object/source only; can add explicit metadata capability after a later parser
   * pass.
   */
  ContentClaim upgradeProof(
      UUID token, CanonicalContentProof proof, CancellationToken cancellation);

  /**
   * Source worker only, after conclusive nonpublication and retirement; never a facade cancellation
   * hook.
   */
  void releaseUnpublished(UUID token, CancellationToken cancellation);

  void markUncertain(UUID token, CancellationToken cancellation);

  /**
   * Authorizes only an unreferenced initial EN/DL/DS prospective object that is currently absent.
   * The existing actual worker must own the nonce; no retained/corrupt object or proof is reset.
   */
  default void authorizeInitialMissingContinuation(
      ContentWorkerPermit permit,
      MetadataContentRequest request,
      io.vibeminer.domain.content.MetadataContentProof original,
      CancellationToken cancellation) {
    throw new ContentClaimException(
        ContentClaimException.Code.UNSUPPORTED_REUSE,
        "Initial missing metadata continuation is unsupported");
  }

  /** Exclusive actual-worker admission. A passive reservation never grants this authority. */
  default ContentWorkerPermit claimWorker(UUID token, CancellationToken cancellation) {
    throw workerUnsupported();
  }

  default void markPublishing(ContentWorkerPermit permit, CancellationToken cancellation) {
    throw workerUnsupported();
  }

  default void recordVerifiedCompletion(
      ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken cancellation) {
    throw workerUnsupported();
  }

  default ContentClaim completePublication(
      ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken cancellation) {
    throw workerUnsupported();
  }

  default ContentClaim upgradeProof(
      ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken cancellation) {
    throw workerUnsupported();
  }

  /**
   * Called only after the actual source runnable has closed IO and can no longer publish.
   * Atomically retires this nonce; prospective proof preserves recovery and retained publication
   * never downgrades.
   */
  default void finishWorker(
      ContentWorkerPermit permit,
      ContentWorkerFinishOutcome outcome,
      CancellationToken cancellation) {
    throw workerUnsupported();
  }

  private static ContentClaimException workerUnsupported() {
    return new ContentClaimException(
        ContentClaimException.Code.UNSUPPORTED_REUSE,
        "Repository does not implement fenced publisher authority");
  }
}
