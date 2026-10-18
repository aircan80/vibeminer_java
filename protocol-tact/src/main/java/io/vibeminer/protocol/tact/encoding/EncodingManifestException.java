package io.vibeminer.protocol.tact.encoding;

/** A complete supported EN parse failed; no partial lookup table is published. */
public final class EncodingManifestException extends Exception {
    public enum Reason { MALFORMED, UNSUPPORTED, INTEGRITY, LIMIT, CANCELLED }
    private final Reason reason;
    public EncodingManifestException(Reason reason,String message){super(message);this.reason=reason;}
    public Reason reason(){return reason;}
}
