package io.vibeminer.format.wow.root;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Requested memberships after complete supported-stream structural validation. This is NOT
 * CKey/authenticity verification. Caller must verify the complete source/chunk chain before
 * reporting verified membership or absence, and retain its exact build/source provenance.
 */
public final class SelectedRootMemberships {
  public enum StructuralCoverage {
    COMPLETE_SUPPORTED_STREAM
  }

  private final int headerSize, version, matchCount;
  private final long totalRecordCount, namedRecordCount, decodedBytesRead;
  private final Set<Long> requested;
  private final Map<Long, List<WowRootManifest.Entry>> entries;

  SelectedRootMemberships(
      int headerSize,
      int version,
      long totalRecordCount,
      long namedRecordCount,
      long decodedBytesRead,
      Set<Long> requested,
      Map<Long, List<WowRootManifest.Entry>> entries,
      int matchCount) {
    this.headerSize = headerSize;
    this.version = version;
    this.totalRecordCount = totalRecordCount;
    this.namedRecordCount = namedRecordCount;
    this.decodedBytesRead = decodedBytesRead;
    this.matchCount = matchCount;
    this.requested = Set.copyOf(requested);
    var copy = new LinkedHashMap<Long, List<WowRootManifest.Entry>>();
    entries.forEach((id, variants) -> copy.put(id, List.copyOf(variants)));
    this.entries = java.util.Collections.unmodifiableMap(copy);
  }

  public int headerSize() {
    return headerSize;
  }

  public int version() {
    return version;
  }

  public int matchCount() {
    return matchCount;
  }

  public long totalRecordCount() {
    return totalRecordCount;
  }

  public long namedRecordCount() {
    return namedRecordCount;
  }

  public long decodedBytesRead() {
    return decodedBytesRead;
  }

  public Set<Long> requestedFileDataIds() {
    return requested;
  }

  public StructuralCoverage structuralCoverage() {
    return StructuralCoverage.COMPLETE_SUPPORTED_STREAM;
  }

  public List<WowRootManifest.Entry> findFileDataId(long id) {
    if (!requested.contains(id))
      throw new IllegalArgumentException("FileDataID was not included in this selection");
    return entries.getOrDefault(id, List.of());
  }
}
