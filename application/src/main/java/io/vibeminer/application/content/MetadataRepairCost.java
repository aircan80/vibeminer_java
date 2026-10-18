package io.vibeminer.application.content;

import java.time.Duration;
import java.util.Objects;

/**
 * Positive payload consent ceilings for one original-representation repair, excluding HEAD work.
 */
public record MetadataRepairCost(
    long encodedTransferBytes,
    long maximumAdditionalPhysicalBytes,
    int maximumPayloadRequests,
    long maximumDecodedWorkBytes,
    Duration maximumPayloadDuration) {
  public MetadataRepairCost {
    Objects.requireNonNull(maximumPayloadDuration);
    if (encodedTransferBytes < 1
        || maximumAdditionalPhysicalBytes < 1
        || maximumPayloadRequests < 1
        || maximumDecodedWorkBytes < 1
        || maximumPayloadDuration.isNegative()
        || maximumPayloadDuration.isZero()
        || maximumPayloadDuration.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Positive bounded original-metadata repair cost required");
  }
}
