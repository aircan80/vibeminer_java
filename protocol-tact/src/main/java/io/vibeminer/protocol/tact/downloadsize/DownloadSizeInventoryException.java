package io.vibeminer.protocol.tact.downloadsize;

/** No structural completion exists after this failure; callbacks remain provisional. */
public final class DownloadSizeInventoryException extends Exception {
  public enum Reason {
    MALFORMED,
    UNSUPPORTED,
    LIMIT,
    CANCELLED,
    IO
  }

  private final Reason reason;

  public DownloadSizeInventoryException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public DownloadSizeInventoryException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
