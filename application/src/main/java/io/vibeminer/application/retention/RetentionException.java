package io.vibeminer.application.retention;

import java.util.Objects;

public final class RetentionException extends RuntimeException {
  public enum Code {
    NOT_FOUND,
    INTEGRITY_FAILURE,
    STORAGE_UNAVAILABLE,
    CAPACITY_EXCEEDED,
    CLOSED
  }

  private final Code code;

  public RetentionException(Code code, String message) {
    this(code, message, null);
  }

  public RetentionException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
