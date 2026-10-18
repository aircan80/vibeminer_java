package io.vibeminer.format.wow.root;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectedRootLookupTest {
  final SelectedRootLookup lookup = new SelectedRootLookup();
  final SelectedRootLookup.Limits limits = new SelectedRootLookup.Limits(8192, 100, 10, 50, 10, 20);

  @Test
  void fragmentedStreamsPreserveAllRequestedVariantsAndMatchCompleteParser() throws Exception {
    byte[] bytes =
        WowRootParserTest.fixture2(
            new WowRootParserTest.Block2(
                0, 0, 0, 2, new long[] {5, 2}, new int[] {1, 2}, new long[] {11, 12}),
            new WowRootParserTest.Block2(
                0x10000000L, 0, 4, 16, new long[] {5}, new int[] {3}, null));
    var full =
        new WowRootParser().parse(bytes, new WowRootParser.Limits(8192, 100, 10, 50), () -> false);
    for (int fragment : new int[] {1, 2, 7, 8192}) {
      var stream = new Fragmented(bytes, fragment);
      var selected = lookup.read(stream, Set.of(5L, 8L, 999L), limits, () -> false);
      assertEquals(full.findFileDataId(5), selected.findFileDataId(5));
      assertEquals(full.findFileDataId(8), selected.findFileDataId(8));
      assertTrue(selected.findFileDataId(999).isEmpty());
      assertFalse(stream.closed);
      assertEquals(bytes.length, stream.position);
      assertEquals(3, selected.totalRecordCount());
      assertEquals(2, selected.namedRecordCount());
      assertEquals(bytes.length, selected.decodedBytesRead());
      assertEquals(
          SelectedRootMemberships.StructuralCoverage.COMPLETE_SUPPORTED_STREAM,
          selected.structuralCoverage());
      assertThrows(IllegalArgumentException.class, () -> selected.findFileDataId(7));
      assertThrows(
          UnsupportedOperationException.class, () -> selected.requestedFileDataIds().clear());
      assertThrows(UnsupportedOperationException.class, () -> selected.findFileDataId(5).clear());
    }
  }

  @Test
  void versionOneHeaderShapesHaveParityIncludingPresentZeroNameHash() throws Exception {
    for (int header : new int[] {20, 24}) {
      byte[] bytes =
          WowRootParserTest.fixture(
              header,
              new WowRootParserTest.Block(0, 0, new long[] {0}, new int[] {1}, new long[] {0}));
      var result = read(bytes, Set.of(0L));
      assertEquals(1, result.version());
      assertEquals(header, result.headerSize());
      assertEquals(Optional.of("0000000000000000"), result.findFileDataId(0).getFirst().nameHash());
    }
  }

  @Test
  void generatedLargeSkippedCohortUsesBoundedReadsAndOnlyRetainsRequestedRows() throws Exception {
    var stream = new GeneratedRoot(100000);
    var generous = new SelectedRootLookup.Limits(2_100_000, 100000, 1, 100000, 3, 3);
    var result = lookup.read(stream, Set.of(99998L, 99999L, 100001L), generous, () -> false);
    assertEquals(100000, result.totalRecordCount());
    assertEquals(2, result.matchCount());
    assertTrue(result.findFileDataId(100001).isEmpty());
    assertEquals(
        "01010101010101010101010101010101", result.findFileDataId(99999).getFirst().contentKey());
    assertTrue(stream.largestRead <= 8192);
    assertFalse(stream.closed);
    assertEquals(stream.length, stream.position);
  }

  @Test
  void absenceAndFoundResultsRequireValidationOfEntireTail() throws Exception {
    byte[] good =
        WowRootParserTest.fixture(
            20, new WowRootParserTest.Block(0x10000000L, 2, new long[] {1}, new int[] {1}, null));
    byte[] trailing = Arrays.copyOf(good, good.length + 1);
    for (Set<Long> ids : List.of(Set.of(1L), Set.of(999L), Set.<Long>of()))
      failure(StreamingRootException.Reason.MALFORMED, () -> read(trailing, ids));
    byte[] badCounts = good.clone();
    WowRootParserTest.put32(badCounts, 12, 2);
    failure(StreamingRootException.Reason.MALFORMED, () -> read(badCounts, Set.of(999L)));
    byte[] overflow =
        WowRootParserTest.fixture(
            20,
            new WowRootParserTest.Block(
                0x10000000L, 2, new long[] {0xffffffffL, 0}, new int[] {1, 2}, null));
    failure(StreamingRootException.Reason.MALFORMED, () -> read(overflow, Set.of(999L)));
  }

  @Test
  void everyTruncationIsTypedAndNeverClosesCaller() throws Exception {
    byte[] bytes =
        WowRootParserTest.fixture2(
            new WowRootParserTest.Block2(
                0, 0, 0, 2, new long[] {1, 1}, new int[] {1, 2}, new long[] {1, 2}));
    for (int length = 0; length < bytes.length; length++) {
      var stream = new Fragmented(Arrays.copyOf(bytes, length), 1);
      failure(
          StreamingRootException.Reason.MALFORMED,
          () -> lookup.read(stream, Set.of(1L), limits, () -> false));
      assertFalse(stream.closed);
    }
  }

  @Test
  void boundsRequestedIdsMatchesInputAndCountsBeforeRetaining() throws Exception {
    byte[] bytes =
        WowRootParserTest.fixture2(
            new WowRootParserTest.Block2(
                0x10000000L, 0, 0, 2, new long[] {1}, new int[] {1}, null));
    failure(
        StreamingRootException.Reason.LIMIT,
        () ->
            lookup.read(
                new ByteArrayInputStream(bytes),
                Set.of(1L),
                new SelectedRootLookup.Limits(8192, 100, 10, 50, 0, 20),
                () -> false));
    failure(
        StreamingRootException.Reason.LIMIT,
        () ->
            lookup.read(
                new ByteArrayInputStream(bytes),
                Set.of(1L),
                new SelectedRootLookup.Limits(8192, 100, 10, 50, 10, 0),
                () -> false));
    failure(
        StreamingRootException.Reason.LIMIT,
        () ->
            lookup.read(
                new ByteArrayInputStream(bytes),
                Set.of(1L),
                new SelectedRootLookup.Limits(bytes.length - 1, 100, 10, 50, 10, 20),
                () -> false));
    failure(
        StreamingRootException.Reason.LIMIT,
        () ->
            lookup.read(
                new ByteArrayInputStream(bytes),
                Set.of(),
                new SelectedRootLookup.Limits(8192, 0, 10, 50, 10, 20),
                () -> false));
    assertThrows(IllegalArgumentException.class, () -> read(bytes, Set.of(-1L)));
  }

  @Test
  void typedIoAndCancellationRemainDistinctWithCallerOwnership() throws Exception {
    InputStream broken =
        new InputStream() {
          public int read() throws IOException {
            throw new IOException("synthetic failure");
          }
        };
    var failure =
        assertThrows(
            StreamingRootException.class, () -> lookup.read(broken, Set.of(), limits, () -> false));
    assertEquals(StreamingRootException.Reason.IO, failure.reason());
    assertInstanceOf(IOException.class, failure.getCause());
    failure(
        StreamingRootException.Reason.CANCELLED,
        () -> lookup.read(new ByteArrayInputStream(new byte[0]), Set.of(), limits, () -> true));
    var stream = new GeneratedRoot(100000);
    int[] polls = {0};
    failure(
        StreamingRootException.Reason.CANCELLED,
        () ->
            lookup.read(
                stream,
                Set.of(),
                new SelectedRootLookup.Limits(2_100_000, 100000, 1, 100000, 1, 1),
                () -> ++polls[0] > 100));
    assertFalse(stream.closed);
  }

  @Test
  void unsupportedVersionsRemainStrictWithoutFallback() throws Exception {
    byte[] bytes =
        WowRootParserTest.fixture2(
            new WowRootParserTest.Block2(
                0x20, 0, 0, 2, new long[] {1}, new int[] {1}, new long[] {1}));
    byte[] version = bytes.clone();
    WowRootParserTest.put32(version, 8, 3);
    failure(StreamingRootException.Reason.UNSUPPORTED, () -> read(version, Set.of(1L)));
  }

  @Test
  void zeroProgressBulkReadsFallbackWithoutSpinningAndExactLimitAllowsEof() throws Exception {
    byte[] empty = WowRootParserTest.fixture(20);
    var stream =
        new ByteArrayInputStream(empty) {
          public synchronized int read(byte[] b, int off, int len) {
            return 0;
          }
        };
    assertEquals(
        0,
        lookup
            .read(stream, Set.of(), new SelectedRootLookup.Limits(20, 0, 0, 0, 0, 0), () -> false)
            .totalRecordCount());
  }

  @Test
  void eofIoFailureCannotTurnFoundOrAbsentMembershipIntoSuccess() throws Exception {
    byte[] bytes =
        WowRootParserTest.fixture(
            20, new WowRootParserTest.Block(0x10000000L, 2, new long[] {1}, new int[] {1}, null));
    for (Set<Long> ids : List.of(Set.of(1L), Set.of(999L))) {
      InputStream input =
          new InputStream() {
            int at;

            public int read() throws IOException {
              if (at == bytes.length) throw new IOException("synthetic tail failure");
              return bytes[at++] & 255;
            }
          };
      failure(StreamingRootException.Reason.IO, () -> lookup.read(input, ids, limits, () -> false));
    }
  }

  @Test
  void totalBlocksPerBlockAndRepeatedOutputHaveIndependentLimits() throws Exception {
    byte[] bytes =
        WowRootParserTest.fixture2(
            new WowRootParserTest.Block2(0x10000000L, 0, 0, 2, new long[] {1}, new int[] {1}, null),
            new WowRootParserTest.Block2(
                0x10000000L, 0, 0, 16, new long[] {1}, new int[] {2}, null));
    for (var bounded :
        List.of(
            new SelectedRootLookup.Limits(8192, 100, 1, 50, 10, 20),
            new SelectedRootLookup.Limits(8192, 100, 10, 0, 10, 20),
            new SelectedRootLookup.Limits(8192, 100, 10, 50, 10, 1)))
      failure(
          StreamingRootException.Reason.LIMIT,
          () -> lookup.read(new ByteArrayInputStream(bytes), Set.of(1L), bounded, () -> false));
  }

  @Test
  void namedCountAndUnsupportedHeaderPaddingAreValidatedEvenWithNoRequestedIds() throws Exception {
    byte[] bytes =
        WowRootParserTest.fixture(
            24, new WowRootParserTest.Block(0, 2, new long[] {1}, new int[] {1}, new long[] {1}));
    byte[] named = bytes.clone();
    WowRootParserTest.put32(named, 16, 0);
    failure(StreamingRootException.Reason.MALFORMED, () -> read(named, Set.of()));
    byte[] padding = bytes.clone();
    padding[20] = 1;
    failure(StreamingRootException.Reason.UNSUPPORTED, () -> read(padding, Set.of()));
  }

  @Test
  void truncatedUnknownSignatureStillMatchesMandatoryPrefixFraming() {
    for (byte[] input : new byte[][] {{'X'}, {'T', 'X'}, {'T', 'S', 'X'}})
      failure(StreamingRootException.Reason.MALFORMED, () -> read(input, Set.of()));
  }

  @Test
  void opaqueFlagsHaveFragmentedStreamingParityForBothNameArrayShapes() throws Exception {
    for (boolean nameless : new boolean[] {false, true}) {
      long flags1 = 0x20;
      long flags2 = nameless ? 0x10000040L : 0x40;
      long[] hashes = nameless ? null : new long[] {0xfedcba9876543210L};
      byte[] bytes =
          WowRootParserTest.fixture2(
              new WowRootParserTest.Block2(
                  flags1, flags2, 0xff, 2, new long[] {1}, new int[] {1}, hashes));
      var full =
          new WowRootParser()
              .parse(bytes, new WowRootParser.Limits(8192, 100, 10, 50), () -> false);
      for (int fragment : new int[] {1, 7}) {
        var result =
            lookup.read(new Fragmented(bytes, fragment), Set.of(1L, 999L), limits, () -> false);
        assertEquals(full.findFileDataId(1), result.findFileDataId(1));
        assertTrue(result.findFileDataId(999).isEmpty());
        assertEquals(0x20, result.findFileDataId(1).getFirst().rawContentFlags().first());
        assertEquals(flags2, result.findFileDataId(1).getFirst().rawContentFlags().second());
        assertEquals(0xff, result.findFileDataId(1).getFirst().rawContentFlags().third());
      }
    }
  }

  @Test
  void malformedOpaqueFlagStreamHasSafeVersionOffsetAndRawFlagDiagnostics() throws Exception {
    byte[] full =
        WowRootParserTest.fixture2(
            new WowRootParserTest.Block2(
                0x20, 0x40, 0xff, 2, new long[] {1}, new int[] {1}, new long[] {1}));
    byte[] truncated = Arrays.copyOf(full, full.length - 1);
    var error = assertThrows(StreamingRootException.class, () -> read(truncated, Set.of(1L)));
    assertEquals(StreamingRootException.Reason.MALFORMED, error.reason());
    assertTrue(error.getMessage().contains("version=2"));
    assertTrue(error.getMessage().contains("decodedOffset="));
    assertTrue(error.getMessage().contains("rawFlags1=32"));
    assertTrue(error.getMessage().contains("rawFlags2=64"));
    assertTrue(error.getMessage().contains("rawFlags3=255"));
  }

  SelectedRootMemberships read(byte[] bytes, Set<Long> ids) throws StreamingRootException {
    return lookup.read(new ByteArrayInputStream(bytes), ids, limits, () -> false);
  }

  static void failure(
      StreamingRootException.Reason reason, org.junit.jupiter.api.function.Executable action) {
    assertEquals(reason, assertThrows(StreamingRootException.class, action).reason());
  }

  static final class Fragmented extends InputStream {
    final byte[] bytes;
    final int fragment;
    int position;
    boolean closed;

    Fragmented(byte[] bytes, int fragment) {
      this.bytes = bytes;
      this.fragment = fragment;
    }

    public int read() {
      return position == bytes.length ? -1 : bytes[position++] & 255;
    }

    public int read(byte[] target, int offset, int count) {
      if (position == bytes.length) return -1;
      int n = Math.min(Math.min(count, fragment), bytes.length - position);
      System.arraycopy(bytes, position, target, offset, n);
      position += n;
      return n;
    }

    public void close() {
      closed = true;
    }
  }

  static final class GeneratedRoot extends InputStream {
    final byte[] header = new byte[41];
    final long length;
    final int records;
    long position;
    int largestRead;
    boolean closed;

    GeneratedRoot(int records) {
      this.records = records;
      length = 41L + 20L * records;
      var b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
      b.put(new byte[] {'T', 'S', 'F', 'M'})
          .putInt(24)
          .putInt(2)
          .putInt(records)
          .putInt(0)
          .putInt(0)
          .putInt(records)
          .putInt(2)
          .putInt(0x10000000)
          .putInt(0)
          .put((byte) 0);
    }

    public int read() {
      if (position == length) return -1;
      long index = position++;
      return index < header.length ? header[(int) index] & 255 : index < 41L + 4L * records ? 0 : 1;
    }

    public int read(byte[] target, int offset, int count) {
      largestRead = Math.max(largestRead, count);
      if (position == length) return -1;
      int n = (int) Math.min(Math.min(count, 257), length - position);
      for (int i = 0; i < n; i++) target[offset + i] = (byte) read();
      return n;
    }

    public void close() {
      closed = true;
    }
  }
}
