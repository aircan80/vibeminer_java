package io.vibeminer.protocol.db2;

/** Invalid bounded integer extraction; no record data is returned. */
public final class BitFieldException extends Exception {
  public enum Reason {
    INVALID_WIDTH,
    INVALID_RECORD_SLICE,
    FIELD_OUTSIDE_RECORD
  }

  private final Reason reason;

  public BitFieldException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
