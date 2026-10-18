package io.vibeminer.application.encodedsource;

import java.util.Objects;

/** Source policy does not imply an FDID or inventory association. */
public final class EncodedSourceException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INVALID_PLAN,
    BUSY,
    CAPACITY_EXCEEDED,
    CLOSED,
    UNAVAILABLE,
    UNSUPPORTED,
    BUDGET_EXCEEDED,
    REPLAN_REQUIRED,
    RECOVERY_REQUIRED,
    INTEGRITY_FAILURE,
    MISSING_LOCAL,
    STORAGE_UNAVAILABLE
  }

  private final Code code;

  public EncodedSourceException(Code code, String message) {
    this(code, message, null);
  }

  public EncodedSourceException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
