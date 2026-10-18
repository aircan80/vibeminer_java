package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.content.MetadataContentProof;

/**
 * Blocking local-only verification. No acquisition, publisher admission, association, pin or claim
 * mutation is authorized. Physical/config read leases protect verification; missing/corrupt bytes
 * fail without fetching. Construction of a plan never substitutes for these checks.
 */
public interface MetadataReuseGateway {
  void validateDeclaration(
      MetadataDeclaration declaration, LookupBudgets budgets, CancellationToken cancellation);

  /**
   * Replays actual A, preserving its exact original alias/evidence; no current EN claim follows.
   */
  MetadataContentProof replay(
      MetadataReusePlan plan, LookupBudgets budgets, CancellationToken cancellation);
}
