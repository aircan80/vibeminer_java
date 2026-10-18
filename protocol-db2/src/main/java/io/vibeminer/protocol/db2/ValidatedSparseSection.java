package io.vibeminer.protocol.db2;

import static io.vibeminer.protocol.db2.SparseRecordException.Reason.*;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Privately prepared lookup for one narrow, coherent sparse section. This validates supplied
 * metadata only, not content authenticity, schema, strings or completeness of a whole table.
 * Absolute map coordinates for the writer subset are supported by DBCD commit
 * e732093f8864240fc5884bd1bba6b02f3dfc0d56, DBCD.IO/Writers/WDC5Writer.cs, SparseEntry.Offset =
 * writer.BaseStream.Position before record.CopyTo (MIT).
 */
public final class ValidatedSparseSection {
  public record Limits(int maxRecords, int maxCopies, long maxRecordAreaBytes) {
    public Limits {
      if (maxRecords < 0 || maxCopies < 0 || maxRecordAreaBytes < 0)
        throw new IllegalArgumentException("Invalid sparse locator bounds");
    }
  }

  public enum Status {
    FOUND,
    NOT_IN_THIS_SECTION
  }

  public record Location(
      long id, long physicalSourceId, long decodedFileOffset, int length, boolean copy) {}

  public record Lookup(Status status, Optional<Location> location) {}

  private final long[] ids, offsets;
  private final int[] sizes;
  private final Map<Long, Integer> copies;

  private ValidatedSparseSection(
      long[] ids, long[] offsets, int[] sizes, Map<Long, Integer> copies) {
    this.ids = ids;
    this.offsets = offsets;
    this.sizes = sizes;
    this.copies = Map.copyOf(copies);
  }

  public static ValidatedSparseSection prepare(
      SparseSectionIndex raw,
      Wdc5Metadata.Section section,
      Limits limits,
      BooleanSupplier cancelled)
      throws SparseRecordException {
    Objects.requireNonNull(raw);
    Objects.requireNonNull(section);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    if (!"0000000000000000".equals(section.encryptionKeyIdentifier())
        || section.relationshipDataSize() != 0
        || section.recordCount() == 0)
      throw error(UNSUPPORTED, "Requires nonempty zero-key sparse section without relationships");
    for (long value :
        new long[] {
          section.fileOffset(),
          section.offsetRecordsEndOffset(),
          section.recordCount(),
          section.idListSize(),
          section.copyTableCount(),
          section.offsetMapIdCount()
        })
      if (value < 0 || value > 0xffffffffL)
        throw error(MALFORMED, "Invalid unsigned sparse section geometry");
    if (section.offsetRecordsEndOffset() < section.fileOffset())
      throw error(MALFORMED, "Sparse record area has reversed extent");
    if (section.recordCount() > limits.maxRecords()
        || section.copyTableCount() > limits.maxCopies()
        || section.offsetRecordsEndOffset() - section.fileOffset() > limits.maxRecordAreaBytes())
      throw error(LIMIT, "Sparse locator preparation exceeds bounds");
    long count = section.recordCount();
    if (raw.recordIds().size() != count
        || raw.mapIds().size() != count
        || raw.mapEntries().size() != count
        || section.offsetMapIdCount() != count
        || section.idListSize() != count * 4L
        || raw.copies().size() != section.copyTableCount())
      throw error(
          UNSUPPORTED, "Sparse locator subset requires equal physical ID/map cardinalities");
    long copyStart, mapStart, mapIdsStart, end;
    try {
      copyStart = Math.addExact(section.offsetRecordsEndOffset(), section.idListSize());
      mapStart = Math.addExact(copyStart, Math.multiplyExact(section.copyTableCount(), 8L));
      mapIdsStart = Math.addExact(mapStart, Math.multiplyExact(count, 6L));
      end = Math.addExact(mapIdsStart, Math.multiplyExact(count, 4L));
    } catch (ArithmeticException overflow) {
      throw error(MALFORMED, "Sparse locator suffix geometry overflow");
    }
    if (raw.suffixOffset() != section.offsetRecordsEndOffset()
        || raw.copyTableOffset() != copyStart
        || raw.offsetMapOffset() != mapStart
        || raw.mapIdListOffset() != mapIdsStart
        || raw.declaredEndOffset() != end
        || section.declaredEndOffset() != end)
      throw error(MALFORMED, "Sparse index suffix geometry disagrees with selected section");
    var ids = new long[(int) count];
    var offsets = new long[(int) count];
    var sizes = new int[(int) count];
    long next = section.fileOffset(), previousId = 0;
    for (int i = 0; i < count; i++) {
      check(cancelled);
      long id = raw.recordIds().get(i);
      var map = raw.mapEntries().get(i);
      if (id <= previousId || id > 0xffffffffL || raw.mapIds().get(i) != id)
        throw error(
            UNSUPPORTED,
            "Sparse locator requires equal, positive, strictly increasing physical IDs");
      if (map.rawOffset() < 0
          || map.rawOffset() > 0xffffffffL
          || map.size() < 0
          || map.size() > 65535)
        throw error(MALFORMED, "Sparse map values exceed physical integer widths");
      if (map.size() == 0 || map.rawOffset() != next)
        throw error(UNSUPPORTED, "Sparse locator requires positive contiguous absolute spans");
      next = Math.addExact(map.rawOffset(), (long) map.size());
      if (next > section.offsetRecordsEndOffset())
        throw error(MALFORMED, "Sparse span exceeds selected record area");
      ids[i] = id;
      offsets[i] = map.rawOffset();
      sizes[i] = map.size();
      previousId = id;
    }
    if (next != section.offsetRecordsEndOffset())
      throw error(UNSUPPORTED, "Sparse spans do not exactly cover selected record area");
    var copies = new HashMap<Long, Integer>();
    for (var copy : raw.copies()) {
      check(cancelled);
      if (copy.targetId() < 1
          || copy.targetId() > 0xffffffffL
          || copy.sourceId() < 1
          || copy.sourceId() > 0xffffffffL)
        throw error(UNSUPPORTED, "Sparse copy IDs must be positive unsigned values");
      int source = Arrays.binarySearch(ids, copy.sourceId());
      if (source < 0
          || Arrays.binarySearch(ids, copy.targetId()) >= 0
          || copies.putIfAbsent(copy.targetId(), source) != null)
        throw error(
            UNSUPPORTED,
            "Sparse locator requires unique nonphysical targets and physical sources within this"
                + " section");
    }
    check(cancelled);
    return new ValidatedSparseSection(ids, offsets, sizes, copies);
  }

  /** NOT_IN_THIS_SECTION never means absent from another section, build, locale or whole table. */
  public Lookup lookup(long id) {
    if (id < 0 || id > 0xffffffffL)
      throw new IllegalArgumentException("Lookup ID must be unsigned32");
    int row = Arrays.binarySearch(ids, id);
    boolean copy = false;
    if (row < 0) {
      Integer source = copies.get(id);
      if (source == null) return new Lookup(Status.NOT_IN_THIS_SECTION, Optional.empty());
      row = source;
      copy = true;
    }
    return new Lookup(
        Status.FOUND, Optional.of(new Location(id, ids[row], offsets[row], sizes[row], copy)));
  }

  private static void check(BooleanSupplier cancelled) throws SparseRecordException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Sparse locator preparation cancelled");
  }

  private static SparseRecordException error(SparseRecordException.Reason reason, String message) {
    return new SparseRecordException(reason, message);
  }
}
