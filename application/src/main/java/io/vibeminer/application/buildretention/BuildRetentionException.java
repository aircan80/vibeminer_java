package io.vibeminer.application.buildretention;

public final class BuildRetentionException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    NOT_FOUND,
    NOT_READY,
    STALE_GENERATION,
    BUSY,
    LIMIT_EXCEEDED,
    QUOTA_EXCEEDED,
    CORRUPT_STATE,
    STORAGE_UNAVAILABLE
  }

  private final Code code;

  public BuildRetentionException(Code code, String message) {
    super(message);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public BuildRetentionException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
