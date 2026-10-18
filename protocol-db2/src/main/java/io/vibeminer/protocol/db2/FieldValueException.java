package io.vibeminer.protocol.db2;

/** A supported field cannot be decoded safely; no schema-level meaning is inferred. */
public final class FieldValueException extends Exception {
  public enum Reason {
    UNSUPPORTED_STORAGE,
    UNSUPPORTED_PARAMETERS,
    INVALID_DESCRIPTOR,
    INVALID_RECORD,
    INVALID_PALLET,
    INDEX_OUT_OF_RANGE
  }

  private final Reason reason;

  public FieldValueException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public FieldValueException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
