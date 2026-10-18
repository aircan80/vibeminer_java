package io.vibeminer.format.wow.item;

/** Earlier row callbacks are provisional and must be discarded if completion fails. */
public final class SparseNameProjectionException extends Exception {
  public enum Reason {
    INCOMPATIBLE,
    UNSUPPORTED,
    MALFORMED,
    LIMIT,
    CANCELLED,
    IO_FAILURE
  }

  private final Reason reason;

  public SparseNameProjectionException(Reason reason, String message) {
    super(message);
    this.reason = java.util.Objects.requireNonNull(reason);
  }

  public SparseNameProjectionException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = java.util.Objects.requireNonNull(reason);
  }

  public Reason reason() {
    return reason;
  }
}
