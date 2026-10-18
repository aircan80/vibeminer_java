package io.vibeminer.application.buildlibrary;

import java.util.Objects;

/** Import stops at the failing snapshot; earlier committed pairs remain resumable. */
public final class LegacyBuildImportException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INCOMPATIBLE_LEGACY,
    CORRUPT_LEGACY,
    LIMIT_EXCEEDED,
    STORAGE_UNAVAILABLE
  }

  private final Code code;
  private final LegacyBuildObservationImporter.Batch partialProgress;

  public LegacyBuildImportException(
      Code code,
      String message,
      LegacyBuildObservationImporter.Batch partialProgress,
      Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
    this.partialProgress = Objects.requireNonNull(partialProgress);
  }

  public Code code() {
    return code;
  }

  public LegacyBuildObservationImporter.Batch partialProgress() {
    return partialProgress;
  }
}
