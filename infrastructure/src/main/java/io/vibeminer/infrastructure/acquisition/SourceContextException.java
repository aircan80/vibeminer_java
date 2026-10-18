package io.vibeminer.infrastructure.acquisition;

import java.util.Objects;

/** Captured-source interpretation failure, independent of a specific application use case. */
public final class SourceContextException extends RuntimeException {
    public enum Reason {
        UNSUPPORTED,
        MALFORMED,
        INTEGRITY
    }

    private final Reason reason;

    public SourceContextException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    public SourceContextException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason reason() {
        return reason;
    }
}
