package io.vibeminer.application.itemnames;

/** Sparse extraction bounds only; both independently bounded retained-root replays are separate. */
public record SparseNameLimits(
    long maxEncodedBytes,
    int maxHeaderBytes,
    int maxChunks,
    int maxChunkEncodedBytes,
    int maxChunkDecodedBytes,
    int maxMetadataBytes,
    int maxSuffixBytes,
    int maxPhysicalRows,
    int maxCopyDeclarations,
    long maxRecordAreaBytes,
    int maxRecordBytes,
    int maxStringBytes,
    int maxPrefixBytes,
    long maxProjectedUtf8Bytes,
    long maxDecodedWorkBytes,
    int maxStagingRows) {
  public SparseNameLimits {
    if (maxEncodedBytes < 1
        || maxEncodedBytes > 16L << 20
        || maxHeaderBytes < 12
        || maxHeaderBytes > 131072
        || maxChunks < 1
        || maxChunks > 4096
        || maxChunkEncodedBytes < 1
        || maxChunkEncodedBytes > 4 << 20
        || maxChunkDecodedBytes < 1
        || maxChunkDecodedBytes > 4 << 20
        || maxMetadataBytes < 204
        || maxMetadataBytes > 131072
        || maxSuffixBytes < 1
        || maxSuffixBytes > 4 << 20
        || maxPhysicalRows < 1
        || maxPhysicalRows > 200000
        || maxCopyDeclarations < 0
        || maxCopyDeclarations > 1000
        || maxRecordAreaBytes < 1
        || maxRecordAreaBytes > 64L << 20
        || maxRecordBytes < 5
        || maxRecordBytes > 65535
        || maxStringBytes < 0
        || maxStringBytes > 65535
        || maxPrefixBytes < 5
        || maxPrefixBytes > 65535
        || maxProjectedUtf8Bytes < 0
        || maxProjectedUtf8Bytes > 64L << 20
        || maxDecodedWorkBytes < 1
        || maxDecodedWorkBytes > 256L << 20
        || maxStagingRows < 1
        || maxStagingRows > 500)
      throw new IllegalArgumentException("Invalid explicit bounded Sparse extraction limits");
  }
}
