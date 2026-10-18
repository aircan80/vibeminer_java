package io.vibeminer.protocol.tact.install;

/** No partial manifest is returned on parse failure. */
public final class InstallManifestException extends Exception {
    public enum Reason { MALFORMED, UNSUPPORTED, LIMIT, CANCELLED }
    private final Reason reason;
    public InstallManifestException(Reason reason,String message){super(message);this.reason=reason;}
    public Reason reason(){return reason;}
}
