package io.vibeminer.application;

/** Typed adapter failure; safe messages do not require parsing OS-specific exceptions. */
public final class ObjectStoreException extends RuntimeException {
    public enum Code { INTEGRITY_FAILURE, LIMIT_EXCEEDED, IO_FAILURE, STORE_IN_USE, CLOSED, BUSY, MISSING_OBJECT }
    private final Code code;
    public ObjectStoreException(Code code, String message) { super(message); this.code = code; }
    public ObjectStoreException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }
}
