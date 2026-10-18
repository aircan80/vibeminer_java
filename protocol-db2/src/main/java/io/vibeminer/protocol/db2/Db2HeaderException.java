package io.vibeminer.protocol.db2;

/** Header inspection failed; this type makes no statement about row decoding. */
public final class Db2HeaderException extends Exception {
    public enum Reason { MALFORMED, UNSUPPORTED, LIMIT, CANCELLED }
    private final Reason reason;
    public Db2HeaderException(Reason reason,String message){super(message);this.reason=reason;}
    public Reason reason(){return reason;}
}
