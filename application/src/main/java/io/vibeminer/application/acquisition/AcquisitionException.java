package io.vibeminer.application.acquisition;

import java.util.Objects;

public final class AcquisitionException extends RuntimeException {
    public enum Code { CAPACITY_EXCEEDED, SOURCE_FAILURE, INVALID_VERIFIED_OBJECT, CLOSED }
    private final Code code;
    public AcquisitionException(Code code, String message) { super(message); this.code = Objects.requireNonNull(code); }
    public AcquisitionException(Code code, String message, Throwable cause) { super(message, cause); this.code = Objects.requireNonNull(code); }
    public Code code() { return code; }
}
