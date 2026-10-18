package io.vibeminer.application.inventory;

import java.util.Objects;

/** Typed orchestration or backend refusal; unsupported inventory is never an empty complete set. */
public final class BuildInventoryPreparationException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    BUSY,
    CLOSED,
    NOT_READY,
    STALE_CONTEXT,
    UNSUPPORTED,
    LIMIT_EXCEEDED,
    CORRUPT_INVENTORY,
    STORAGE_UNAVAILABLE
  }

  private final Code code;

  public BuildInventoryPreparationException(Code code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code);
  }

  public BuildInventoryPreparationException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
