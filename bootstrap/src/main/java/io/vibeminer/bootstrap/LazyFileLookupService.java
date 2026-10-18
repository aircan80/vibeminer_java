package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Root facade over the same lazy resource owner used by install-list work. */
final class LazyFileLookupService implements FileLookupService {
  private final LazyBootstrapService owner;

  LazyFileLookupService(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public CompletionStage<RootPlan> planRoot(BuildSnapshot build, LookupBudgets budgets) {
    return owner.dispatchResources(
        runtime -> runtime.lookup().planRoot(build, budgets), LazyFileLookupService::classify);
  }

  @Override
  public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan selection) {
    return owner.dispatchResources(
        runtime -> runtime.lookup().retainRoot(selection), LazyFileLookupService::classify);
  }

  @Override
  public CompletionStage<RootLookupResult> lookupOffline(
      BuildSnapshot build, Set<Long> ids, LookupBudgets budgets) {
    var selected = Set.copyOf(ids);
    return owner.dispatchResources(
        runtime -> runtime.lookup().lookupOffline(build, selected, budgets),
        LazyFileLookupService::classify);
  }

  /** Both facades share one idempotent lifecycle; closing either closes their common runtime. */
  @Override
  public void close() {
    owner.close();
  }

  private static Throwable classify(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof BootstrapException bootstrap) {
      var code =
          switch (bootstrap.reason()) {
            case CLOSED -> FileLookupException.Code.CLOSED;
            case CAPACITY -> FileLookupException.Code.CAPACITY_EXCEEDED;
            default -> FileLookupException.Code.STORAGE_FAILURE;
          };
      return new FileLookupException(code, bootstrap.getMessage(), bootstrap);
    }
    return failure;
  }
}
