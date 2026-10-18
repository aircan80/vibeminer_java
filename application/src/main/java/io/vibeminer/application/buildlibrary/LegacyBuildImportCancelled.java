package io.vibeminer.application.buildlibrary;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Cancellation preserves completed local commits and the last safe continuation. */
public final class LegacyBuildImportCancelled extends CancellationException {
  private final LegacyBuildObservationImporter.Batch partialProgress;

  public LegacyBuildImportCancelled(LegacyBuildObservationImporter.Batch progress) {
    super("Legacy library import cancelled; committed progress is retained");
    partialProgress = Objects.requireNonNull(progress);
  }

  public LegacyBuildObservationImporter.Batch partialProgress() {
    return partialProgress;
  }
}
