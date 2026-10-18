package io.vibeminer.protocol.db2;

import static io.vibeminer.protocol.db2.Db2HeaderException.Reason.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Bounded sparse suffix metadata, not source authentication or record decoding. Physical order:
 * DBCD e732093f8864240fc5884bd1bba6b02f3dfc0d56, DBCD.IO/Readers/WDC5Reader.cs 403–465,
 * Common/DBStructs.cs SparseEntry (MIT). That reader accumulates Size rather than using Offset;
 * this reference does not establish the raw map offset origin, so offsets remain opaque.
 */
public final class SparseSectionIndexParser {
  public record Limits(int maxSuffixBytes, int maxRecordIds, int maxCopies, int maxMapEntries) {
    public Limits {
      if (maxSuffixBytes < 0 || maxRecordIds < 0 || maxCopies < 0 || maxMapEntries < 0)
        throw new IllegalArgumentException("Negative sparse suffix bounds");
    }
  }

  /** Input is exactly [offsetRecordsEndOffset, declaredEndOffset), not the full section body. */
  public SparseSectionIndex parse(
      byte[] suffix,
      Wdc5Header header,
      Wdc5Metadata.Section section,
      Limits limits,
      BooleanSupplier cancelled)
      throws Db2HeaderException {
    Objects.requireNonNull(suffix);
    Objects.requireNonNull(header);
    Objects.requireNonNull(section);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    if (header.schemaVersion() != 5
        || header.flags() != 5
        || !"0000000000000000".equals(section.encryptionKeyIdentifier())
        || section.relationshipDataSize() != 0
        || header.tableHash() == 145293629L)
      throw error(
          UNSUPPORTED,
          "Requires schema5 sparse non-inline-ID zero-key section without relationships or special"
              + " fixture grammar");
    for (long raw :
        new long[] {
          section.fileOffset(),
          section.recordCount(),
          section.offsetRecordsEndOffset(),
          section.idListSize(),
          section.copyTableCount(),
          section.offsetMapIdCount(),
          section.stringTableSize()
        })
      if (raw < 0 || raw > 0xffffffffL)
        throw error(MALFORMED, "Invalid unsigned sparse section metadata");
    if (section.declaredEndOffset() < 0
        || section.offsetRecordsEndOffset() < section.fileOffset()
        || section.recordCount() > header.recordCount())
      throw error(MALFORMED, "Invalid sparse section extent/count");
    if (suffix.length > limits.maxSuffixBytes()
        || section.recordCount() > limits.maxRecordIds()
        || section.copyTableCount() > limits.maxCopies()
        || section.offsetMapIdCount() > limits.maxMapEntries())
      throw error(LIMIT, "Sparse suffix extraction exceeds bounds");
    if (section.idListSize() != section.recordCount() * 4L
        || section.offsetMapIdCount() != section.recordCount())
      throw error(
          UNSUPPORTED, "Sparse suffix subset requires one ID and map entry per declared record");
    long copyStart, mapStart, mapIdsStart, end;
    try {
      copyStart = Math.addExact(section.offsetRecordsEndOffset(), section.idListSize());
      mapStart = Math.addExact(copyStart, Math.multiplyExact(section.copyTableCount(), 8L));
      mapIdsStart = Math.addExact(mapStart, Math.multiplyExact(section.offsetMapIdCount(), 6L));
      end = Math.addExact(mapIdsStart, Math.multiplyExact(section.offsetMapIdCount(), 4L));
    } catch (ArithmeticException overflow) {
      throw error(MALFORMED, "Sparse suffix geometry overflow");
    }
    if (end != section.declaredEndOffset()
        || end - section.offsetRecordsEndOffset() != suffix.length)
      throw error(MALFORMED, "Sparse suffix span differs from exact declared geometry");
    var bytes = ByteBuffer.wrap(suffix).order(ByteOrder.LITTLE_ENDIAN);
    var ids = new ArrayList<Long>((int) section.recordCount());
    for (int i = 0; i < section.recordCount(); i++) {
      check(cancelled);
      ids.add(Integer.toUnsignedLong(bytes.getInt()));
    }
    var copies = new ArrayList<SparseSectionIndex.Copy>((int) section.copyTableCount());
    for (int i = 0; i < section.copyTableCount(); i++) {
      check(cancelled);
      copies.add(
          new SparseSectionIndex.Copy(
              Integer.toUnsignedLong(bytes.getInt()), Integer.toUnsignedLong(bytes.getInt())));
    }
    var maps = new ArrayList<SparseSectionIndex.MapEntry>((int) section.offsetMapIdCount());
    for (int i = 0; i < section.offsetMapIdCount(); i++) {
      check(cancelled);
      maps.add(
          new SparseSectionIndex.MapEntry(
              Integer.toUnsignedLong(bytes.getInt()), Short.toUnsignedInt(bytes.getShort())));
    }
    var mapIds = new ArrayList<Long>((int) section.offsetMapIdCount());
    for (int i = 0; i < section.offsetMapIdCount(); i++) {
      check(cancelled);
      mapIds.add(Integer.toUnsignedLong(bytes.getInt()));
    }
    check(cancelled);
    return new SparseSectionIndex(
        section.offsetRecordsEndOffset(),
        copyStart,
        mapStart,
        mapIdsStart,
        end,
        ids,
        copies,
        maps,
        mapIds,
        Set.of(
            SparseSectionIndex.Capability.RAW_OFFSET_ORIGIN_UNVERIFIED,
            SparseSectionIndex.Capability.RECORD_BODY_NOT_INSPECTED,
            SparseSectionIndex.Capability.SCHEMA_SEMANTICS_UNVERIFIED));
  }

  private static void check(BooleanSupplier cancelled) throws Db2HeaderException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Sparse suffix extraction cancelled");
  }

  private static Db2HeaderException error(Db2HeaderException.Reason reason, String message) {
    return new Db2HeaderException(reason, message);
  }
}
