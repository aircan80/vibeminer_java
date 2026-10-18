package io.vibeminer.application.buildlibrary;

/**
 * Provided app-owned state, NOT inferred from remembered publication documents or current
 * readiness.
 */
public record BuildLibraryLocalFact(
    boolean hasBuildPreparationContext, boolean hasPayloadReference) {
  public boolean isLocal() {
    return hasBuildPreparationContext || hasPayloadReference;
  }

  public static BuildLibraryLocalFact observationOnly() {
    return new BuildLibraryLocalFact(false, false);
  }
}
