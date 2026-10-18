package io.vibeminer.application.selectedfile;

import java.util.Objects;

/** Typed selected-file policy, availability and persistence outcomes. */
public final class SelectedFileException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INVALID_PLAN,
    BUSY,
    CAPACITY_EXCEEDED,
    CLOSED,
    CANCELLED,
    UNAVAILABLE,
    UNSUPPORTED,
    BUDGET_EXCEEDED,
    INCOMPLETE_SEARCH,
    REPLAN_REQUIRED,
    RECOVERY_REQUIRED,
    INTEGRITY_FAILURE,
    MISSING_LOCAL,
    STORAGE_UNAVAILABLE,
    NOT_FOUND
  }

  private final Code code;

  public SelectedFileException(Code code, String message) {
    this(code, message, null);
  }

  public SelectedFileException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
