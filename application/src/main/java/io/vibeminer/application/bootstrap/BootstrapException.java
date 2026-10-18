package io.vibeminer.application.bootstrap;

/** Explicit unsupported/unavailable/partial-local outcomes; none implies complete asset coverage. */
public final class BootstrapException extends RuntimeException {
    public enum Reason { UNSUPPORTED, UNAVAILABLE, LIMIT, INTEGRITY, MALFORMED, MISSING_LOCAL, CANCELLED, CAPACITY, CLOSED, STORAGE, TRANSPORT }
    private final Reason reason;
    public BootstrapException(Reason reason, String message) { super(message); this.reason = reason; }
    public BootstrapException(Reason reason, String message, Throwable cause) { super(message, cause); this.reason = reason; }
    public Reason reason() { return reason; }
}
