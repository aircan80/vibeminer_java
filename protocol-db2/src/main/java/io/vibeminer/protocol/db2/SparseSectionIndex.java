package io.vibeminer.protocol.db2;

import java.util.List;
import java.util.Set;

/**
 * Raw suffix declarations only: no record data, repaired IDs, resolved copies or decoded strings.
 */
public record SparseSectionIndex(
    long suffixOffset,
    long copyTableOffset,
    long offsetMapOffset,
    long mapIdListOffset,
    long declaredEndOffset,
    List<Long> recordIds,
    List<Copy> copies,
    List<MapEntry> mapEntries,
    List<Long> mapIds,
    Set<Capability> capabilities) {
  public SparseSectionIndex {
    recordIds = List.copyOf(recordIds);
    copies = List.copyOf(copies);
    mapEntries = List.copyOf(mapEntries);
    mapIds = List.copyOf(mapIds);
    capabilities = Set.copyOf(capabilities);
  }

  public enum Capability {
    RAW_OFFSET_ORIGIN_UNVERIFIED,
    RECORD_BODY_NOT_INSPECTED,
    SCHEMA_SEMANTICS_UNVERIFIED
  }

  /** Offset remains opaque: no absolute/relative origin or row correspondence is inferred. */
  public record MapEntry(long rawOffset, int size) {}

  /** Raw target/source pair, including forward, cross-section, self or repeated references. */
  public record Copy(long targetId, long sourceId) {}
}
