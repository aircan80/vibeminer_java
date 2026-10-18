package io.vibeminer.protocol.db2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Wdc5MetadataInspectorTest {
  private final Wdc5MetadataInspector inspector = new Wdc5MetadataInspector();
  private final Wdc5MetadataInspector.Limits limits =
      new Wdc5MetadataInspector.Limits(4096, 10000, 1000, 100, 20, 1024, 4096);

  @Test
  void readsIndependentAbsoluteOffsetMetadataAndPreservesUnsignedFacts() throws Exception {
    var result = inspect(fixture(), 1000);
    assertEquals(2, result.sections().size());
    assertEquals(2, result.fields().size());
    assertEquals(2, result.storage().size());
    assertEquals(340, result.metadataBytesRead());
    assertEquals(356, result.declaredAuxiliaryDataEnd());
    var first = result.sections().getFirst();
    assertEquals("fedcba9876543210", first.encryptionKeyIdentifier());
    assertTrue(first.encryptionKeyDeclared());
    assertEquals(400, first.fileOffset());
    assertEquals(2, first.recordCount());
    assertEquals(2, first.stringTableSize());
    assertEquals(8, first.idListSize());
    assertEquals(1, first.copyTableCount());
    assertEquals(426, first.declaredEndOffset());
    assertEquals(-3, result.fields().getFirst().bits());
    assertEquals(-2, result.fields().getFirst().offset());
    var pallet = result.storage().getFirst();
    assertEquals(Wdc5Metadata.StorageKind.PALLET, pallet.kind().orElseThrow());
    assertEquals(3, pallet.rawKind());
    assertEquals(8, pallet.additionalDataSize());
    assertEquals(0xffffffffL, pallet.parameter1());
    assertEquals(5, pallet.parameter2());
    assertEquals(1, pallet.parameter3());
    assertThrows(UnsupportedOperationException.class, () -> result.sections().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.storage().clear());
  }

  @Test
  void capabilitiesAlwaysSeparateMetadataFromRowsSchemaAndEncryptionAvailability()
      throws Exception {
    var metadata = inspect(fixture(), 1000);
    assertTrue(metadata.capabilities().contains(Wdc5Metadata.Capability.ENCRYPTION_KEY_DECLARED));
    assertTrue(metadata.capabilities().contains(Wdc5Metadata.Capability.BODY_NOT_VALIDATED));
    assertTrue(
        metadata.capabilities().contains(Wdc5Metadata.Capability.RECORD_DECODER_UNAVAILABLE));
    assertTrue(
        metadata.capabilities().contains(Wdc5Metadata.Capability.SCHEMA_SEMANTICS_UNVERIFIED));
    assertThrows(UnsupportedOperationException.class, () -> metadata.capabilities().clear());
  }

  @Test
  void unknownStorageKindsStayRawAndExplicitlyUnsupportedRatherThanBeingGuessed() throws Exception {
    byte[] input = fixture();
    put32(input, 292 + 8, -1);
    var metadata = inspect(input, 1000);
    assertEquals(0xffffffffL, metadata.storage().getFirst().rawKind());
    assertTrue(metadata.storage().getFirst().kind().isEmpty());
    assertTrue(metadata.capabilities().contains(Wdc5Metadata.Capability.UNKNOWN_STORAGE_KIND));
    assertTrue(
        metadata.capabilities().contains(Wdc5Metadata.Capability.AUXILIARY_ACCOUNTING_UNVERIFIED));
  }

  @Test
  void truncatedMetadataAtEveryByteCannotPublishPartialDescriptors() {
    byte[] full = fixture();
    for (int length = 0; length < full.length; length++) {
      byte[] prefix = Arrays.copyOf(full, length);
      failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(prefix, 1000));
    }
  }

  @Test
  void sectionRangesMustFitDeclaredSourceAndSparseEndCannotRunBackward() throws Exception {
    byte[] outOfBounds = fixture();
    put32(outOfBounds, 204 + 8, 990);
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(outOfBounds, 1000));
    byte[] sparse = fixture();
    put16(sparse, 172, 5);
    put32(sparse, 204 + 20, 399);
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(sparse, 1000));
    put32(sparse, 204 + 20, 410);
    put32(sparse, 244 + 20, 604);
    assertTrue(
        inspect(sparse, 1000)
            .capabilities()
            .contains(Wdc5Metadata.Capability.SPARSE_LAYOUT_OBSERVED));
  }

  @Test
  void verifiesKnownCommonPalletAccountingAndElementWidths() {
    byte[] pallet = fixture();
    put32(pallet, 292 + 4, 6);
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(pallet, 1000));
    byte[] common = fixture();
    put32(common, 316 + 4, 4);
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(common, 1000));
    byte[] declared = fixture();
    put32(declared, 196, 12);
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(declared, 1000));
    byte[] columnWidth = fixture();
    put32(columnWidth, 188, 24);
    failure(Db2HeaderException.Reason.UNSUPPORTED, () -> inspect(columnWidth, 1000));
  }

  @Test
  void countIndexWidthsAndMetadataPrefixesHaveIndependentLimits() {
    byte[] count = fixture();
    put32(count, 204 + 12, -1);
    failure(Db2HeaderException.Reason.LIMIT, () -> inspect(count, 1000));
    byte[] idWidth = fixture();
    put32(idWidth, 204 + 24, 3);
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(idWidth, 1000));
    byte[] additional = fixture();
    put32(additional, 292 + 4, -1);
    failure(Db2HeaderException.Reason.LIMIT, () -> inspect(additional, 1000));
    failure(Db2HeaderException.Reason.LIMIT, () -> inspect(new byte[4097], 1000));
    failure(Db2HeaderException.Reason.LIMIT, () -> inspect(fixture(), 10001));
  }

  @Test
  void checkedRecordArithmeticRejectsOverflowBeforeUsingRanges() {
    byte[] input = fixture();
    put32(input, 136, -1);
    put32(input, 144, -1);
    put32(input, 204 + 12, -1);
    var generous =
        new Wdc5MetadataInspector.Limits(
            4096, Long.MAX_VALUE, 0xffffffffL, 100, 20, 0xffffffffL, 4096);
    failure(
        Db2HeaderException.Reason.MALFORMED,
        () -> inspector.inspect(input, Long.MAX_VALUE, generous, () -> false));
  }

  @Test
  void metadataOnlyPrefixIgnoresUninspectedBodyAndRejectsDeclaredLengthTooSmall() throws Exception {
    byte[] full = Arrays.copyOf(fixture(), 500);
    Arrays.fill(full, 340, 500, (byte) 0xff);
    assertEquals(inspect(fixture(), 1000).storage(), inspect(full, 1000).storage());
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(fixture(), 330));
    byte[] wrongCount = fixture();
    put32(wrongCount, 136, 4);
    failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(wrongCount, 1000));
  }

  @Test
  void cancellationAndUnsupportedHeaderVariantsRemainTyped() {
    failure(
        Db2HeaderException.Reason.CANCELLED,
        () -> inspector.inspect(new byte[0], 1000, limits, () -> true));
    int[] polls = {0};
    failure(
        Db2HeaderException.Reason.CANCELLED,
        () -> inspector.inspect(fixture(), 1000, limits, () -> ++polls[0] > 3));
    byte[] magic = fixture();
    magic[3] = '3';
    failure(Db2HeaderException.Reason.UNSUPPORTED, () -> inspect(magic, 1000));
  }

  @Test
  void sparseMapAndIdArraysCountSeparatelyAroundRelationshipData() throws Exception {
    for (int flags : new int[] {5, 7}) {
      byte[] input = fixture();
      put16(input, 172, flags);
      put32(input, 204 + 16, 0);
      put32(input, 204 + 20, 410);
      put32(input, 204 + 28, 12);
      put32(input, 204 + 32, 2);
      put32(input, 244 + 20, 604);
      var first = inspect(input, 1000).sections().getFirst();
      // 400 + 10 record bytes + 8 ID bytes + 8 copy bytes + 12 sparse map bytes
      // + 8 sparse ID bytes + 12 relationship bytes. SecondaryKey changes order, not extent.
      assertEquals(458, first.declaredEndOffset());
      assertEquals(2, first.offsetMapIdCount());
      assertEquals(12, first.relationshipDataSize());
    }
  }

  @Test
  void allSixPinnedStorageModeNumbersRemainDistinctWithoutDecodingParameters() throws Exception {
    byte[] input = new byte[372];
    var bytes = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put(new byte[] {'W', 'D', 'C', '5'});
    bytes.putInt(4, 5);
    bytes.putInt(140, 6);
    bytes.putInt(176, 6);
    bytes.putInt(188, 144);
    for (int mode = 0; mode < 6; mode++) {
      bytes.putInt(228 + 24 * mode + 8, mode);
      bytes.putInt(228 + 24 * mode + 12, -1);
    }
    var metadata = inspect(input, 1000);
    for (int mode = 0; mode < 6; mode++) {
      assertEquals(mode, metadata.storage().get(mode).rawKind());
      assertEquals(
          Wdc5Metadata.StorageKind.values()[mode],
          metadata.storage().get(mode).kind().orElseThrow());
      assertEquals(0xffffffffL, metadata.storage().get(mode).parameter1());
    }
  }

  @Test
  void relationshipDeclarationsNeedHeaderAndWholeReferenceEntries() {
    for (int size : new int[] {1, 4, 11, 13, 14, 19}) {
      byte[] input = fixture();
      put32(input, 204 + 28, size);
      failure(Db2HeaderException.Reason.MALFORMED, () -> inspect(input, 1000));
    }
  }

  private Wdc5Metadata inspect(byte[] input, long decodedLength) throws Db2HeaderException {
    return inspector.inspect(input, decodedLength, limits, () -> false);
  }

  private static void failure(
      Db2HeaderException.Reason reason, org.junit.jupiter.api.function.Executable action) {
    assertEquals(reason, assertThrows(Db2HeaderException.class, action).reason());
  }

  private static byte[] fixture() {
    byte[] data = new byte[340];
    var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {'W', 'D', 'C', '5'});
    b.putInt(4, 5);
    b.putInt(136, 3);
    b.putInt(140, 2);
    b.putInt(144, 4);
    b.putInt(148, 2);
    b.putInt(152, 0x50238ec2);
    b.putInt(156, 0x996192aa);
    b.putShort(172, (short) 4);
    b.putShort(174, (short) 0xffff);
    b.putInt(176, 2);
    b.putInt(188, 48);
    b.putInt(192, 8);
    b.putInt(196, 8);
    b.putInt(200, 2);
    // Section 1: eight-byte key + eight independent u32 fields = 40 bytes.
    b.putLong(204, 0xfedcba9876543210L);
    b.putInt(212, 400);
    b.putInt(216, 2);
    b.putInt(220, 2);
    b.putInt(228, 8);
    b.putInt(240, 1);
    // Section 2.
    b.putInt(252, 600);
    b.putInt(256, 1);
    b.putInt(268, 4);
    // Fields begin at 204 + 2*40 = 284, each pair of signed shorts is four bytes.
    b.putShort(284, (short) -3);
    b.putShort(286, (short) -2);
    b.putShort(288, (short) 24);
    b.putShort(290, (short) 1);
    // Storage descriptors begin at 292, each 24 bytes, preserve all three union words.
    b.putShort(292, (short) 0xffff);
    b.putShort(294, (short) 32);
    b.putInt(296, 8);
    b.putInt(300, 3);
    b.putInt(304, -1);
    b.putInt(308, 5);
    b.putInt(312, 1);
    b.putInt(320, 8);
    b.putInt(324, 2);
    b.putInt(328, 42);
    return data;
  }

  private static void put32(byte[] data, int offset, int value) {
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
  }

  private static void put16(byte[] data, int offset, int value) {
    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
  }
}
