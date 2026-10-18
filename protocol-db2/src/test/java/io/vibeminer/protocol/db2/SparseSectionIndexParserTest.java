package io.vibeminer.protocol.db2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SparseSectionIndexParserTest {
  private final SparseSectionIndexParser parser = new SparseSectionIndexParser();
  private final SparseSectionIndexParser.Limits limits =
      new SparseSectionIndexParser.Limits(1024, 20, 20, 20);

  private Wdc5Header header(int flags) {
    return new Wdc5Header(5, "", 2, 1, 3, 0, 1, 2, 0, 0, 0, flags, 0xffff, 1, 0, 0, 24, 0, 0, 1);
  }

  private Wdc5Metadata.Section section() {
    return new Wdc5Metadata.Section("0000000000000000", 100, 2, 0, 120, 8, 0, 2, 2, 164);
  }

  private byte[] suffix() {
    var b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(42).putInt(0);
    b.putInt(90).putInt(91).putInt(91).putInt(90);
    b.putInt(-1).putShort((short) 65535).putInt(0).putShort((short) 0);
    b.putInt(43).putInt(-1);
    return b.array();
  }

  private SparseSectionIndex parse(byte[] input, Wdc5Header header, Wdc5Metadata.Section section)
      throws Exception {
    return parser.parse(input, header, section, limits, () -> false);
  }

  @Test
  void preservesBothIdListsRawMapsAndUnresolvedCopiesWithoutReordering() throws Exception {
    byte[] input = suffix();
    var result = parse(input, header(5), section());
    assertEquals(List.of(42L, 0L), result.recordIds());
    assertEquals(List.of(43L, 0xffffffffL), result.mapIds());
    assertEquals(
        List.of(
            new SparseSectionIndex.MapEntry(0xffffffffL, 65535),
            new SparseSectionIndex.MapEntry(0, 0)),
        result.mapEntries());
    assertEquals(
        List.of(new SparseSectionIndex.Copy(90, 91), new SparseSectionIndex.Copy(91, 90)),
        result.copies());
    assertEquals(120, result.suffixOffset());
    assertEquals(128, result.copyTableOffset());
    assertEquals(144, result.offsetMapOffset());
    assertEquals(156, result.mapIdListOffset());
    assertEquals(164, result.declaredEndOffset());
    assertTrue(
        result.capabilities().contains(SparseSectionIndex.Capability.RAW_OFFSET_ORIGIN_UNVERIFIED));
    Arrays.fill(input, (byte) 0);
    assertEquals(42, result.recordIds().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> result.mapEntries().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.mapIds().clear());
  }

  @Test
  void rejectsEveryIncompleteAndTrailingSpan() {
    for (int n = 0; n <= 45; n++)
      if (n != 44) {
        byte[] input = Arrays.copyOf(suffix(), n);
        assertThrows(Db2HeaderException.class, () -> parse(input, header(5), section()));
      }
  }

  @Test
  void rejectsUnsupportedFlagsEncryptionAndRelationships() {
    for (int flags : new int[] {0, 1, 4, 7, 13, 0x25})
      assertEquals(
          Db2HeaderException.Reason.UNSUPPORTED,
          assertThrows(Db2HeaderException.class, () -> parse(suffix(), header(flags), section()))
              .reason());
    for (String key : new String[] {null, "0000000000000001"}) {
      var s = new Wdc5Metadata.Section(key, 100, 2, 0, 120, 8, 0, 2, 2, 164);
      assertEquals(
          Db2HeaderException.Reason.UNSUPPORTED,
          assertThrows(Db2HeaderException.class, () -> parse(suffix(), header(5), s)).reason());
    }
    var s = new Wdc5Metadata.Section("0000000000000000", 100, 2, 0, 120, 8, 12, 2, 2, 176);
    assertEquals(
        Db2HeaderException.Reason.UNSUPPORTED,
        assertThrows(Db2HeaderException.class, () -> parse(suffix(), header(5), s)).reason());
  }

  @Test
  void requiresRecordIdAndMapCardinalityWithinNarrowSubset() {
    for (long ids : new long[] {0, 4, 7, 9, 12}) {
      var s = new Wdc5Metadata.Section("0000000000000000", 100, 2, 0, 120, ids, 0, 2, 2, 164);
      assertThrows(Db2HeaderException.class, () -> parse(suffix(), header(5), s));
    }
    for (long maps : new long[] {0, 1, 3}) {
      var s = new Wdc5Metadata.Section("0000000000000000", 100, 2, 0, 120, 8, 0, maps, 2, 164);
      assertThrows(Db2HeaderException.class, () -> parse(suffix(), header(5), s));
    }
  }

  @Test
  void boundsEachIndependentCollectionAndSuffixBeforeAllocation() {
    for (var bound :
        List.of(
            new SparseSectionIndexParser.Limits(43, 20, 20, 20),
            new SparseSectionIndexParser.Limits(1024, 1, 20, 20),
            new SparseSectionIndexParser.Limits(1024, 20, 1, 20),
            new SparseSectionIndexParser.Limits(1024, 20, 20, 1)))
      assertEquals(
          Db2HeaderException.Reason.LIMIT,
          assertThrows(
                  Db2HeaderException.class,
                  () -> parser.parse(suffix(), header(5), section(), bound, () -> false))
              .reason());
  }

  @Test
  void rejectsInvalidCallerGeometryAsTypedFailure() {
    for (long end : new long[] {-1, 99, 119, 0x100000000L}) {
      var s = new Wdc5Metadata.Section("0000000000000000", 100, 2, 0, end, 8, 0, 2, 2, 164);
      assertThrows(Db2HeaderException.class, () -> parse(suffix(), header(5), s));
    }
    var s = new Wdc5Metadata.Section("0000000000000000", 100, 2, 0, 120, 8, 0, 2, 2, 163);
    assertThrows(Db2HeaderException.class, () -> parse(suffix(), header(5), s));
  }

  @Test
  void handlesEmptySparseSectionWithoutInventingEntries() throws Exception {
    var s = new Wdc5Metadata.Section("0000000000000000", 100, 0, 0, 100, 0, 0, 0, 0, 100);
    var result = parse(new byte[0], header(5), s);
    assertTrue(result.recordIds().isEmpty());
    assertTrue(result.mapEntries().isEmpty());
  }

  @Test
  void rejectsKnownReferenceFixtureSpecialGrammarInsteadOfGuessing() {
    var h = header(5);
    var special =
        new Wdc5Header(5, "", 2, 1, 3, 0, 145293629, 2, 0, 0, 0, 5, 0xffff, 1, 0, 0, 24, 0, 0, 1);
    assertEquals(
        Db2HeaderException.Reason.UNSUPPORTED,
        assertThrows(Db2HeaderException.class, () -> parse(suffix(), special, section())).reason());
  }

  @Test
  void cancellationBeforeAndWithinSuffixIsTyped() {
    assertEquals(
        Db2HeaderException.Reason.CANCELLED,
        assertThrows(
                Db2HeaderException.class,
                () -> parser.parse(suffix(), header(5), section(), limits, () -> true))
            .reason());
    AtomicInteger calls = new AtomicInteger();
    assertEquals(
        Db2HeaderException.Reason.CANCELLED,
        assertThrows(
                Db2HeaderException.class,
                () ->
                    parser.parse(
                        suffix(), header(5), section(), limits, () -> calls.incrementAndGet() > 4))
            .reason());
  }
}
