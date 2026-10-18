package io.vibeminer.protocol.tact.encoding;

/** No completion on failure; previously delivered rows remain provisional. */
public final class FullEncodingInventoryException extends Exception {
  public enum Reason {
    MALFORMED,
    UNSUPPORTED,
    INTEGRITY,
    LIMIT,
    CANCELLED,
    IO
  }

  private final Reason reason;

  public FullEncodingInventoryException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public FullEncodingInventoryException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
