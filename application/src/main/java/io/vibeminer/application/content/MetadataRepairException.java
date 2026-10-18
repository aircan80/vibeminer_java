package io.vibeminer.application.content;

import java.util.Objects;

/**
 * Repair planning/issuance outcomes; cancellation and canonical exclusions keep their own types.
 */
public final class MetadataRepairException extends RuntimeException {
  public enum Code {
    INVALID_PLAN,
    UNAVAILABLE,
    UNSUPPORTED,
    LIMIT,
    INTEGRITY,
    CLOSED
  }

  private final Code code;

  public MetadataRepairException(Code code, String message) {
    this(code, message, null);
  }

  public MetadataRepairException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
