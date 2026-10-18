package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Explicit original-metadata repair facade borrowing the single lazy content owner. */
final class LazyMetadataRepairService implements MetadataRepairService {
  private final LazyBootstrapService owner;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();

  LazyMetadataRepairService(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public CompletionStage<MetadataRepairPlan> prepareInstall(
      io.vibeminer.application.bootstrap.InstallPlan ordinary, LookupBudgets budgets) {
    return dispatch(runtime -> runtime.metadataRepair().prepareInstall(ordinary, budgets));
  }

  @Override
  public CompletionStage<MetadataRepairPlan> prepareRoot(
      io.vibeminer.application.lookup.RootSelectionPlan ordinary) {
    return dispatch(runtime -> runtime.metadataRepair().prepareRoot(ordinary));
  }

  @Override
  public CompletionStage<MetadataRepairResult> execute(MetadataRepairPlan issued) {
    return dispatch(runtime -> runtime.metadataRepair().execute(issued));
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) requests.forEach(request -> request.cancel(true));
  }

  private <T> CompletionStage<T> dispatch(
      Function<LazyBootstrapService.Resources, CompletionStage<T>> action) {
    if (closed.get())
      return CompletableFuture.failedFuture(
          new ContentClaimException(
              ContentClaimException.Code.INVALID_STATE, "Metadata repair facade is closed"));
    var request =
        owner
            .dispatchResources(
                runtime -> {
                  if (closed.get())
                    throw new ContentClaimException(
                        ContentClaimException.Code.INVALID_STATE,
                        "Metadata repair facade is closed");
                  return action.apply(runtime);
                },
                LazyMetadataRepairService::classify)
            .toCompletableFuture();
    requests.add(request);
    request.whenComplete((value, failure) -> requests.remove(request));
    if (closed.get()) request.cancel(true);
    return request;
  }

  private static Throwable classify(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof BootstrapException bootstrap) {
      var code =
          switch (bootstrap.reason()) {
            case CLOSED -> ContentClaimException.Code.INVALID_STATE;
            case CAPACITY -> ContentClaimException.Code.BUSY;
            default -> ContentClaimException.Code.STORAGE_UNAVAILABLE;
          };
      return new ContentClaimException(code, bootstrap.getMessage(), bootstrap);
    }
    return failure;
  }
}
