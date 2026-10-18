package io.vibeminer.protocol.tact.blte;

/** A complete decode failed; no partial bytes are returned. */
public final class BlteException extends Exception {
    public enum Reason { MALFORMED, INTEGRITY, UNSUPPORTED, LIMIT, CANCELLED }
    private final Reason reason;
    public BlteException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
