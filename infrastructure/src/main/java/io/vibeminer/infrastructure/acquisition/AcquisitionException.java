package io.vibeminer.infrastructure.acquisition;

/** Unavailability does not imply that a file or research record was removed. */
public final class AcquisitionException extends RuntimeException {
    public enum Reason { UNAVAILABLE, TRANSPORT, INTEGRITY, LIMIT, CANCELLED }
    private final Reason reason;
    public AcquisitionException(Reason reason, String message) { super(message); this.reason = reason; }
    public AcquisitionException(Reason reason, String message, Throwable cause) { super(message, cause); this.reason = reason; }
    public Reason reason() { return reason; }
}
