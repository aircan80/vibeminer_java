package io.vibeminer.protocol.tact.blte;

import java.io.IOException;

/** Encoded verification failed or has not yet consumed actual source EOF. */
public final class EncodedBlteException extends IOException {
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

  public EncodedBlteException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
