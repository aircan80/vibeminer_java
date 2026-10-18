package io.vibeminer.application.buildoverview;

public final class BuildOverviewException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    BUSY,
    CLOSED,
    CORRUPT_RESULT,
    STORAGE,
    LIMIT
  }

  private final Code code;

  public BuildOverviewException(Code code, String message) {
    super(message);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public BuildOverviewException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
