package io.vibeminer.application.discoveryhistory;

/** Initial product quotas; raw bytes count unique shared metadata bodies, not total disk use. */
public record DiscoveryHistoryLimits(
    long maximumEvents,
    long maximumBuildReferences,
    long maximumUniqueDocumentBytes,
    int maximumBuildsPerEvent) {
  public DiscoveryHistoryLimits {
    if (maximumEvents < 1
        || maximumEvents > 10000
        || maximumBuildReferences < 1
        || maximumBuildReferences > 100000
        || maximumUniqueDocumentBytes < 1
        || maximumUniqueDocumentBytes > (256L << 20)
        || maximumBuildsPerEvent < 1
        || maximumBuildsPerEvent > 64)
      throw new IllegalArgumentException("History limits exceed initial supported bounds");
  }

  public static DiscoveryHistoryLimits defaults() {
    return new DiscoveryHistoryLimits(10000, 100000, 256L << 20, 64);
  }
}
