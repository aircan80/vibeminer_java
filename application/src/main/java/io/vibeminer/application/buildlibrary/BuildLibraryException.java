package io.vibeminer.application.buildlibrary;

import java.util.Objects;

public final class BuildLibraryException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    NOT_FOUND,
    BUSY,
    CLOSED,
    LIMIT_EXCEEDED,
    CORRUPT_LIBRARY,
    IMMUTABLE_REGISTRATION_CONFLICT,
    INVALID_CHECK_STATE,
    STORAGE_UNAVAILABLE
  }

  private final Code code;

  public BuildLibraryException(Code code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code);
  }

  public BuildLibraryException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
