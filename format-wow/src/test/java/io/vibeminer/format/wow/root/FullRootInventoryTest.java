package io.vibeminer.format.wow.root;

import static io.vibeminer.format.wow.root.StreamingRootException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class FullRootInventoryTest {
  final FullRootInventory parser = new FullRootInventory();
  final FullRootInventory.Limits limits = new FullRootInventory.Limits(8192, 100, 10, 50, 1200);

  record Block(
      long first, long second, int third, long locale, long[] deltas, int[] keys, long[] names) {}

  record Row(long block, long ordinal, WowRootManifest.Entry value) {}

  static Block named() {
    return new Block(
        0x20, 0x40, 1, 0xffffffffL, new long[] {5, 2}, new int[] {11, 12}, new long[] {0, -1});
  }

  List<Row> read(byte[] bytes) throws Exception {
    var rows = new ArrayList<Row>();
    parser.read(
        new ByteArrayInputStream(bytes),
        limits,
        (b, n, e) -> rows.add(new Row(b, n, e)),
        () -> false);
    return rows;
  }

  @Test
  void streamsAllVariantsWithRawFlagsAndNonIdCounts() throws Exception {
    byte[] bytes =
        fixture(
            2,
            24,
            named(),
            new Block(WowRootParser.NO_NAMES, 0x40, 0, 2, new long[] {5}, new int[] {13}, null));
    var rows = read(bytes);
    assertEquals(3, rows.size());
    assertEquals(List.of(5L, 8L, 5L), rows.stream().map(r -> r.value.fileDataId()).toList());
    assertEquals(
        new WowRootManifest.RawContentFlags(0x20, 0x40, 1),
        rows.getFirst().value.rawContentFlags());
    assertEquals(0x20060L, rows.getFirst().value.contentFlags());
    assertEquals(0xffffffffL, rows.getFirst().value.localeFlags());
    assertEquals(Optional.of("0000000000000000"), rows.get(0).value.nameHash());
    assertEquals(Optional.of("ffffffffffffffff"), rows.get(1).value.nameHash());
    assertTrue(rows.get(2).value.nameHash().isEmpty());
    assertEquals(List.of(0L, 1L, 0L), rows.stream().map(Row::ordinal).toList());
    assertEquals(List.of(0L, 0L, 1L), rows.stream().map(Row::block).toList());
    var expected =
        new WowRootParser().parse(bytes, new WowRootParser.Limits(8192, 100, 10, 50), () -> false);
    for (var row : rows)
      assertTrue(expected.findFileDataId(row.value.fileDataId()).contains(row.value));
  }

  @Test
  void admitsBothVersionOneHeaderShapesAndEmptyRoot() throws Exception {
    for (int header : List.of(20, 24)) {
      var data =
          fixture(1, header, new Block(0, 0, 0, 2, new long[] {0}, new int[] {1}, new long[] {0}));
      assertEquals(0, read(data).getFirst().value.fileDataId());
      var result =
          parser.read(
              new ByteArrayInputStream(fixture(1, header)),
              limits,
              (b, n, e) -> fail(),
              () -> false);
      assertEquals(header, result.headerSize());
      assertEquals(0, result.membershipCount());
      assertEquals(header, result.decodedBytesRead());
    }
  }

  @Test
  void completionReportsMembershipsNamedBlocksAndBoundedWorkingBuffers() throws Exception {
    var bytes =
        fixture(
            2,
            24,
            named(),
            new Block(WowRootParser.NO_NAMES, 0, 0, 1, new long[] {2}, new int[] {9}, null));
    var result = parser.read(new ByteArrayInputStream(bytes), limits, (b, n, e) -> {}, () -> false);
    assertEquals(2, result.version());
    assertEquals(3, result.membershipCount());
    assertEquals(2, result.namedMembershipCount());
    assertEquals(2, result.blockCount());
    assertEquals(48, result.peakBlockWorkingBytes());
    assertEquals(bytes.length, result.decodedBytesRead());
  }

  @Test
  void rejectsWorkingBufferBeforeReadingAnyBlockArrays() throws Exception {
    var bytes = fixture(2, 24, named());
    var input = new Tracking(bytes, 1, false);
    var ex =
        assertThrows(
            StreamingRootException.class,
            () ->
                parser.read(
                    input,
                    new FullRootInventory.Limits(8192, 100, 10, 50, 47),
                    (b, n, e) -> fail(),
                    () -> false));
    assertEquals(LIMIT, ex.reason());
    assertTrue(ex.getMessage().contains("working"));
    assertEquals(41, input.at);
    assertFalse(input.closed);
  }

  @Test
  void exactWorkingBudgetAdmitsArraysAndHugeCountRefusesWithoutAllocation() throws Exception {
    var bytes = fixture(2, 24, named());
    parser.read(
        new ByteArrayInputStream(bytes),
        new FullRootInventory.Limits(bytes.length, 2, 1, 2, 48),
        (b, n, e) -> {},
        () -> false);
    byte[] huge = Arrays.copyOf(bytes, 41);
    put(huge, 12, 0xffffffffL);
    put(huge, 16, 0);
    put(huge, 24, 0xffffffffL);
    assertEquals(
        LIMIT,
        assertThrows(
                StreamingRootException.class,
                () ->
                    parser.read(
                        new ByteArrayInputStream(huge),
                        new FullRootInventory.Limits(
                            Long.MAX_VALUE, 0xffffffffL, 1, Integer.MAX_VALUE, Long.MAX_VALUE),
                        (b, n, e) -> fail(),
                        () -> false))
            .reason());
  }

  @Test
  void strictEofAndCountsCanFailAfterProvisionalRows() throws Exception {
    var bytes = fixture(2, 24, named());
    var rows = new AtomicInteger();
    var bad = Arrays.copyOf(bytes, bytes.length + 1);
    assertEquals(
        MALFORMED,
        assertThrows(
                StreamingRootException.class,
                () ->
                    parser.read(
                        new ByteArrayInputStream(bad),
                        limits,
                        (b, n, e) -> rows.incrementAndGet(),
                        () -> false))
            .reason());
    assertEquals(2, rows.get());
    for (int offset : List.of(12, 16)) {
      var mismatch = bytes.clone();
      put(mismatch, offset, 3);
      assertEquals(
          MALFORMED, assertThrows(StreamingRootException.class, () -> read(mismatch)).reason());
    }
  }

  @Test
  void rejectsTruncationAcrossEveryFramingPhase() throws Exception {
    var bytes = fixture(2, 24, named());
    for (int length : new int[] {0, 3, 19, 23, 26, 40, 44, 50, 70, bytes.length - 1}) {
      var ex = assertThrows(StreamingRootException.class, () -> read(Arrays.copyOf(bytes, length)));
      assertEquals(MALFORMED, ex.reason(), "length=" + length);
    }
  }

  @Test
  void unsupportedHeadersNeverGuessLegacyOrIgnoreExtension() throws Exception {
    for (int mutate : List.of(0, 4, 8, 20)) {
      var bytes = fixture(2, 24, named());
      bytes[mutate] = 99;
      assertEquals(
          UNSUPPORTED, assertThrows(StreamingRootException.class, () -> read(bytes)).reason());
    }
  }

  @Test
  void rejectsUnsignedIdOverflowButPreservesLargestId() throws Exception {
    var valid =
        fixture(
            2, 24, new Block(0, 0, 0, 0, new long[] {0xffffffffL}, new int[] {1}, new long[] {4}));
    assertEquals(0xffffffffL, read(valid).getFirst().value.fileDataId());
    var invalid =
        fixture(
            2,
            24,
            new Block(
                0, 0, 0, 0, new long[] {0xffffffffL, 0}, new int[] {1, 2}, new long[] {4, 5}));
    assertEquals(
        MALFORMED, assertThrows(StreamingRootException.class, () -> read(invalid)).reason());
  }

  @Test
  void byteRecordBlockAndPerBlockBoundsAreEnforced() throws Exception {
    byte[] bytes = fixture(2, 24, named());
    for (var bound :
        List.of(
            new FullRootInventory.Limits(bytes.length - 1, 100, 10, 50, 1200),
            new FullRootInventory.Limits(8192, 1, 10, 50, 1200),
            new FullRootInventory.Limits(8192, 100, 0, 50, 1200),
            new FullRootInventory.Limits(8192, 100, 10, 1, 1200)))
      assertEquals(
          LIMIT,
          assertThrows(
                  StreamingRootException.class,
                  () ->
                      parser.read(
                          new ByteArrayInputStream(bytes), bound, (b, n, e) -> {}, () -> false))
              .reason());
  }

  @Test
  void cancellationAfterCallbackNeverReturnsCompletionOrClosesInput() throws Exception {
    var input = new Tracking(fixture(2, 24, named()), 1, false);
    var cancelled = new AtomicBoolean();
    var count = new AtomicInteger();
    assertEquals(
        CANCELLED,
        assertThrows(
                StreamingRootException.class,
                () ->
                    parser.read(
                        input,
                        limits,
                        (b, n, e) -> {
                          count.incrementAndGet();
                          cancelled.set(true);
                        },
                        cancelled::get))
            .reason());
    assertEquals(1, count.get());
    assertFalse(input.closed);
  }

  @Test
  void cancellationDuringArraysAndFinalEofIsObserved() throws Exception {
    var bytes = fixture(2, 24, named());
    var input = new Tracking(bytes, 1, false);
    assertEquals(
        CANCELLED,
        assertThrows(
                StreamingRootException.class,
                () -> parser.read(input, limits, (b, n, e) -> fail(), () -> input.at >= 43))
            .reason());
    AtomicBoolean eofCancel = new AtomicBoolean();
    var finalStream =
        new ByteArrayInputStream(bytes) {
          @Override
          public synchronized int read(byte[] b, int o, int l) {
            int n = super.read(b, o, l);
            if (n < 0) eofCancel.set(true);
            return n;
          }
        };
    assertEquals(
        CANCELLED,
        assertThrows(
                StreamingRootException.class,
                () -> parser.read(finalStream, limits, (b, n, e) -> {}, eofCancel::get))
            .reason());
  }

  @Test
  void sinkFailureAndIoFailurePropagateWithoutOwningStream() throws Exception {
    var input = new Tracking(fixture(2, 24, named()), 1, false);
    var failure = new IllegalStateException("staging refused");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                parser.read(
                    input,
                    limits,
                    (b, n, e) -> {
                      throw failure;
                    },
                    () -> false)));
    assertFalse(input.closed);
    var broken =
        new InputStream() {
          public int read() throws IOException {
            throw new IOException("broken");
          }
        };
    assertEquals(
        IO,
        assertThrows(
                StreamingRootException.class,
                () -> parser.read(broken, limits, (b, n, e) -> {}, () -> false))
            .reason());
  }

  @Test
  void fragmentedAndZeroBulkReadInputsHaveIdenticalResults() throws Exception {
    var bytes = fixture(2, 24, named());
    var expected = read(bytes);
    for (int fragment : List.of(1, 7, 8192))
      for (boolean zero : List.of(false, true)) {
        var stream = new Tracking(bytes, fragment, zero);
        var actual = new ArrayList<Row>();
        parser.read(stream, limits, (b, n, e) -> actual.add(new Row(b, n, e)), () -> false);
        assertEquals(expected, actual);
        assertFalse(stream.closed);
        assertTrue(stream.largestRequest <= 8192);
      }
  }

  @Test
  void enumeratesMillionMembershipsFromGeneratedStreamWithOneSmallBlockBuffer() throws Exception {
    int perBlock = 1000, blocks = 1000;
    long[] delta = new long[perBlock], names = new long[perBlock];
    int[] keys = new int[perBlock];
    Arrays.fill(keys, 7);
    byte[] one = fixture(2, 24, new Block(0, 0, 0, 2, delta, keys, names));
    byte[] body = Arrays.copyOfRange(one, 24, one.length), header = Arrays.copyOf(one, 24);
    put(header, 12, (long) perBlock * blocks);
    put(header, 16, (long) perBlock * blocks);
    long totalBytes = 24L + (long) blocks * body.length;
    AtomicLong seen = new AtomicLong();
    InputStream generated =
        new InputStream() {
          long at;

          public int read() {
            if (at == totalBytes) return -1;
            return at < 24
                ? header[(int) at++] & 255
                : body[(int) ((at++ - 24) % body.length)] & 255;
          }

          public int read(byte[] b, int off, int length) {
            if (at == totalBytes) return -1;
            int n = (int) Math.min(length, totalBytes - at);
            for (int i = 0; i < n; i++) b[off + i] = (byte) read();
            return n;
          }
        };
    var result =
        parser.read(
            generated,
            new FullRootInventory.Limits(totalBytes, 1_000_000, blocks, perBlock, 24000),
            (block, ordinal, entry) -> {
              long index = seen.getAndIncrement();
              assertEquals(index / perBlock, block);
              assertEquals(index % perBlock, ordinal);
              assertEquals(ordinal, entry.fileDataId());
            },
            () -> false);
    assertEquals(1_000_000, seen.get());
    assertEquals(24000, result.peakBlockWorkingBytes());
    assertEquals(totalBytes, result.decodedBytesRead());
    assertEquals(blocks, result.blockCount());
  }

  static class Tracking extends InputStream {
    final byte[] data;
    final int fragment;
    final boolean zero;
    int at, largestRequest;
    boolean closed;

    Tracking(byte[] d, int f, boolean z) {
      data = d;
      fragment = f;
      zero = z;
    }

    public int read() {
      return at == data.length ? -1 : data[at++] & 255;
    }

    public int read(byte[] b, int off, int len) {
      largestRequest = Math.max(largestRequest, len);
      if (zero) return 0;
      if (at == data.length) return -1;
      int n = Math.min(Math.min(len, fragment), data.length - at);
      System.arraycopy(data, at, b, off, n);
      at += n;
      return n;
    }

    public void close() {
      closed = true;
    }
  }

  static void put(byte[] b, int at, long value) {
    ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(at, (int) value);
  }

  static byte[] fixture(int version, int header, Block... blocks) throws IOException {
    int total = 0, named = 0, length = header;
    for (var b : blocks) {
      total += b.deltas.length;
      if (b.names != null) named += b.deltas.length;
      length += (version == 2 ? 17 : 12) + b.deltas.length * (b.names == null ? 20 : 28);
    }
    var out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    out.put(new byte[] {'T', 'S', 'F', 'M'})
        .putInt(header)
        .putInt(version)
        .putInt(total)
        .putInt(named);
    if (header == 24) out.putInt(0);
    for (var b : blocks) {
      out.putInt(b.deltas.length);
      if (version == 2)
        out.putInt((int) b.locale).putInt((int) b.first).putInt((int) b.second).put((byte) b.third);
      else out.putInt((int) b.first).putInt((int) b.locale);
      for (long d : b.deltas) out.putInt((int) d);
      for (int key : b.keys) {
        byte[] k = new byte[16];
        k[0] = (byte) key;
        out.put(k);
      }
      if (b.names != null) for (long name : b.names) out.putLong(name);
    }
    return out.array();
  }
}
