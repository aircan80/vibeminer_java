package io.vibeminer.application.lookup;

/**
 * Explicit independent ceilings. Metadata wire includes configuration and selected EN requests;
 * local root reads never consume wire budget. Decoded work bounds each retained-root operation.
 */
public record LookupBudgets(
    long maximumConfigurationBytes,
    long maximumMetadataWireBytes,
    int maximumMetadataRequests,
    long maximumMetadataDecodedBytes,
    int maximumIndexBytes,
    int maximumPageBytes,
    int maximumCandidates,
    long maximumRootEncodedBytes,
    long maximumRootDecodedBytes,
    int maximumHeaderBytes,
    int maximumChunks,
    int maximumEncodedChunkBytes,
    int maximumDecodedChunkBytes,
    long maximumDecodedWorkBytes,
    int maximumSelectedIds,
    int maximumMatches,
    long maximumRootRecords) {
  public LookupBudgets {
    if (maximumConfigurationBytes < 1
        || maximumMetadataWireBytes < maximumConfigurationBytes
        || maximumMetadataRequests < 1
        || maximumMetadataDecodedBytes < 1
        || maximumIndexBytes < 1
        || maximumPageBytes < 1
        || maximumCandidates < 1
        || maximumRootEncodedBytes < 1
        || maximumRootDecodedBytes < 1
        || maximumHeaderBytes < 1
        || maximumChunks < 1
        || maximumEncodedChunkBytes < 1
        || maximumDecodedChunkBytes < 1
        || maximumDecodedWorkBytes < 1
        || maximumSelectedIds < 1
        || maximumMatches < 1
        || maximumRootRecords < 1)
      throw new IllegalArgumentException(
          "Lookup budgets must be positive and cover configuration wire bytes");
  }

  public static LookupBudgets defaults() {
    return new LookupBudgets(
        1L << 20,
        8L << 20,
        64,
        32L << 20,
        8 << 20,
        64 << 10,
        32,
        128L << 20,
        256L << 20,
        1 << 20,
        4096,
        4 << 20,
        4 << 20,
        512L << 20,
        32,
        4096,
        20_000_000);
  }
}
