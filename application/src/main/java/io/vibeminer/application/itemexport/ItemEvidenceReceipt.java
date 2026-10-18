package io.vibeminer.application.itemexport;

import java.time.Instant;
import java.util.Objects;

/** Confirmed publication only; committedAt is a wall observation, not a causal clock guarantee. */
public record ItemEvidenceReceipt(long encodedUtf8Bytes, String sha256, Instant committedAt) {
  public static final long MAXIMUM_UTF8_BYTES = 2L << 20;

  public ItemEvidenceReceipt {
    Objects.requireNonNull(sha256);
    Objects.requireNonNull(committedAt);
    if (encodedUtf8Bytes < 1
        || encodedUtf8Bytes > MAXIMUM_UTF8_BYTES
        || !sha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Bounded confirmed output and exact SHA256 required");
  }
}
