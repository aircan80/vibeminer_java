package io.vibeminer.protocol.db2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RecordBitFieldsTest {
  @Test
  void exactSliceAndUnaligned64NeedNoNeighborPadding() throws Exception {
    byte[] record = {(byte) 0x80, 1, 2, 3, 4, 5, 6, 7, 8};
    assertEquals(0x100e0c0a08060403L, RecordBitFields.unsignedBits(record, 0, 9, 7, 64));
    assertEquals(8, RecordBitFields.unsignedBits(record, 8, 1, 0, 8));
    assertThrows(BitFieldException.class, () -> RecordBitFields.unsignedBits(record, 0, 8, 1, 64));
  }

  @Test
  void unsigned64AndSignedExtremes() throws Exception {
    byte[] ones = new byte[8];
    java.util.Arrays.fill(ones, (byte) 0xff);
    assertEquals(-1L, RecordBitFields.unsignedBits(ones, 0, 8, 0, 64));
    for (int width = 1; width <= 64; width++) {
      assertEquals(-1L, RecordBitFields.signedValue(ones, 0, 8, 0, width));
      byte[] maximum = ones.clone();
      int signBit = width - 1;
      maximum[signBit / 8] &= (byte) ~(1 << (signBit % 8));
      assertEquals(
          width == 64 ? Long.MAX_VALUE : (1L << signBit) - 1,
          RecordBitFields.signedValue(maximum, 0, 8, 0, width));
      byte[] minimum = new byte[8];
      int sign = width - 1;
      minimum[sign / 8] = (byte) (1 << (sign % 8));
      assertEquals(
          width == 64 ? Long.MIN_VALUE : -(1L << sign),
          RecordBitFields.signedValue(minimum, 0, 8, 0, width));
    }
  }

  @Test
  void checkedInvalidGeometryAndWidths() {
    byte[] bytes = new byte[8];
    for (int width : new int[] {Integer.MIN_VALUE, 0, 65, Integer.MAX_VALUE})
      assertEquals(
          BitFieldException.Reason.INVALID_WIDTH,
          assertThrows(
                  BitFieldException.class,
                  () -> RecordBitFields.unsignedBits(bytes, 0, 8, 0, width))
              .reason());
    assertThrows(BitFieldException.class, () -> RecordBitFields.unsignedBits(bytes, -1, 8, 0, 1));
    assertThrows(
        BitFieldException.class,
        () -> RecordBitFields.unsignedBits(bytes, 1, Integer.MAX_VALUE, 0, 1));
    assertThrows(
        BitFieldException.class,
        () -> RecordBitFields.unsignedBits(bytes, 0, 8, Long.MAX_VALUE, 1));
    assertThrows(BitFieldException.class, () -> RecordBitFields.unsignedBits(bytes, 0, 8, -1, 1));
    assertThrows(BitFieldException.class, () -> RecordBitFields.unsignedBits(bytes, 8, 0, 0, 1));
  }

  @Test
  void randomDifferentialAgainstBitByBitReference() throws Exception {
    Random random = new Random(58123);
    byte[] source = new byte[40];
    for (int sample = 0; sample < 20000; sample++) {
      random.nextBytes(source);
      int recordOffset = random.nextInt(8),
          length = 9 + random.nextInt(20),
          width = 1 + random.nextInt(64),
          bit = random.nextInt(length * 8 - width + 1);
      long expected = 0;
      for (int i = 0; i < width; i++) {
        int position = bit + i;
        expected |= (long) ((source[recordOffset + position / 8] >>> (position % 8)) & 1) << i;
      }
      assertEquals(
          expected, RecordBitFields.unsignedBits(source, recordOffset, length, bit, width));
      long signed = width == 64 ? expected : (expected << (64 - width)) >> (64 - width);
      assertEquals(signed, RecordBitFields.signedValue(source, recordOffset, length, bit, width));
    }
  }
}
