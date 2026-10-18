package io.vibeminer.application.discoveryhistory;

import java.util.Objects;

public final class DiscoveryHistoryException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    BUSY,
    CLOSED,
    NOT_FOUND,
    QUOTA_EXCEEDED,
    LIMIT_EXCEEDED,
    CORRUPT_OBSERVATION,
    IMMUTABLE_EVENT_CONFLICT,
    UNSUPPORTED_INTERPRETATION,
    STORAGE_UNAVAILABLE,
    OBSERVATION_NOT_SAVED
  }

  private final Code code;

  public DiscoveryHistoryException(Code code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code);
  }

  public DiscoveryHistoryException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
