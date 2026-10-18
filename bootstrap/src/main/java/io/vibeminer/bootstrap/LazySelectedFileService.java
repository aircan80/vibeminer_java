package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.*;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Selected-file facade borrowing the install/root lazy runtime and its single resource owner. */
final class LazySelectedFileService implements SelectedFileService {
  private final LazyBootstrapService owner;

  LazySelectedFileService(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public CompletionStage<CandidatePlan> planCandidates(
      BuildSnapshot build, FileVariantAssociation variant, SelectedFileBudgets budgets) {
    return owner.dispatchResources(
        runtime -> runtime.selectedFiles().planCandidates(build, variant, budgets),
        LazySelectedFileService::classify);
  }

  @Override
  public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice choice) {
    return owner.dispatchResources(
        runtime -> runtime.selectedFiles().probeLoose(choice), LazySelectedFileService::classify);
  }

  @Override
  public CompletionStage<FileSelectionSearch> searchArchives(
      Loose404Permit permit, ArchiveSearchBudgets budgets) {
    return owner.dispatchResources(
        runtime -> runtime.selectedFiles().searchArchives(permit, budgets),
        LazySelectedFileService::classify);
  }

  @Override
  public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan plan) {
    return owner.dispatchResources(
        runtime -> runtime.selectedFiles().retain(plan), LazySelectedFileService::classify);
  }

  @Override
  public CompletionStage<SelectedFileAssociation> inspectOffline(
      SelectedFileDescriptor descriptor, SelectedFileBudgets budgets) {
    return owner.dispatchResources(
        runtime -> runtime.selectedFiles().inspectOffline(descriptor, budgets),
        LazySelectedFileService::classify);
  }

  /** One idempotent owner manages all content services; delegate resources remain borrowed. */
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
            case CLOSED -> SelectedFileException.Code.CLOSED;
            case CAPACITY -> SelectedFileException.Code.CAPACITY_EXCEEDED;
            default -> SelectedFileException.Code.STORAGE_UNAVAILABLE;
          };
      return new SelectedFileException(code, bootstrap.getMessage(), bootstrap);
    }
    return failure;
  }
}
