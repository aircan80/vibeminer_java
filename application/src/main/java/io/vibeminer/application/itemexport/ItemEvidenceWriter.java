package io.vibeminer.application.itemexport;

import io.vibeminer.application.CancellationToken;

/**
 * Bounded explicit JSON publication. The exact absolute lexically normalized path authorizes
 * replacement at commit, even if it was absent at preview; it never enters document JSON.
 * documentCreatedAt is sampled before encoding; receipt time follows confirmed publication.
 * Cancellation after commit cannot erase confirmed success. No source revalidation or raw DTO dump.
 */
public interface ItemEvidenceWriter {
  /** Pure bounded platform lexical validation; performs no filesystem I/O or publication. */
  void validateDestination(String authorizedReplacementPath);

  ItemEvidenceReceipt publish(
      ItemEvidenceSnapshot snapshot,
      String authorizedReplacementPath,
      CancellationToken cancellation);
}
