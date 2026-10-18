package io.vibeminer.protocol.db2;

/** Scoped sparse metadata or explicit record-slice failure; no full-table absence claim. */
public final class SparseRecordException extends Exception {
  public enum Reason {
    MALFORMED,
    UNSUPPORTED,
    LIMIT,
    CANCELLED
  }

  private final Reason reason;

  public SparseRecordException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
