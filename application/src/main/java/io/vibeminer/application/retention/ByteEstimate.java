package io.vibeminer.application.retention;

/** Known contribution and unknown representation count; unknown never means zero. */
public record ByteEstimate(long knownBytes, int unknownCount) {
  public ByteEstimate {
    if (knownBytes < 0 || unknownCount < 0) throw new IllegalArgumentException("Invalid estimate");
  }
}
