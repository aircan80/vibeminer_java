package io.vibeminer.application.selectedfile;

import io.vibeminer.application.lookup.LookupBudgets;
import java.time.Duration;
import java.util.Objects;

/** Root/EN metadata and encoded-payload ceilings have separate accounting. */
public record SelectedFileBudgets(
    LookupBudgets lookup,
    long maximumEncodedFileBytes,
    int maximumPayloadRangeBytes,
    int maximumPayloadRequests,
    long maximumPayloadWireBytes,
    Duration maximumPayloadDuration) {
  public SelectedFileBudgets {
    Objects.requireNonNull(lookup);
    Objects.requireNonNull(maximumPayloadDuration);
    if (maximumEncodedFileBytes < 1
        || maximumPayloadRangeBytes < 1
        || maximumPayloadRangeBytes > 4 * 1024 * 1024
        || maximumPayloadRequests < 1
        || maximumPayloadWireBytes < 1
        || maximumPayloadDuration.isZero()
        || maximumPayloadDuration.isNegative()
        || maximumPayloadDuration.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Invalid selected-file budgets");
  }

  /** Fixed bounded root/EN metadata-stage ceiling, disclosed before explicit consent. */
  public Duration maximumMetadataDuration() {
    return Duration.ofMinutes(10);
  }

  public static SelectedFileBudgets defaults() {
    return new SelectedFileBudgets(
        LookupBudgets.defaults(), 128L << 20, 1 << 20, 512, 256L << 20, Duration.ofMinutes(5));
  }
}
