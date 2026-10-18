package io.vibeminer.protocol.tact.archive;

/** No partial index lookup is published when this exception is raised. */
public final class ArchiveIndexException extends Exception {
  public enum Reason {
    MALFORMED,
    INTEGRITY,
    UNSUPPORTED,
    LIMIT,
    CANCELLED
  }

  private final Reason reason;

  public ArchiveIndexException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
