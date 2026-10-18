package io.vibeminer.format.wow.root;

/** Streaming selection failed; no partial memberships are returned. */
public final class StreamingRootException extends Exception {
  public enum Reason {
    MALFORMED,
    UNSUPPORTED,
    LIMIT,
    CANCELLED,
    IO
  }

  private final Reason reason;

  public StreamingRootException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public StreamingRootException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
