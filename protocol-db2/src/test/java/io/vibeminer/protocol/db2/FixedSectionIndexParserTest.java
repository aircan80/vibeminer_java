package io.vibeminer.protocol.db2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FixedSectionIndexParserTest {
  private final FixedSectionIndexParser parser = new FixedSectionIndexParser();
  private final FixedSectionIndexParser.Limits limits =
      new FixedSectionIndexParser.Limits(1024, 20, 20);

  private Wdc5Header header(int flags) {
    return new Wdc5Header(5, "", 2, 1, 3, 0, 1, 2, 0, 0, 0, flags, 0xffff, 1, 0, 0, 24, 0, 0, 1);
  }

  private Wdc5Metadata.Section section(String key, long ids, long copies, long end) {
    return new Wdc5Metadata.Section(key, 100, 2, 2, 0, ids, 0, 0, copies, end);
  }

  private Wdc5Metadata.Section section() {
    return section("0000000000000000", 8, 3, 140);
  }

  private byte[] bytes() {
    var b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
    b.position(8);
    b.putInt(42).putInt(-1);
    b.putInt(90).putInt(91);
    b.putInt(91).putInt(42);
    b.putInt(90).putInt(90);
    return b.array();
  }

  private FixedSectionIndex parse(byte[] bytes, Wdc5Header header, Wdc5Metadata.Section section)
      throws Exception {
    return parser.parse(bytes, header, section, limits, () -> false);
  }

  @Test
  void readsIdsAndRawCopiesWithoutResolvingForwardOrRepeatedTargets() throws Exception {
    byte[] input = bytes();
    var result = parse(input, header(4), section());
    assertEquals(List.of(42L, 0xffffffffL), result.recordIds());
    assertEquals(
        List.of(
            new FixedSectionIndex.Copy(90, 91),
            new FixedSectionIndex.Copy(91, 42),
            new FixedSectionIndex.Copy(90, 90)),
        result.copies());
    assertEquals(100, result.recordOffset(0));
    assertEquals(103, result.recordOffset(1));
    assertEquals(106, result.stringTableOffset());
    assertEquals(108, result.idListOffset());
    assertEquals(116, result.copyTableOffset());
    assertThrows(IndexOutOfBoundsException.class, () -> result.recordOffset(2));
    Arrays.fill(input, (byte) 0);
    assertEquals(42, result.recordIds().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> result.recordIds().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.copies().clear());
  }

  @Test
  void preservesZeroIdsRatherThanInventingDbcdSequentialFallback() throws Exception {
    byte[] input = bytes();
    Arrays.fill(input, 8, 16, (byte) 0);
    assertEquals(List.of(0L, 0L), parse(input, header(4), section()).recordIds());
  }

  @Test
  void rejectsEveryTruncatedOrTrailingProvidedSpan() {
    for (int n = 0; n <= 41; n++)
      if (n != 40) {
        byte[] input = Arrays.copyOf(bytes(), n);
        assertThrows(Db2HeaderException.class, () -> parse(input, header(4), section()));
      }
  }

  @Test
  void requiresExactIdCardinalityAndDeclaredEnd() {
    for (long ids : new long[] {0, 4, 7, 9, 12})
      assertThrows(
          Db2HeaderException.class,
          () -> parse(bytes(), header(4), section("0000000000000000", ids, 3, 140)));
    assertThrows(
        Db2HeaderException.class,
        () -> parse(bytes(), header(4), section("0000000000000000", 8, 3, 139)));
  }

  @Test
  void rejectsUnsupportedSparseInlineUnknownEncryptedAndRelationshipModes() {
    for (int flags : new int[] {0, 1, 5, 8, 12, 0x24})
      assertEquals(
          Db2HeaderException.Reason.UNSUPPORTED,
          assertThrows(Db2HeaderException.class, () -> parse(bytes(), header(flags), section()))
              .reason());
    assertEquals(
        Db2HeaderException.Reason.UNSUPPORTED,
        assertThrows(
                Db2HeaderException.class,
                () -> parse(bytes(), header(4), section("0000000000000001", 8, 3, 140)))
            .reason());
    var relation = new Wdc5Metadata.Section("0000000000000000", 100, 2, 2, 0, 8, 12, 0, 3, 152);
    assertEquals(
        Db2HeaderException.Reason.UNSUPPORTED,
        assertThrows(Db2HeaderException.class, () -> parse(bytes(), header(4), relation)).reason());
  }

  @Test
  void rejectsSparseMetadataEvenWithFixedHeader() {
    var sparse = new Wdc5Metadata.Section("0000000000000000", 100, 2, 2, 106, 8, 0, 1, 3, 140);
    assertEquals(
        Db2HeaderException.Reason.UNSUPPORTED,
        assertThrows(Db2HeaderException.class, () -> parse(bytes(), header(4), sparse)).reason());
  }

  @Test
  void boundsEachAllocationBeforeReading() {
    for (var bound :
        List.of(
            new FixedSectionIndexParser.Limits(39, 20, 20),
            new FixedSectionIndexParser.Limits(1024, 1, 20),
            new FixedSectionIndexParser.Limits(1024, 20, 2)))
      assertEquals(
          Db2HeaderException.Reason.LIMIT,
          assertThrows(
                  Db2HeaderException.class,
                  () -> parser.parse(bytes(), header(4), section(), bound, () -> false))
              .reason());
  }

  @Test
  void rejectsOverflowAndNegativeCallerMetadataAsTypedErrors() {
    var overflow =
        new Wdc5Metadata.Section("0000000000000000", Long.MAX_VALUE, 2, 2, 0, 8, 0, 0, 3, 140);
    assertThrows(Db2HeaderException.class, () -> parse(bytes(), header(4), overflow));
    var negative = new Wdc5Metadata.Section("0000000000000000", 100, -1, 2, 0, 8, 0, 0, 3, 140);
    assertThrows(Db2HeaderException.class, () -> parse(bytes(), header(4), negative));
  }

  @Test
  void rejectsSectionRecordCountAboveTableCountAndNullKeyWithoutUncheckedFailure() {
    var excessive = new Wdc5Metadata.Section("0000000000000000", 100, 3, 2, 0, 12, 0, 0, 3, 147);
    assertEquals(
        Db2HeaderException.Reason.MALFORMED,
        assertThrows(Db2HeaderException.class, () -> parse(new byte[47], header(4), excessive))
            .reason());
    assertThrows(
        Db2HeaderException.class, () -> parse(bytes(), header(4), section(null, 8, 3, 140)));
  }

  @Test
  void supportsAnEmptyExplicitSection() throws Exception {
    var empty = new Wdc5Metadata.Section("0000000000000000", 100, 0, 0, 0, 0, 0, 0, 0, 100);
    assertTrue(parse(new byte[0], header(4), empty).recordIds().isEmpty());
  }

  @Test
  void cancellationBeforeAndDuringParsingIsTyped() {
    assertEquals(
        Db2HeaderException.Reason.CANCELLED,
        assertThrows(
                Db2HeaderException.class,
                () -> parser.parse(bytes(), header(4), section(), limits, () -> true))
            .reason());
    AtomicInteger calls = new AtomicInteger();
    assertEquals(
        Db2HeaderException.Reason.CANCELLED,
        assertThrows(
                Db2HeaderException.class,
                () ->
                    parser.parse(
                        bytes(), header(4), section(), limits, () -> calls.incrementAndGet() > 2))
            .reason());
  }
}
