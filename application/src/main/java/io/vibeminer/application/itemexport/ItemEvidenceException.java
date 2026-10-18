package io.vibeminer.application.itemexport;

import java.util.Objects;

/** Typed snapshot, admission and publication failures; uncertainty never implies rollback. */
public final class ItemEvidenceException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    BUSY,
    CLOSED,
    STALE_SELECTION,
    MISSING_LOCAL,
    CORRUPT,
    UNSUPPORTED,
    LIMIT_EXCEEDED,
    STORAGE_UNAVAILABLE,
    DESTINATION_REFUSED,
    ATOMIC_PUBLICATION_UNSUPPORTED,
    COMMIT_UNCERTAIN
  }

  private final Code code;

  public ItemEvidenceException(Code code, String message) {
    super(message);
    this.code = Objects.requireNonNull(code);
  }

  public ItemEvidenceException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code);
  }

  public Code code() {
    return code;
  }
}
