package io.vibeminer.application;

/** Stable failures for presentation; technical causes remain diagnostic context. */
public final class WorkspaceException extends RuntimeException {
    public enum Code { STORAGE_UNAVAILABLE, CORRUPT_METADATA, NOT_FOUND, LIMIT_REACHED }
    private final Code code;
    public WorkspaceException(Code code, String message) { this(code, message, null); }
    public WorkspaceException(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }
}
