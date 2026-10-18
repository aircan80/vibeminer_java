package io.vibeminer.application.inventory;

import java.util.*;

/**
 * Latest local preparation stage, not payload transfer or full-build retention progress. Counts
 * describe cumulative exact origin references across this preparation, never per-stage estimates.
 * Unknown totals stay empty; a known total must be fully processed before terminal completion.
 */
public record BuildInventoryPreparationProgress(
    Stage stage,
    OptionalLong processedReferences,
    OptionalLong totalReferences,
    Optional<BuildInventoryPreparationException.Code> failure) {
  public enum Stage {
    QUEUED,
    REVALIDATING_ADMISSION,
    VERIFYING_SOURCES,
    PREPARING_INVENTORY,
    ISSUING_TARGET,
    COMPLETE,
    FAILED,
    CANCELLED
  }

  public BuildInventoryPreparationProgress {
    Objects.requireNonNull(stage);
    Objects.requireNonNull(processedReferences);
    Objects.requireNonNull(totalReferences);
    Objects.requireNonNull(failure);
    if (processedReferences.isPresent() && processedReferences.getAsLong() < 0
        || totalReferences.isPresent()
            && (processedReferences.isEmpty()
                || totalReferences.getAsLong() < processedReferences.getAsLong())
        || stage == Stage.COMPLETE
            && totalReferences.isPresent()
            && processedReferences.getAsLong() != totalReferences.getAsLong()
        || (stage == Stage.FAILED) != failure.isPresent())
      throw new IllegalArgumentException(
          "Honest optional local-reference counts and typed failure required");
  }

  public static BuildInventoryPreparationProgress initial() {
    return new BuildInventoryPreparationProgress(
        Stage.QUEUED, OptionalLong.empty(), OptionalLong.empty(), Optional.empty());
  }
}
