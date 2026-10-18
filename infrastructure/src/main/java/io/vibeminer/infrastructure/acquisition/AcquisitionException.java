package io.vibeminer.infrastructure.acquisition;

import java.util.OptionalInt;

/** Unavailability does not imply that a file or research record was removed. */
public final class AcquisitionException extends RuntimeException {
  public enum Reason {
    UNAVAILABLE,
    TRANSPORT,
    INTEGRITY,
    LIMIT,
    CANCELLED
  }

  private final Reason reason;
  private final OptionalInt httpStatus;

  public AcquisitionException(Reason reason, String message) {
    super(message);
    this.reason = reason;
    this.httpStatus = OptionalInt.empty();
  }

  public AcquisitionException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.httpStatus = OptionalInt.empty();
  }

  public AcquisitionException(Reason reason, String message, int httpStatus) {
    super(message);
    this.reason = reason;
    this.httpStatus = validatedStatus(httpStatus);
  }

  public AcquisitionException(Reason reason, String message, Throwable cause, int httpStatus) {
    super(message, cause);
    this.reason = reason;
    this.httpStatus = validatedStatus(httpStatus);
  }

  public Reason reason() {
    return reason;
  }

  /** Present only when a concrete HTTP response status is known; never parsed from text. */
  public OptionalInt httpStatus() {
    return httpStatus;
  }

  private static OptionalInt validatedStatus(int status) {
    if (status < 100 || status > 599)
      throw new IllegalArgumentException("HTTP status must be between 100 and 599");
    return OptionalInt.of(status);
  }
}
