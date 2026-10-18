package io.vibeminer.application.selectedfile;

import java.time.Duration;
import java.util.Objects;

/** Explicit metadata-search consent, independent from payload consent. */
public record ArchiveSearchBudgets(
    int maximumArchives,
    long maximumWireBytes,
    int maximumRequests,
    Duration maximumDuration,
    long maximumArchiveBytes) {
  public ArchiveSearchBudgets {
    Objects.requireNonNull(maximumDuration);
    if (maximumArchives < 1
        || maximumWireBytes < 1
        || maximumRequests < 1
        || maximumArchiveBytes < 1
        || maximumDuration.isZero()
        || maximumDuration.isNegative()
        || maximumDuration.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Invalid archive search budgets");
  }

  public static ArchiveSearchBudgets defaults() {
    return new ArchiveSearchBudgets(512, 16L << 20, 2048, Duration.ofMinutes(5), 8L << 30);
  }
}
