package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.content.CanonicalContentProof;
import io.vibeminer.domain.content.ContentClaim;
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
}
