package io.vibeminer.protocol.db2;

import java.util.List;

/** Raw section ID/copy metadata only; no record bytes, resolved copies, schema or names. */
public record FixedSectionIndex(
    long sectionOffset,
    long recordSize,
    long stringTableOffset,
    long idListOffset,
    long copyTableOffset,
    long declaredEndOffset,
    List<Long> recordIds,
    List<Copy> copies) {
  public FixedSectionIndex {
    recordIds = List.copyOf(recordIds);
    copies = List.copyOf(copies);
    if (sectionOffset < 0
        || recordSize < 0
        || stringTableOffset < sectionOffset
        || idListOffset < stringTableOffset
        || copyTableOffset < idListOffset
        || declaredEndOffset < copyTableOffset)
      throw new IllegalArgumentException("Invalid section index geometry");
    Math.addExact(sectionOffset, Math.multiplyExact(recordSize, (long) recordIds.size()));
  }

  /** File-relative position, not a decoded record. The input section bytes are never retained. */
  public long recordOffset(int rowIndex) {
    if (rowIndex < 0 || rowIndex >= recordIds.size()) throw new IndexOutOfBoundsException(rowIndex);
    return Math.addExact(sectionOffset, Math.multiplyExact(recordSize, (long) rowIndex));
  }

  /** Exact unsigned on-disk pair. Source may be forward, absent here, or in another section. */
  public record Copy(long targetId, long sourceId) {}
}
