package io.vibeminer.protocol.db2;

import static io.vibeminer.protocol.db2.FieldValueException.Reason.*;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Pure per-field integer decoder for a restricted observed WDC5 storage subset.
 *
 * <p>Physical ordering follows DBCD WDC5Reader.GetFieldValue/ColumnMetaData at revision
 * e732093f8864240fc5884bd1bba6b02f3dfc0d56. Only IMMEDIATE, SIGNED_IMMEDIATE and scalar PALLET with
 * matching duplicated bit offset/width and zero parameter3 are supported. No schema, row identity,
 * copies, string or array semantics are inferred. Inputs must remain unchanged during decoding. The
 * caller supplies only this field's pallet slice, never an implicit global table.
 */
public final class FieldValueDecoder {
  public enum Interpretation {
    UNSIGNED_BITS,
    SIGNED_TWOS_COMPLEMENT
  }

  /**
   * Raw unsigned bits remain available even when physical storage explicitly declares signedness.
   */
  public record IntegerValue(long rawBits, int bitWidth, Interpretation interpretation) {
    public IntegerValue {
      Objects.requireNonNull(interpretation);
      if (bitWidth < 1 || bitWidth > 64 || bitWidth < 64 && (rawBits >>> bitWidth) != 0)
        throw new IllegalArgumentException("Invalid integer bit pattern");
    }

    public OptionalLong signedPhysicalValue() {
      if (interpretation != Interpretation.SIGNED_TWOS_COMPLEMENT) return OptionalLong.empty();
      int shift = 64 - bitWidth;
      return OptionalLong.of(bitWidth == 64 ? rawBits : (rawBits << shift) >> shift);
    }
  }

  public IntegerValue decode(
      byte[] record,
      int recordOffset,
      int recordLength,
      Wdc5Metadata.Storage storage,
      byte[] pallet,
      int palletOffset,
      int palletLength)
      throws FieldValueException {
    Objects.requireNonNull(record, "record");
    Objects.requireNonNull(storage, "storage");
    Objects.requireNonNull(pallet, "pallet");
    Wdc5Metadata.StorageKind kind = validateStorage(storage);
    validatePalletSlice(pallet, palletOffset, palletLength);
    if (kind == Wdc5Metadata.StorageKind.PALLET) {
      if (storage.size() > 32)
        throw failure(UNSUPPORTED_PARAMETERS, "Pallet index width exceeds supported uint32 index");
      if (palletLength == 0
          || palletLength % 4 != 0
          || storage.additionalDataSize() != palletLength)
        throw failure(
            INVALID_PALLET, "Pallet slice must exactly match nonempty per-field uint32 data");
      long index = readRecord(record, recordOffset, recordLength, storage);
      if (index >= palletLength / 4)
        throw failure(INDEX_OUT_OF_RANGE, "Record index lies outside this field's pallet");
      int at = palletOffset + (int) index * 4;
      long raw = 0;
      for (int i = 0; i < 4; i++) raw |= (long) (pallet[at + i] & 255) << (8 * i);
      return new IntegerValue(raw, 32, Interpretation.UNSIGNED_BITS);
    }
    if (storage.additionalDataSize() != 0 || palletLength != 0)
      throw failure(INVALID_PALLET, "Immediate storage does not consume pallet data");
    long raw = readRecord(record, recordOffset, recordLength, storage);
    return new IntegerValue(
        raw,
        storage.size(),
        kind == Wdc5Metadata.StorageKind.SIGNED_IMMEDIATE
            ? Interpretation.SIGNED_TWOS_COMPLEMENT
            : Interpretation.UNSIGNED_BITS);
  }

  private static Wdc5Metadata.StorageKind validateStorage(Wdc5Metadata.Storage storage)
      throws FieldValueException {
    long rawKind = storage.rawKind();
    if (rawKind != 1 && rawKind != 3 && rawKind != 5)
      throw failure(UNSUPPORTED_STORAGE, "Unsupported field storage kind");
    Wdc5Metadata.StorageKind expected = Wdc5Metadata.StorageKind.values()[(int) rawKind];
    if (storage.kind() == null || storage.kind().isEmpty() || storage.kind().get() != expected)
      throw failure(INVALID_DESCRIPTOR, "Raw storage kind conflicts with decoded kind");
    if (storage.recordOffset() < 0
        || storage.recordOffset() > 65535
        || storage.size() < 1
        || storage.size() > 64
        || storage.additionalDataSize() < 0
        || storage.additionalDataSize() > 0xffffffffL)
      throw failure(INVALID_DESCRIPTOR, "Invalid physical descriptor geometry");
    if (storage.parameter1() != storage.recordOffset() || storage.parameter2() != storage.size())
      throw failure(INVALID_DESCRIPTOR, "Descriptor offset/width disagree with union parameters");
    if (storage.parameter3() != 0)
      throw failure(UNSUPPORTED_PARAMETERS, "Nonzero storage flags/cardinality are unsupported");
    return expected;
  }

  private static long readRecord(byte[] bytes, int offset, int length, Wdc5Metadata.Storage storage)
      throws FieldValueException {
    try {
      return RecordBitFields.unsignedBits(
          bytes, offset, length, storage.recordOffset(), storage.size());
    } catch (BitFieldException invalid) {
      throw new FieldValueException(
          INVALID_RECORD, "Field lies outside its explicit record slice", invalid);
    }
  }

  private static void validatePalletSlice(byte[] bytes, int offset, int length)
      throws FieldValueException {
    if (offset < 0 || offset > bytes.length || length < 0 || length > bytes.length - offset)
      throw failure(INVALID_PALLET, "Pallet slice lies outside supplied bytes");
  }

  private static FieldValueException failure(FieldValueException.Reason reason, String message) {
    return new FieldValueException(reason, message);
  }
}
