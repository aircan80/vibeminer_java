package io.vibeminer.application.selectedfile;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.*;
import java.util.concurrent.CompletionStage;

/** Explicit stages; no UI, transport, storage implementation or protocol parser dependencies. */
public interface SelectedFileService extends AutoCloseable {
  CompletionStage<CandidatePlan> planCandidates(
      BuildSnapshot build, FileVariantAssociation variant, SelectedFileBudgets budgets);

  CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice choice);

  CompletionStage<FileSelectionSearch> searchArchives(
      Loose404Permit permit, ArchiveSearchBudgets budgets);

  CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan plan);

  CompletionStage<SelectedFileAssociation> inspectOffline(SelectedFileDescriptor descriptor, SelectedFileBudgets budgets);

  @Override
  void close();
}
