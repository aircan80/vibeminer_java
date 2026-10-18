package io.vibeminer.application.itemnames;

public final class ItemNamesException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    NOT_READY,
    STALE_CURSOR,
    MISSING_LOCAL,
    CORRUPT,
    INCOMPATIBLE,
    UNSUPPORTED,
    LIMIT_EXCEEDED,
    BUSY,
    STORAGE_UNAVAILABLE,
    CLOSED
  }

  private final Code code;

  public ItemNamesException(Code code, String message) {
    super(message);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public ItemNamesException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
