package io.vibeminer.protocol.db2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CStringPrefixReaderTest {
  private final CStringPrefixReader reader = new CStringPrefixReader();
  private final CStringPrefixReader.Limits limits = new CStringPrefixReader.Limits(100, 5, 30, 100);

  @Test
  void readsExactRequestedPrefixIncludingEmptyAndMultibyteStrings() throws Exception {
    byte[] bytes = "\0héllo\0third\0unread".getBytes(StandardCharsets.UTF_8);
    var result = reader.read(bytes, 3, limits, () -> false);
    assertEquals(List.of("", "héllo", "third"), result.strings());
    assertEquals(14, result.bytesConsumed());
    assertThrows(UnsupportedOperationException.class, () -> result.strings().clear());
  }

  @Test
  void cannotReadNeighborOrRepairUnterminatedRecordSlice() {
    assertEquals(
        SparseRecordException.Reason.MALFORMED,
        assertThrows(
                SparseRecordException.class,
                () -> reader.read(new byte[] {'x'}, 1, limits, () -> false))
            .reason());
    assertThrows(
        SparseRecordException.class,
        () -> reader.read(new byte[] {'x', 0, 'y'}, 2, limits, () -> false));
  }

  @Test
  void rejectsInvalidUtf8ButIgnoresUnrequestedTail() throws Exception {
    assertThrows(
        SparseRecordException.class,
        () -> reader.read(new byte[] {(byte) 0xc0, (byte) 0x80, 0}, 1, limits, () -> false));
    assertEquals(
        List.of("a"),
        reader.read(new byte[] {'a', 0, (byte) 0xff}, 1, limits, () -> false).strings());
  }

  @Test
  void enforcesRecordStringCountAndPrefixBudgets() {
    for (var bound :
        List.of(
            new CStringPrefixReader.Limits(1, 5, 30, 100),
            new CStringPrefixReader.Limits(100, 0, 30, 100),
            new CStringPrefixReader.Limits(100, 5, 0, 100),
            new CStringPrefixReader.Limits(100, 5, 30, 1)))
      assertEquals(
          SparseRecordException.Reason.LIMIT,
          assertThrows(
                  SparseRecordException.class,
                  () -> reader.read(new byte[] {'x', 0}, 1, bound, () -> false))
              .reason());
  }

  @Test
  void impossibleTerminatorCountIsRejectedBeforeCollectionAllocation() {
    var permissive =
        new CStringPrefixReader.Limits(
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertEquals(
        SparseRecordException.Reason.MALFORMED,
        assertThrows(
                SparseRecordException.class,
                () -> reader.read(new byte[0], Integer.MAX_VALUE, permissive, () -> false))
            .reason());
  }

  @Test
  void zeroStringsAndCancellationAreExplicit() throws Exception {
    assertEquals(0, reader.read(new byte[0], 0, limits, () -> false).bytesConsumed());
    AtomicInteger calls = new AtomicInteger();
    assertEquals(
        SparseRecordException.Reason.CANCELLED,
        assertThrows(
                SparseRecordException.class,
                () ->
                    reader.read(
                        new byte[] {'a', 'b', 0}, 1, limits, () -> calls.incrementAndGet() > 2))
            .reason());
    assertThrows(
        IllegalArgumentException.class, () -> reader.read(new byte[0], -1, limits, () -> false));
  }
}
