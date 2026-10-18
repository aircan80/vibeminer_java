package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;

/**
 * Mandatory durable repair gates supplied by the actual worker's application policy. There is no
 * no-op implementation: an executor must not promote physical bytes without a successful
 * prospective callback. Implementations bind the attempt to its worker permit and original issued
 * proof.
 */
public interface MetadataRepairObserver {
  /**
   * After exact original SHA/length, real EOF and fresh decoded-CKey verification, before physical
   * promotion. Root observations must not contain grammar yet; install observations contain its
   * completed grammar. A thrown failure forbids promotion.
   */
  void beforePromotion(MetadataRepairObservation observation, CancellationToken cancellation);

  /**
   * After the actual supported parser reaches EOF. The observation preserves the exact attempt,
   * original proof and fresh transfer facts from beforePromotion, adding only matching grammar.
   * Both kinds must invoke this callback. Root invokes it after its bounded second pass and the
   * observer records the ROOT-only audit STRUCTURE event. Install repeats its exact
   * already-complete prospective observation: the observer validates it without writing a STRUCTURE
   * event, and audit completion uses the full PROSPECTIVE fact.
   */
  void afterStructureValidation(
      MetadataRepairObservation observation, CancellationToken cancellation);
}
