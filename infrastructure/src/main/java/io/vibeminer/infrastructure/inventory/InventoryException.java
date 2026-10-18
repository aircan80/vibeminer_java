package io.vibeminer.infrastructure.inventory;

/** A known inventory fact cannot be admitted by the current retention capability. */
public final class InventoryException extends IllegalStateException {
  public enum Code {
    UNSUPPORTED_ZERO_LENGTH_ENCODING
  }

  private final Code code;

  public InventoryException(Code code, String message) {
    super(message);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
