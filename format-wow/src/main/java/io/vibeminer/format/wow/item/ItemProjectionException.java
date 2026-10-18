package io.vibeminer.format.wow.item;

/**
 * Pure projection failure. Earlier sink callbacks, if any, are provisional and must be discarded.
 */
public final class ItemProjectionException extends Exception {
  public enum Reason {
    INCOMPATIBLE,
    UNSUPPORTED,
    MALFORMED,
    LIMIT,
    CANCELLED
  }

  private final Reason reason;

  public ItemProjectionException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public ItemProjectionException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
