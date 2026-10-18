package io.vibeminer.infrastructure.buildopening;

/** Source-worker admission and elapsed-time failures, distinct from corrupt source bytes. */
public final class MetadataSourceException extends RuntimeException {
  public enum Reason {
    LIMIT,
    DEADLINE
  }

  private final Reason reason;

  public MetadataSourceException(Reason reason, String message) {
    this(reason, message, null);
  }

  public MetadataSourceException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = java.util.Objects.requireNonNull(reason);
  }

  public Reason reason() {
    return reason;
  }
}
