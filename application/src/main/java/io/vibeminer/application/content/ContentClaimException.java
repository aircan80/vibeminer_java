package io.vibeminer.application.content;

import java.util.Objects;

/** Canonical exclusion/recovery outcomes are distinct from absence and current readability. */
public final class ContentClaimException extends RuntimeException {
  public enum Code {
    LEGACY_CONFLICT,
    BUSY,
    REPLAN_REQUIRED,
    RECOVERY_REQUIRED,
    UNSUPPORTED_REUSE,
    INVALID_REQUEST,
    INVALID_STATE,
    INTEGRITY_FAILURE,
    MISSING_LOCAL,
    STORAGE_UNAVAILABLE
  }

  private final Code code;

  public ContentClaimException(Code code, String message) {
    this(code, message, null);
  }

  public ContentClaimException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
