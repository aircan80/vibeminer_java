package io.vibeminer.application.item;

/** Explicit local work bounds; none authorize transport or repair. */
public record ItemProjectionLimits(
    long maxEncodedBytes,
    int maxSectionBytes,
    int maxPhysicalRows,
    int maxCopyDeclarations,
    int maxMetadataBytes,
    int maxPalletBytes) {
  public ItemProjectionLimits {
    if (maxEncodedBytes < 1
        || maxSectionBytes < 1
        || maxSectionBytes > 4 * 1024 * 1024
        || maxPhysicalRows < 1
        || maxCopyDeclarations < 0
        || maxMetadataBytes < 1
        || maxPalletBytes < 0)
      throw new IllegalArgumentException("Invalid local projection bounds");
  }
}
