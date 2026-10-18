package io.vibeminer.application.lookup;

public final class FileLookupException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INVALID_PLAN,
    MISSING_LOCAL,
    INTEGRITY_FAILURE,
    UNAVAILABLE,
    UNSUPPORTED,
    CAPACITY_EXCEEDED,
    BUDGET_EXCEEDED,
    STORAGE_FAILURE,
    CLOSED
  }

  private final Code code;

  public FileLookupException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public FileLookupException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
