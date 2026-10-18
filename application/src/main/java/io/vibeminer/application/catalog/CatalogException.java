package io.vibeminer.application.catalog;

import java.util.Objects;

/** Stable catalog failures; missing local coverage is an ordinary empty verified lookup. */
public final class CatalogException extends RuntimeException {
    public enum Code { STORAGE_UNAVAILABLE, CORRUPT_METADATA, IMMUTABLE_CONFLICT, MISSING_OBJECT, INTEGRITY_FAILURE, UNKNOWN_SNAPSHOT, INVALID_EVIDENCE }
    private final Code code;
    public CatalogException(Code code, String message) { this(code, message, null); }
    public CatalogException(Code code, String message, Throwable cause) { super(message, cause); this.code = Objects.requireNonNull(code); }
    public Code code() { return code; }
}
