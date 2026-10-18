package io.vibeminer.application.lookup;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Explicit metadata planning, separate whole-root retention, then offline-only selected-ID lookup.
 */
public interface FileLookupService extends AutoCloseable {
  CompletionStage<RootPlan> planRoot(BuildSnapshot build, LookupBudgets budgets);

  CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan selection);

  CompletionStage<RootLookupResult> lookupOffline(
      BuildSnapshot build, Set<Long> ids, LookupBudgets budgets);

  @Override
  void close();
}
