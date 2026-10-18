package io.vibeminer.application.retention;

import java.time.Instant;
import java.util.Objects;

/**
 * Advisory observation window, decoded metadata logical bytes and encoded physical/transfer bytes.
 * losesProtectionBytes is NOT safe reclaimable space and is zero when the scope is unpinned.
 */
public record RetentionMeasurement(
    String scopeId,
    Instant startedAt,
    Instant completedAt,
    ByteEstimate logicalDecodedBytes,
    ByteEstimate additionalEncodedBytes,
    long presentUniqueBytes,
    long sharedPresentBytes,
    long losesProtectionBytes,
    int missingRequirements,
    int unresolvedRequirements,
    int corruptRequirements) {
  public RetentionMeasurement {
    Objects.requireNonNull(scopeId);
    Objects.requireNonNull(startedAt);
    Objects.requireNonNull(completedAt);
    Objects.requireNonNull(logicalDecodedBytes);
    Objects.requireNonNull(additionalEncodedBytes);
    if (presentUniqueBytes < 0
        || sharedPresentBytes < 0
        || losesProtectionBytes < 0
        || missingRequirements < 0
        || unresolvedRequirements < 0
        || corruptRequirements < 0) throw new IllegalArgumentException("Invalid measurement");
  }
}
