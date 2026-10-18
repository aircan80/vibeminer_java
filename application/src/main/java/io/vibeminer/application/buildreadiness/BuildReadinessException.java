package io.vibeminer.application.buildreadiness;

public final class BuildReadinessException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    BUSY,
    CLOSED,
    CORRUPT_RESULT,
    STORAGE,
    LIMIT
  }

  private final Code code;

  public BuildReadinessException(Code code, String message) {
    super(message);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public BuildReadinessException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
