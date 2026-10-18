package io.vibeminer.application.content;

import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.application.lookup.RootSelectionPlan;
import java.util.concurrent.CompletionStage;

/** Explicit positive-cost repair only; initial healthy original bytes require local reuse. */
public interface MetadataRepairService extends AutoCloseable {
  CompletionStage<MetadataRepairPlan> prepareInstall(
      InstallPlan ordinary, LookupBudgets localBudgets);

  CompletionStage<MetadataRepairPlan> prepareRoot(RootSelectionPlan ordinary);

  CompletionStage<MetadataBuildAssociation> execute(MetadataRepairPlan issued);

  @Override
  void close();
}
