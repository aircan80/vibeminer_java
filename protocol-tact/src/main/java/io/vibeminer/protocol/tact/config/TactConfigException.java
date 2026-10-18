package io.vibeminer.protocol.tact.config;

/** Invalid or unsupported verified configuration; no partial model is returned. */
public final class TactConfigException extends Exception {
    public enum Reason { MALFORMED, INTEGRITY, LIMIT, CANCELLED, MISSING_FIELD, UNSUPPORTED }
    private final Reason reason;
    public TactConfigException(Reason reason,String message){super(message);this.reason=reason;}
    public Reason reason(){return reason;}
}
