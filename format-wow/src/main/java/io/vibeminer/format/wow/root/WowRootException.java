package io.vibeminer.format.wow.root;

/** Complete supported root parsing failed; no partial membership is published. */
public final class WowRootException extends Exception {
    public enum Reason { MALFORMED, UNSUPPORTED, LIMIT, CANCELLED }
    private final Reason reason;
    public WowRootException(Reason reason,String message){super(message);this.reason=reason;}
    public Reason reason(){return reason;}
}
