package io.vibeminer.protocol.tact.blte;

import java.io.IOException;

/** A bounded full BLTE decode failed, or its full-source proof is not yet available. */
public final class StreamingDecodedBlteException extends IOException {
  public enum Reason {
    MALFORMED,
    UNSUPPORTED,
    LIMIT,
    INTEGRITY,
    TRUNCATED,
    TRAILING_BYTES,
    CANCELLED,
    INCOMPLETE,
    CLOSED
  }

  private final Reason reason;

  public StreamingDecodedBlteException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public StreamingDecodedBlteException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
