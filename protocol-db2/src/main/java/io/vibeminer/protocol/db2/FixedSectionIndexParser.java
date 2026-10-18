package io.vibeminer.protocol.db2;

import static io.vibeminer.protocol.db2.Db2HeaderException.Reason.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Bounded fixed-section ID/copy extraction, not row decoding or source authentication. Physical
 * order reference: DBCD e732093f8864240fc5884bd1bba6b02f3dfc0d56, DBCD.IO/Readers/WDC5Reader.cs
 * lines 343–420 (MIT). No sequential-ID repair or copy resolution.
 */
public final class FixedSectionIndexParser {
  public record Limits(int maxSectionBytes, int maxRecordIds, int maxCopies) {
    public Limits {
      if (maxSectionBytes < 0 || maxRecordIds < 0 || maxCopies < 0)
        throw new IllegalArgumentException("Negative section bounds");
    }
  }

  /** Input must be exactly the selected section span starting at section.fileOffset(). */
  public FixedSectionIndex parse(
      byte[] span,
      Wdc5Header header,
      Wdc5Metadata.Section section,
      Limits limits,
      BooleanSupplier cancelled)
      throws Db2HeaderException {
    Objects.requireNonNull(span);
    Objects.requireNonNull(header);
    Objects.requireNonNull(section);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    // Deliberately narrow header subset: non-inline IDs only. No unknown flag semantics assumed.
    if (header.schemaVersion() != 5
        || header.flags() != 4
        || !"0000000000000000".equals(section.encryptionKeyIdentifier())
        || section.relationshipDataSize() != 0
        || section.offsetMapIdCount() != 0
        || section.offsetRecordsEndOffset() != 0)
      throw error(
          UNSUPPORTED,
          "Requires schema5 fixed non-inline-ID zero-key section without relationships/offset"
              + " maps");
    if (header.recordSize() < 0
        || header.recordSize() > 0xffffffffL
        || section.recordCount() < 0
        || section.recordCount() > 0xffffffffL
        || section.copyTableCount() < 0
        || section.copyTableCount() > 0xffffffffL
        || section.recordCount() > header.recordCount()
        || section.fileOffset() > 0xffffffffL
        || section.fileOffset() < 0
        || section.stringTableSize() < 0
        || section.stringTableSize() > 0xffffffffL
        || section.idListSize() < 0
        || section.idListSize() > 0xffffffffL
        || section.declaredEndOffset() < 0)
      throw error(MALFORMED, "Invalid unsigned section metadata");
    if (section.recordCount() > limits.maxRecordIds()
        || section.copyTableCount() > limits.maxCopies()
        || span.length > limits.maxSectionBytes())
      throw error(LIMIT, "Section index extraction exceeds bounds");
    if (section.recordCount() > 0 && header.recordSize() == 0)
      throw error(MALFORMED, "Nonempty fixed section has zero-width records");
    long recordsEnd, idsStart, copiesStart, end;
    try {
      recordsEnd = Math.multiplyExact(section.recordCount(), header.recordSize());
      idsStart = Math.addExact(recordsEnd, section.stringTableSize());
      if (section.idListSize() != Math.multiplyExact(section.recordCount(), 4L))
        throw error(MALFORMED, "ID list cardinality differs from fixed records");
      copiesStart = Math.addExact(idsStart, section.idListSize());
      end = Math.addExact(copiesStart, Math.multiplyExact(section.copyTableCount(), 8L));
      if (Math.addExact(section.fileOffset(), end) != section.declaredEndOffset())
        throw error(MALFORMED, "Declared section end differs from exact fixed geometry");
    } catch (ArithmeticException overflow) {
      throw error(MALFORMED, "Section index geometry overflow");
    }
    if (end != span.length)
      throw error(MALFORMED, "Provided section span is truncated or has trailing bytes");
    var bytes = ByteBuffer.wrap(span).order(ByteOrder.LITTLE_ENDIAN);
    bytes.position((int) idsStart);
    var ids = new ArrayList<Long>((int) section.recordCount());
    for (int i = 0; i < section.recordCount(); i++) {
      check(cancelled);
      ids.add(Integer.toUnsignedLong(bytes.getInt()));
    }
    var copies = new ArrayList<FixedSectionIndex.Copy>((int) section.copyTableCount());
    for (int i = 0; i < section.copyTableCount(); i++) {
      check(cancelled);
      copies.add(
          new FixedSectionIndex.Copy(
              Integer.toUnsignedLong(bytes.getInt()), Integer.toUnsignedLong(bytes.getInt())));
    }
    check(cancelled);
    return new FixedSectionIndex(
        section.fileOffset(),
        header.recordSize(),
        section.fileOffset() + recordsEnd,
        section.fileOffset() + idsStart,
        section.fileOffset() + copiesStart,
        section.declaredEndOffset(),
        ids,
        copies);
  }

  private static void check(BooleanSupplier cancelled) throws Db2HeaderException {
    if (cancelled.getAsBoolean())
      throw error(CANCELLED, "Fixed section index extraction cancelled");
  }

  private static Db2HeaderException error(Db2HeaderException.Reason reason, String message) {
    return new Db2HeaderException(reason, message);
  }
}
