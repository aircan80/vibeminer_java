package io.vibeminer.protocol.db2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FieldValueDecoderTest {
  final FieldValueDecoder decoder = new FieldValueDecoder();

  Wdc5Metadata.Storage storage(int offset, int width, long bytes, int mode) {
    return new Wdc5Metadata.Storage(
        offset,
        width,
        bytes,
        mode,
        Optional.of(Wdc5Metadata.StorageKind.values()[mode]),
        offset,
        width,
        0);
  }

  @Test
  void immediateRawAndSignedPhysicalInterpretationsAreExplicit() throws Exception {
    byte[] record = {(byte) 0xf8};
    var unsigned = decoder.decode(record, 0, 1, storage(3, 5, 0, 1), new byte[0], 0, 0);
    assertEquals(31, unsigned.rawBits());
    assertEquals(OptionalLong.empty(), unsigned.signedPhysicalValue());
    var signed = decoder.decode(record, 0, 1, storage(3, 5, 0, 5), new byte[0], 0, 0);
    assertEquals(31, signed.rawBits());
    assertEquals(OptionalLong.of(-1), signed.signedPhysicalValue());
  }

  @Test
  void palletUsesOnlyExplicitFieldSliceAndReturnsUnsigned32Raw() throws Exception {
    byte[] pallet = {99, 99, 1, 2, 3, 4, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 99};
    var value = decoder.decode(new byte[] {1}, 0, 1, storage(0, 1, 8, 3), pallet, 2, 8);
    assertEquals(4294967295L, value.rawBits());
    assertEquals(32, value.bitWidth());
    assertTrue(value.signedPhysicalValue().isEmpty());
    assertThrows(
        FieldValueException.class,
        () -> decoder.decode(new byte[] {2}, 0, 1, storage(0, 2, 8, 3), pallet, 2, 8));
  }

  @Test
  void inconsistentUnknownAndUnsupportedDescriptorsFailClosed() {
    var base = storage(0, 4, 0, 1);
    var inconsistent = new Wdc5Metadata.Storage(0, 4, 0, 1, base.kind(), 1, 4, 0);
    assertThrows(
        FieldValueException.class,
        () -> decoder.decode(new byte[1], 0, 1, inconsistent, new byte[0], 0, 0));
    for (int kind : new int[] {0, 2, 4})
      assertThrows(
          FieldValueException.class,
          () -> decoder.decode(new byte[1], 0, 1, storage(0, 1, 0, kind), new byte[0], 0, 0));
    var unknown = new Wdc5Metadata.Storage(0, 1, 0, 99, Optional.empty(), 0, 1, 0);
    assertThrows(
        FieldValueException.class,
        () -> decoder.decode(new byte[1], 0, 1, unknown, new byte[0], 0, 0));
    var flags =
        new Wdc5Metadata.Storage(
            0, 1, 0, 1, Optional.of(Wdc5Metadata.StorageKind.IMMEDIATE), 0, 1, 1);
    assertThrows(
        FieldValueException.class,
        () -> decoder.decode(new byte[1], 0, 1, flags, new byte[0], 0, 0));
  }

  @Test
  void palletGeometryAndRecordBoundariesAreChecked() {
    assertThrows(
        FieldValueException.class,
        () -> decoder.decode(new byte[1], 0, 1, storage(7, 2, 0, 1), new byte[0], 0, 0));
    assertThrows(
        FieldValueException.class,
        () -> decoder.decode(new byte[1], 0, 1, storage(0, 1, 3, 3), new byte[3], 0, 3));
    assertThrows(
        FieldValueException.class,
        () ->
            decoder.decode(
                new byte[1], 0, 1, storage(0, 1, 4, 3), new byte[8], 4, Integer.MAX_VALUE));
    assertThrows(
        FieldValueException.class,
        () -> decoder.decode(new byte[1], 0, 1, storage(0, 1, 4, 3), new byte[8], 0, 8));
  }

  @Test
  void differentialImmediateWidthsAndSignedExtremes() throws Exception {
    var random = new Random(59);
    byte[] bytes = new byte[12];
    for (int sample = 0; sample < 10000; sample++) {
      random.nextBytes(bytes);
      int width = 1 + random.nextInt(64), offset = random.nextInt(96 - width + 1);
      long expected = 0;
      for (int i = 0; i < width; i++) {
        int bit = offset + i;
        expected |= (long) ((bytes[bit / 8] >>> (bit % 8)) & 1) << i;
      }
      var unsigned = decoder.decode(bytes, 0, 12, storage(offset, width, 0, 1), new byte[0], 0, 0);
      assertEquals(expected, unsigned.rawBits());
      var signed = decoder.decode(bytes, 0, 12, storage(offset, width, 0, 5), new byte[0], 0, 0);
      assertEquals(
          width == 64 ? expected : (expected << (64 - width)) >> (64 - width),
          signed.signedPhysicalValue().orElseThrow());
    }
  }

  @Test
  void palletUint32IndexCannotWrapIntoNegativeOrNeighborField() throws Exception {
    var failure =
        assertThrows(
            FieldValueException.class,
            () ->
                decoder.decode(
                    new byte[] {-1, -1, -1, -1}, 0, 4, storage(0, 32, 4, 3), new byte[4], 0, 4));
    assertEquals(FieldValueException.Reason.INDEX_OUT_OF_RANGE, failure.reason());
    var cardinality =
        new Wdc5Metadata.Storage(0, 1, 4, 3, Optional.of(Wdc5Metadata.StorageKind.PALLET), 0, 1, 1);
    assertEquals(
        FieldValueException.Reason.UNSUPPORTED_PARAMETERS,
        assertThrows(
                FieldValueException.class,
                () -> decoder.decode(new byte[] {0}, 0, 1, cardinality, new byte[4], 0, 4))
            .reason());
    var mismatch =
        new Wdc5Metadata.Storage(
            0, 1, 0, 1, Optional.of(Wdc5Metadata.StorageKind.SIGNED_IMMEDIATE), 0, 1, 0);
    assertEquals(
        FieldValueException.Reason.INVALID_DESCRIPTOR,
        assertThrows(
                FieldValueException.class,
                () -> decoder.decode(new byte[] {0}, 0, 1, mismatch, new byte[0], 0, 0))
            .reason());
  }

  @Test
  void differentialPalletLittleEndianValuesAndExplicitRecordSlice() throws Exception {
    var random = new Random(59001);
    byte[] table = new byte[4 * 256 + 6];
    random.nextBytes(table);
    for (int index = 0; index < 256; index++) {
      byte[] record = {(byte) 255, (byte) index, (byte) 255};
      long expected = 0;
      for (int b = 3; b >= 0; b--) expected = (expected << 8) | (table[3 + index * 4 + b] & 255);
      var value = decoder.decode(record, 1, 1, storage(0, 8, 1024, 3), table, 3, 1024);
      assertEquals(expected, value.rawBits());
      assertEquals(FieldValueDecoder.Interpretation.UNSIGNED_BITS, value.interpretation());
    }
  }
}
