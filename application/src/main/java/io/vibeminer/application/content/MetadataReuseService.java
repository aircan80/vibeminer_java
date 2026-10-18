package io.vibeminer.application.content;

import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.util.concurrent.CompletionStage;

/** Explicit local-only reuse; construction and planning never attach a build or acquire bytes. */
public interface MetadataReuseService extends AutoCloseable {
  CompletionStage<MetadataReusePlan> planRetained(
      BuildSnapshot build, MetadataStructureAttestation.Kind kind, LookupBudgets budgets);

  CompletionStage<MetadataBuildAssociation> apply(MetadataReusePlan plan);

  @Override
  void close();
}
