package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.content.MetadataContentProof;

/** Full local original-object verification, including historical missing capabilities. */
public interface MetadataLocalVerificationGateway {
  /**
   * No HTTP, publisher, audit or metadata mutation. Replays exact retained configuration, physical
   * SHA/length, encoded integrity, full decoded CKey/length and supported grammar EOF. Returns
   * fresh attestations with the exact original alias/object/EKey/CKey/declarations, without
   * replacing original capabilities. Caller applies add-missing-only policy under its actual worker
   * permit.
   */
  MetadataContentProof verifyOriginal(
      MetadataContentRequest request,
      MetadataContentProof original,
      LookupBudgets budgets,
      CancellationToken cancellation);
}
