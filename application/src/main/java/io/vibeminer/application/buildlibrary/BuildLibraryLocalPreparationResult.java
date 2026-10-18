package io.vibeminer.application.buildlibrary;

import java.util.Objects;

/** Commit-aware bounded library-entry progress. Incomplete work has an explicit continuation. */
public record BuildLibraryLocalPreparationResult(
    LegacyBuildObservationImporter.Batch progress, int steps, boolean cancellationRequested) {
  public BuildLibraryLocalPreparationResult {
    Objects.requireNonNull(progress);
    if (steps < 0 || steps > 4 || (steps == 0 && progress.complete()))
      throw new IllegalArgumentException("Bounded actual local preparation required");
  }
}
