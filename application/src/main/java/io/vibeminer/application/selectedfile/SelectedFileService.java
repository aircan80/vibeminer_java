package io.vibeminer.application.selectedfile;

import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Explicit stages; no UI, transport, storage implementation or protocol parser dependencies. */
public interface SelectedFileService extends AutoCloseable {
  CompletionStage<CandidatePlan> planCandidates(
      BuildSnapshot build, FileVariantAssociation variant, SelectedFileBudgets budgets);

  CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice choice);

  CompletionStage<FileSelectionSearch> searchArchives(
      Loose404Permit permit, ArchiveSearchBudgets budgets);

  CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan plan);

  /** Explicit source demand; bulk generation cancellation affects only this consumer. */
  default CompletionStage<SelectedFileAssociation> retain(
      SelectedFilePlan plan, PrioritySourceScheduler.Demand demand) {
    return CompletableFuture.failedFuture(
        new SelectedFileException(
            SelectedFileException.Code.UNSUPPORTED, "Priority source admission is not supported"));
  }

  CompletionStage<SelectedFileAssociation> inspectOffline(
      SelectedFileDescriptor descriptor, SelectedFileBudgets budgets);

  /**
   * Offline convenience for callers that have a retained root variant but no current EKey/session.
   */
  default CompletionStage<SelectedFileAssociation> inspectOffline(
      FileVariantAssociation variant, SelectedFileBudgets budgets) {
    return CompletableFuture.failedFuture(
        new SelectedFileException(
            SelectedFileException.Code.UNSUPPORTED,
            "Offline variant lookup is not supported by this service"));
  }

  @Override
  void close();
}
