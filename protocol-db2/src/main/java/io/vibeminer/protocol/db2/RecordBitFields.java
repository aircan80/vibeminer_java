package io.vibeminer.protocol.db2;

import java.util.Objects;

/**
 * Stateless LSB-first integer extraction from one explicit record slice. No bytes outside that
 * slice are inspected, including for unaligned 64-bit fields.
 *
 * <p>Bit ordering and signed extension match DBCD BitReader at revision
 * e732093f8864240fc5884bd1bba6b02f3dfc0d56. This implementation uses bounded Java byte reads, not
 * the reference implementation's unsafe machine-word loads. Input must remain unchanged during a
 * call; no schema or storage-mode semantics are implied.
 */
public final class RecordBitFields {
  private RecordBitFields() {}

  /**
   * Returns width bits in the low bits of a long. For width 64 the returned long is a raw unsigned
   * bit pattern and may be negative; use unsigned Java operations when interpreting it.
   */
  public static long unsignedBits(
      byte[] bytes, int recordOffset, int recordLength, long bitOffset, int width)
      throws BitFieldException {
    validate(bytes, recordOffset, recordLength, bitOffset, width);
    long result = 0;
    int extracted = 0;
    while (extracted < width) {
      long position = bitOffset + extracted;
      int withinByte = (int) (position & 7);
      int take = Math.min(8 - withinByte, width - extracted);
      int source = bytes[recordOffset + (int) (position >>> 3)] & 255;
      int fragment = (source >>> withinByte) & ((1 << take) - 1);
      result |= (long) fragment << extracted;
      extracted += take;
    }
    return result;
  }

  /** Returns the same field interpreted as a two's-complement integer of the given width. */
  public static long signedValue(
      byte[] bytes, int recordOffset, int recordLength, long bitOffset, int width)
      throws BitFieldException {
    long raw = unsignedBits(bytes, recordOffset, recordLength, bitOffset, width);
    if (width == 64) return raw;
    int shift = 64 - width;
    return (raw << shift) >> shift;
  }

  private static void validate(
      byte[] bytes, int recordOffset, int recordLength, long bitOffset, int width)
      throws BitFieldException {
    Objects.requireNonNull(bytes, "bytes");
    if (width < 1 || width > 64) {
      throw new BitFieldException(
          BitFieldException.Reason.INVALID_WIDTH,
          "Integer field width must be between 1 and 64 bits");
    }
    // Subtraction avoids overflowing offset + length before checking the array boundary.
    if (recordOffset < 0
        || recordOffset > bytes.length
        || recordLength < 0
        || recordLength > bytes.length - recordOffset) {
      throw new BitFieldException(
          BitFieldException.Reason.INVALID_RECORD_SLICE,
          "Record slice lies outside the supplied bytes");
    }
    long recordBits = (long) recordLength * 8;
    if (bitOffset < 0 || bitOffset > recordBits || width > recordBits - bitOffset) {
      throw new BitFieldException(
          BitFieldException.Reason.FIELD_OUTSIDE_RECORD,
          "Integer field extends outside its record slice");
    }
  }
}
