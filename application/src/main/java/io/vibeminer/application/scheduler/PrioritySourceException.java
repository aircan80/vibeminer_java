package io.vibeminer.application.scheduler;

import java.util.Objects;

/** Admission failures do not imply a network or storage failure. */
public final class PrioritySourceException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    BUSY,
    CAPACITY_EXCEEDED,
    CLOSED,
    BULK_DISABLED
  }

  private final Code code;

  public PrioritySourceException(Code code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code);
  }

  public PrioritySourceException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
