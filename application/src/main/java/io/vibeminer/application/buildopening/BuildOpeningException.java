package io.vibeminer.application.buildopening;

import java.util.Objects;

public final class BuildOpeningException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    BUSY,
    CLOSED,
    NOT_READY,
    MISSING_METADATA,
    CORRUPT_METADATA,
    UNSUPPORTED_METADATA,
    UNAVAILABLE,
    LIMIT_EXCEEDED,
    STORAGE_UNAVAILABLE
  }

  private final Code code;

  public BuildOpeningException(Code code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code);
  }

  public BuildOpeningException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
