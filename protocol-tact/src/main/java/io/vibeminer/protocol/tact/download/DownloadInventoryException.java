package io.vibeminer.protocol.tact.download;

/** Structural failure; emitted rows remain provisional and establish no payload authenticity. */
public final class DownloadInventoryException extends Exception {
  public enum Reason {
    MALFORMED,
    UNSUPPORTED,
    LIMIT,
    CANCELLED,
    IO
  }

  private final Reason reason;

  public DownloadInventoryException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public DownloadInventoryException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
