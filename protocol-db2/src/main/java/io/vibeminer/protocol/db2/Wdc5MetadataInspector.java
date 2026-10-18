package io.vibeminer.protocol.db2;

import static io.vibeminer.protocol.db2.Db2HeaderException.Reason.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Bounded WDC5 section/field/storage prefix inspection; no row or auxiliary-value decoding. Layout
 * reference: DBCD e732093f8864240fc5884bd1bba6b02f3dfc0d56, DBStructs.cs (SectionHeaderWDC5,
 * FieldMetaData, ColumnMetaData) and WDC5Reader.cs.
 */
public final class Wdc5MetadataInspector {
  public static final int SECTION_BYTES = 40;
  public static final int FIELD_BYTES = 4;
  public static final int STORAGE_BYTES = 24;

  public record Limits(
      int maxPrefixBytes,
      long maxDecodedFileBytes,
      long maxRecords,
      int maxFields,
      int maxSections,
      long maxRecordBytes,
      long maxAdditionalDataBytes) {
    public Limits {
      if (maxPrefixBytes < Wdc5HeaderInspector.HEADER_BYTES
          || maxDecodedFileBytes < Wdc5HeaderInspector.HEADER_BYTES
          || maxRecords < 0
          || maxFields < 0
          || maxSections < 0
          || maxRecordBytes < 0
          || maxAdditionalDataBytes < 0) {
        throw new IllegalArgumentException("Invalid WDC5 metadata inspection bounds");
      }
    }
  }

  /**
   * The supplied length is a caller declaration, not verified by this parser. Input may end after
   * descriptors; extra bytes are ignored. Range checks establish declared geometry only. Encrypted
   * ID lists, auxiliary values and section bodies remain uninspected. Section disjointness and the
   * contents of gaps are not established by this metadata-only inspection.
   */
  public Wdc5Metadata inspect(
      byte[] prefix, long declaredDecodedLength, Limits limits, BooleanSupplier cancelled)
      throws Db2HeaderException {
    Objects.requireNonNull(prefix);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    checkCancelled(cancelled);
    if (declaredDecodedLength < Wdc5HeaderInspector.HEADER_BYTES) {
      throw error(MALFORMED, "Declared WDC5 source length is shorter than mandatory header");
    }
    if (declaredDecodedLength > limits.maxDecodedFileBytes()) {
      throw error(LIMIT, "Declared WDC5 source length exceeds inspection bound");
    }
    var headerLimits =
        new Wdc5HeaderInspector.Limits(
            limits.maxPrefixBytes(),
            limits.maxRecords(),
            limits.maxFields(),
            limits.maxSections(),
            limits.maxRecordBytes(),
            limits.maxDecodedFileBytes());
    Wdc5Header header = new Wdc5HeaderInspector().inspect(prefix, headerLimits, cancelled);
    long sectionsEnd = Wdc5HeaderInspector.HEADER_BYTES + header.sectionCount() * SECTION_BYTES;
    long fieldsEnd = sectionsEnd + header.fieldCount() * FIELD_BYTES;
    long metadataEnd = fieldsEnd + header.fieldCount() * STORAGE_BYTES;
    if (metadataEnd > limits.maxPrefixBytes()) {
      throw error(LIMIT, "WDC5 descriptor prefix exceeds inspection byte bound");
    }
    if (header.fieldStorageInfoSize() != header.fieldCount() * STORAGE_BYTES) {
      throw error(UNSUPPORTED, "Unsupported WDC5 declared field-storage descriptor width/count");
    }
    if (metadataEnd > prefix.length || metadataEnd > declaredDecodedLength) {
      throw error(MALFORMED, "Truncated WDC5 descriptor prefix");
    }
    long auxiliaryEnd =
        checkedAdd(metadataEnd, checkedAdd(header.palletDataSize(), header.commonDataSize()));
    if (auxiliaryEnd > declaredDecodedLength) {
      throw error(MALFORMED, "Declared WDC5 auxiliary data extends beyond source length");
    }
    var capabilities =
        EnumSet.of(
            Wdc5Metadata.Capability.BODY_NOT_VALIDATED,
            Wdc5Metadata.Capability.RECORD_DECODER_UNAVAILABLE,
            Wdc5Metadata.Capability.SCHEMA_SEMANTICS_UNVERIFIED);
    boolean sparse = (header.flags() & 1) != 0;
    if (sparse) capabilities.add(Wdc5Metadata.Capability.SPARSE_LAYOUT_OBSERVED);
    var bytes = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN);
    var sections = new ArrayList<Wdc5Metadata.Section>((int) header.sectionCount());
    long totalRecords = 0;
    for (int i = 0; i < header.sectionCount(); i++) {
      checkCancelled(cancelled);
      int at = Wdc5HeaderInspector.HEADER_BYTES + i * SECTION_BYTES;
      var section =
          readSection(bytes, at, header, sparse, auxiliaryEnd, declaredDecodedLength, limits);
      if (section.recordCount() > limits.maxRecords() - totalRecords) {
        throw error(LIMIT, "WDC5 aggregate section record count exceeds inspection bound");
      }
      totalRecords += section.recordCount();
      if (section.encryptionKeyDeclared()) {
        capabilities.add(Wdc5Metadata.Capability.ENCRYPTION_KEY_DECLARED);
      }
      if (sparse && section.stringTableSize() != 0) {
        capabilities.add(Wdc5Metadata.Capability.UNSUPPORTED_SECTION_PARAMETERS);
      }
      sections.add(section);
    }
    if (totalRecords != header.recordCount()) {
      throw error(MALFORMED, "WDC5 declared header and section physical record counts disagree");
    }
    var fields = new ArrayList<Wdc5Metadata.Field>((int) header.fieldCount());
    var storage = new ArrayList<Wdc5Metadata.Storage>((int) header.fieldCount());
    long palletBytes = 0;
    long commonBytes = 0;
    boolean knownAccounting = true;
    for (int i = 0; i < header.fieldCount(); i++) {
      checkCancelled(cancelled);
      int fieldAt = (int) sectionsEnd + i * FIELD_BYTES;
      fields.add(new Wdc5Metadata.Field(bytes.getShort(fieldAt), bytes.getShort(fieldAt + 2)));
      int at = (int) fieldsEnd + i * STORAGE_BYTES;
      long additionalSize = u32(bytes, at + 4);
      if (additionalSize > limits.maxAdditionalDataBytes()) {
        throw error(LIMIT, "WDC5 per-field additional data exceeds inspection bound");
      }
      long rawKind = u32(bytes, at + 8);
      Optional<Wdc5Metadata.StorageKind> kind =
          rawKind < Wdc5Metadata.StorageKind.values().length
              ? Optional.of(Wdc5Metadata.StorageKind.values()[(int) rawKind])
              : Optional.empty();
      storage.add(
          new Wdc5Metadata.Storage(
              Short.toUnsignedInt(bytes.getShort(at)),
              Short.toUnsignedInt(bytes.getShort(at + 2)),
              additionalSize,
              rawKind,
              kind,
              u32(bytes, at + 12),
              u32(bytes, at + 16),
              u32(bytes, at + 20)));
      if (kind.isEmpty()) {
        capabilities.add(Wdc5Metadata.Capability.UNKNOWN_STORAGE_KIND);
        knownAccounting = false;
      } else if (kind.get() == Wdc5Metadata.StorageKind.COMMON) {
        requireMultiple(additionalSize, 8, "common ID/value pairs");
        commonBytes = checkedAdd(commonBytes, additionalSize);
      } else if (kind.get() == Wdc5Metadata.StorageKind.PALLET
          || kind.get() == Wdc5Metadata.StorageKind.PALLET_ARRAY) {
        requireMultiple(additionalSize, 4, "pallet values");
        palletBytes = checkedAdd(palletBytes, additionalSize);
      } else if (additionalSize != 0) {
        capabilities.add(Wdc5Metadata.Capability.UNSUPPORTED_STORAGE_PARAMETERS);
        knownAccounting = false;
      }
    }
    if (knownAccounting) {
      if (palletBytes != header.palletDataSize() || commonBytes != header.commonDataSize()) {
        throw error(MALFORMED, "WDC5 declared and per-field auxiliary data sizes disagree");
      }
    } else {
      capabilities.add(Wdc5Metadata.Capability.AUXILIARY_ACCOUNTING_UNVERIFIED);
    }
    checkCancelled(cancelled);
    return new Wdc5Metadata(
        header, sections, fields, storage, metadataEnd, auxiliaryEnd, capabilities);
  }

  private static Wdc5Metadata.Section readSection(
      ByteBuffer bytes,
      int at,
      Wdc5Header header,
      boolean sparse,
      long auxiliaryEnd,
      long sourceLength,
      Limits limits)
      throws Db2HeaderException {
    String key = HexFormat.of().toHexDigits(bytes.getLong(at));
    long fileOffset = u32(bytes, at + 8);
    long records = u32(bytes, at + 12);
    long strings = u32(bytes, at + 16);
    long recordsEnd = u32(bytes, at + 20);
    long idBytes = u32(bytes, at + 24);
    long relationships = u32(bytes, at + 28);
    long mapCount = u32(bytes, at + 32);
    long copyCount = u32(bytes, at + 36);
    if (records > limits.maxRecords()
        || mapCount > limits.maxRecords()
        || copyCount > limits.maxRecords()) {
      throw error(LIMIT, "WDC5 section record/map/copy count exceeds inspection bound");
    }
    if (fileOffset > sourceLength
        || recordsEnd > sourceLength
        || strings > sourceLength
        || relationships > sourceLength
        || idBytes > sourceLength) {
      throw error(MALFORMED, "WDC5 section declaration exceeds source length");
    }
    requireMultiple(idBytes, 4, "noninline IDs");
    if (relationships != 0) {
      if (relationships < 12) {
        throw error(MALFORMED, "WDC5 relationship data cannot contain its three-word header");
      }
      requireMultiple(relationships - 12, 8, "relationship ID/index pairs");
    }
    if (sparse && recordsEnd < fileOffset) {
      throw error(MALFORMED, "WDC5 sparse record end precedes section start");
    }
    long recordBytes =
        sparse ? recordsEnd - fileOffset : checkedMultiply(records, header.recordSize());
    long span = checkedAdd(recordBytes, sparse ? 0 : strings);
    span = checkedAdd(span, idBytes);
    span = checkedAdd(span, checkedMultiply(copyCount, 8));
    // Sparse map entries are six bytes; corresponding ID entries are four bytes.
    span = checkedAdd(span, checkedMultiply(mapCount, 10));
    span = checkedAdd(span, relationships);
    long end = checkedAdd(fileOffset, span);
    if (end > sourceLength || (span != 0 && fileOffset < auxiliaryEnd)) {
      throw error(MALFORMED, "WDC5 declared section span is outside supported source geometry");
    }
    return new Wdc5Metadata.Section(
        key,
        fileOffset,
        records,
        strings,
        recordsEnd,
        idBytes,
        relationships,
        mapCount,
        copyCount,
        end);
  }

  private static void requireMultiple(long value, int size, String field)
      throws Db2HeaderException {
    if (value % size != 0) {
      throw error(MALFORMED, "WDC5 additional data is not a whole number of " + field);
    }
  }

  private static long checkedMultiply(long left, long right) throws Db2HeaderException {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException ex) {
      throw error(MALFORMED, "WDC5 declared size multiplication overflows source addressing");
    }
  }

  private static long checkedAdd(long left, long right) throws Db2HeaderException {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException ex) {
      throw error(MALFORMED, "WDC5 declared size addition overflows source addressing");
    }
  }

  private static long u32(ByteBuffer bytes, int at) {
    return Integer.toUnsignedLong(bytes.getInt(at));
  }

  private static void checkCancelled(BooleanSupplier cancelled) throws Db2HeaderException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "WDC5 metadata inspection cancelled");
  }

  private static Db2HeaderException error(Db2HeaderException.Reason reason, String message) {
    return new Db2HeaderException(reason, message);
  }
}
