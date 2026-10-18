package io.vibeminer.infrastructure.encoding;

/** No partial or truncated reverse result accompanies a refusal. */
public final class EncodingLookupException extends RuntimeException {
  public enum Code {
    LIMIT
  }

  private final Code code;

  public EncodingLookupException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
