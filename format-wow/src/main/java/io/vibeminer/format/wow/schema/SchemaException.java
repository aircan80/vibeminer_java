package io.vibeminer.format.wow.schema;

public final class SchemaException extends Exception {
  public enum Reason {
    MALFORMED,
    LIMIT,
    UNSUPPORTED,
    CANCELLED
  }

  private final Reason reason;

  public SchemaException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
