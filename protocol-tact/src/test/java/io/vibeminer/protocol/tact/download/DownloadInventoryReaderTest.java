package io.vibeminer.protocol.tact.download;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DownloadInventoryReaderTest {
  static final DownloadInventoryReader.Limits LIMITS =
      new DownloadInventoryReader.Limits(1_000_000, 10000, 100, 256, 10000, 100000, 255);

  static class Sink implements DownloadInventoryReader.Consumer {
    DownloadInventoryReader.Header header;
    final List<DownloadInventoryReader.Entry> entries = new ArrayList<>();
    final List<DownloadInventoryReader.Tag> tags = new ArrayList<>();

    public void header(DownloadInventoryReader.Header h) {
      header = h;
    }

    public void entry(long ordinal, DownloadInventoryReader.Entry e) {
      assertEquals(entries.size(), ordinal);
      entries.add(e);
    }

    public void tag(int ordinal, DownloadInventoryReader.Tag t) {
      assertEquals(tags.size(), ordinal);
      tags.add(t);
    }
  }

  static byte[] fixture(int version, int checksum, int flags, int entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("DL");
    out.writeByte(version);
    out.writeByte(16);
    out.writeByte(checksum);
    out.writeInt(entries);
    out.writeShort(1);
    if (version >= 2) out.writeByte(flags);
    if (version >= 3) {
      out.writeByte(0x80);
      out.write(new byte[] {1, (byte) 0xff, 3});
    }
    for (int i = 0; i < entries; i++) {
      out.write(new byte[16]);
      long size = (1L << 39) + i;
      for (int shift = 32; shift >= 0; shift -= 8) out.writeByte((int) (size >>> shift));
      out.writeByte(0xff);
      if (checksum != 0) out.writeInt(-1);
      if (version >= 2) for (int f = 0; f < flags; f++) out.writeByte(0xa0 + f);
    }
    out.write("α".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    out.writeByte(0);
    out.writeShort(65535);
    for (int i = 0; i < (entries + 7) / 8; i++) out.writeByte(i == 0 ? 0x81 : 0xff);
    return bytes.toByteArray();
  }

  @Test
  void allVersionsPreserveRawFieldsAndCompleteOnlyAtExactEof() throws Exception {
    for (int version = 1; version <= 3; version++) {
      byte[] source = fixture(version, 7, 2, 9);
      var sink = new Sink();
      var complete =
          new DownloadInventoryReader()
              .read(new ByteArrayInputStream(source), LIMITS, sink, () -> false);
      assertEquals(source.length, complete.consumedBytes());
      assertEquals(9, complete.entries());
      assertEquals(1, complete.tags());
      assertEquals(7, sink.header.checksumPresentRaw());
      assertEquals(version >= 2 ? 2 : 0, sink.header.flagBytes());
      assertEquals(
          version == 3 ? OptionalInt.of(128) : OptionalInt.empty(), sink.header.basePriorityRaw());
      assertEquals(
          version == 3 ? OptionalInt.of(-128) : OptionalInt.empty(),
          sink.header.basePrioritySigned());
      assertEquals((1L << 39) + 8, sink.entries.getLast().encodedBytes());
      assertEquals(255, sink.entries.getFirst().priorityRaw());
      assertEquals(-1, sink.entries.getFirst().prioritySigned());
      assertEquals(OptionalLong.of(0xffffffffL), sink.entries.getFirst().checksum());
      assertEquals("0".repeat(32), sink.entries.getFirst().encodingKey());
      var tag = sink.tags.getFirst();
      assertEquals("α", tag.name());
      assertEquals(65535, tag.rawType());
      assertTrue(tag.includesEntry(0));
      assertFalse(tag.includesEntry(1));
      assertTrue(tag.includesEntry(7));
      assertTrue(tag.includesEntry(8));
      assertThrows(IndexOutOfBoundsException.class, () -> tag.includesEntry(9));
    }
  }

  @Test
  void unsigned40AndPriorityExtremaStayRawRatherThanWrappedByBasePriority() throws Exception {
    byte[] bytes = fixture(3, 2, 0, 1);
    bytes[12] = 127;
    Arrays.fill(bytes, 32, 37, (byte) 255);
    bytes[37] = (byte) 128;
    Arrays.fill(bytes, 38, 42, (byte) 0);
    var sink = new Sink();
    new DownloadInventoryReader().read(new ByteArrayInputStream(bytes), LIMITS, sink, () -> false);
    assertEquals((1L << 40) - 1, sink.entries.getFirst().encodedBytes());
    assertEquals(128, sink.entries.getFirst().priorityRaw());
    assertEquals(-128, sink.entries.getFirst().prioritySigned());
    assertEquals(OptionalInt.of(127), sink.header.basePrioritySigned());
    assertEquals(OptionalLong.of(0), sink.entries.getFirst().checksum());
    Arrays.fill(bytes, 32, 37, (byte) 0);
    sink = new Sink();
    new DownloadInventoryReader().read(new ByteArrayInputStream(bytes), LIMITS, sink, () -> false);
    assertEquals(0, sink.entries.getFirst().encodedBytes());
  }

  @Test
  void exactBodyCeilingAllowsEofButRejectsOneTrailingByte() throws Exception {
    byte[] source = fixture(1, 0, 0, 1);
    var limits = new DownloadInventoryReader.Limits(source.length, 1, 1, 2, 1, 4, 0);
    assertEquals(
        source.length,
        new DownloadInventoryReader()
            .read(new ByteArrayInputStream(source), limits, new Sink(), () -> false)
            .consumedBytes());
    assertEquals(
        DownloadInventoryException.Reason.LIMIT,
        assertThrows(
                DownloadInventoryException.class,
                () ->
                    new DownloadInventoryReader()
                        .read(
                            new ByteArrayInputStream(Arrays.copyOf(source, source.length + 1)),
                            limits,
                            new Sink(),
                            () -> false))
            .reason());
  }

  @Test
  void checksumAbsentZeroFlagsAndEmptyInventoriesRemainSupported() throws Exception {
    for (int version = 1; version <= 3; version++) {
      byte[] bytes = fixture(version, 0, 0, 0);
      var sink = new Sink();
      var result =
          new DownloadInventoryReader()
              .read(new ByteArrayInputStream(bytes), LIMITS, sink, () -> false);
      assertEquals(0, result.entries());
      assertEquals(1, result.tags());
      assertEquals(0, sink.tags.getFirst().bitmap().length);
      assertEquals(version == 3 ? 3 : 0, sink.header.unknownVersion3Bytes().length);
      bytes[9] = 0;
      bytes[10] = 0;
      bytes = Arrays.copyOf(bytes, version == 3 ? 16 : version == 2 ? 12 : 11);
      assertEquals(
          0,
          new DownloadInventoryReader()
              .read(new ByteArrayInputStream(bytes), LIMITS, new Sink(), () -> false)
              .tags());
    }
    var sink = new Sink();
    new DownloadInventoryReader()
        .read(new ByteArrayInputStream(fixture(2, 0, 0, 1)), LIMITS, sink, () -> false);
    assertTrue(sink.entries.getFirst().checksum().isEmpty());
    assertEquals(0, sink.entries.getFirst().flags().length);
  }

  @Test
  void everyTruncationAndTrailingByteRefusesStructuralCompletion() throws Exception {
    byte[] bytes = fixture(3, 255, 3, 9);
    for (int length = 0; length < bytes.length; length++) {
      final int end = length;
      assertEquals(
          DownloadInventoryException.Reason.MALFORMED,
          assertThrows(
                  DownloadInventoryException.class,
                  () ->
                      new DownloadInventoryReader()
                          .read(
                              new ByteArrayInputStream(Arrays.copyOf(bytes, end)),
                              LIMITS,
                              new Sink(),
                              () -> false))
              .reason(),
          "prefix " + length);
    }
    assertEquals(
        DownloadInventoryException.Reason.MALFORMED,
        assertThrows(
                DownloadInventoryException.class,
                () ->
                    new DownloadInventoryReader()
                        .read(
                            new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length + 1)),
                            LIMITS,
                            new Sink(),
                            () -> false))
            .reason());
  }

  @Test
  void fragmentedAndZeroBulkReadsMakeProgressWithoutClosingCallerStream() throws Exception {
    byte[] bytes = fixture(3, 9, 5, 9);
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var input =
        new ByteArrayInputStream(bytes) {
          @Override
          public synchronized int read(byte[] target, int offset, int length) {
            assertTrue(length <= 8192);
            if (calls.incrementAndGet() % 2 == 0) return 0;
            return super.read(target, offset, Math.min(1, length));
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    var result = new DownloadInventoryReader().read(input, LIMITS, new Sink(), () -> false);
    assertEquals(bytes.length, result.consumedBytes());
    assertFalse(closed.get());
  }

  @Test
  void rawByteArraysCannotBeMutatedByConsumers() throws Exception {
    var sink = new Sink();
    new DownloadInventoryReader()
        .read(new ByteArrayInputStream(fixture(3, 1, 2, 9)), LIMITS, sink, () -> false);
    assertArrayEquals(new byte[] {1, (byte) 255, 3}, sink.header.unknownVersion3Bytes());
    sink.header.unknownVersion3Bytes()[0] = 0;
    assertEquals(1, sink.header.unknownVersion3Bytes()[0]);
    sink.entries.getFirst().flags()[0] = 0;
    assertEquals((byte) 0xa0, sink.entries.getFirst().flags()[0]);
    sink.tags.getFirst().bitmap()[0] = 0;
    assertTrue(sink.tags.getFirst().includesEntry(0));
    assertEquals(sink.entries.getFirst().encodingKey(), sink.entries.getLast().encodingKey());
  }

  @Test
  void unsupportedSignatureVersionAndKeyWidthNeverBecomeDl() throws Exception {
    byte[] valid = fixture(1, 0, 0, 1);
    for (int offset : new int[] {1, 2, 3}) {
      byte[] changed = valid.clone();
      changed[offset] = (byte) (offset == 1 ? 'S' : offset == 2 ? 4 : 15);
      assertEquals(
          DownloadInventoryException.Reason.UNSUPPORTED,
          assertThrows(
                  DownloadInventoryException.class,
                  () ->
                      new DownloadInventoryReader()
                          .read(new ByteArrayInputStream(changed), LIMITS, new Sink(), () -> false))
              .reason());
    }
  }

  @Test
  void headerDerivedBoundsRejectBeforeAnyEntryOrTagCallbackOrPayloadRead() throws Exception {
    byte[] source = fixture(3, 1, 255, 9);
    for (var limits :
        List.of(
            new DownloadInventoryReader.Limits(1000000, 8, 100, 256, 10000, 100000, 255),
            new DownloadInventoryReader.Limits(1000000, 100, 0, 256, 10000, 100000, 255),
            new DownloadInventoryReader.Limits(1000000, 100, 100, 256, 1, 100000, 255),
            new DownloadInventoryReader.Limits(1000000, 100, 100, 256, 10000, 100000, 254),
            new DownloadInventoryReader.Limits(100, 100, 100, 256, 10000, 100000, 255),
            new DownloadInventoryReader.Limits(1000000, 100, 100, 256, 10000, 2, 255))) {
      var input = new ByteArrayInputStream(source);
      var sink = new Sink();
      assertEquals(
          DownloadInventoryException.Reason.LIMIT,
          assertThrows(
                  DownloadInventoryException.class,
                  () -> new DownloadInventoryReader().read(input, limits, sink, () -> false))
              .reason());
      assertEquals(source.length - 16, input.available());
      assertNull(sink.header);
    }
    byte[] huge = source.clone();
    Arrays.fill(huge, 5, 9, (byte) 255);
    assertEquals(
        DownloadInventoryException.Reason.LIMIT,
        assertThrows(
                DownloadInventoryException.class,
                () ->
                    new DownloadInventoryReader()
                        .read(
                            new ByteArrayInputStream(huge),
                            new DownloadInventoryReader.Limits(
                                Long.MAX_VALUE, 0xffffffffL, 65535, 256, 1024, Long.MAX_VALUE, 255),
                            new Sink(),
                            () -> false))
            .reason());
  }

  @Test
  void strictNamesAndAggregateWorkAreEnforcedBeforeBitmapConsumption() throws Exception {
    byte[] source = fixture(2, 0, 0, 9);
    var input = new ByteArrayInputStream(source);
    var sink = new Sink();
    assertEquals(
        DownloadInventoryException.Reason.LIMIT,
        assertThrows(
                DownloadInventoryException.class,
                () ->
                    new DownloadInventoryReader()
                        .read(
                            input,
                            new DownloadInventoryReader.Limits(
                                1000000, 100, 100, 256, 10000, 4, 255),
                            sink,
                            () -> false))
            .reason());
    assertEquals(2, input.available());
    assertEquals(9, sink.entries.size());
    assertTrue(sink.tags.isEmpty());
    var shortName = new DownloadInventoryReader.Limits(1000000, 100, 100, 1, 10000, 100000, 255);
    assertEquals(
        DownloadInventoryException.Reason.LIMIT,
        assertThrows(
                DownloadInventoryException.class,
                () ->
                    new DownloadInventoryReader()
                        .read(new ByteArrayInputStream(source), shortName, new Sink(), () -> false))
            .reason());
    byte[] malformed = source.clone();
    malformed[malformed.length - 6] = (byte) 0xff;
    assertEquals(
        DownloadInventoryException.Reason.MALFORMED,
        assertThrows(
                DownloadInventoryException.class,
                () ->
                    new DownloadInventoryReader()
                        .read(new ByteArrayInputStream(malformed), LIMITS, new Sink(), () -> false))
            .reason());
  }

  @Test
  void sinkExceptionsAndIoFailuresPreserveTheirCausesAndNoCompletion() throws Exception {
    var failure = new IllegalStateException("staging refused");
    for (int stage = 0; stage < 3; stage++) {
      int selected = stage;
      var sink =
          new Sink() {
            public void header(DownloadInventoryReader.Header h) {
              if (selected == 0) throw failure;
              super.header(h);
            }

            public void entry(long n, DownloadInventoryReader.Entry e) {
              if (selected == 1) throw failure;
              super.entry(n, e);
            }

            public void tag(int n, DownloadInventoryReader.Tag t) {
              if (selected == 2) throw failure;
              super.tag(n, t);
            }
          };
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  new DownloadInventoryReader()
                      .read(
                          new ByteArrayInputStream(fixture(3, 1, 2, 9)),
                          LIMITS,
                          sink,
                          () -> false)));
    }
    var io = new IOException("read failed");
    var input =
        new InputStream() {
          public int read() throws IOException {
            throw io;
          }
        };
    var typed =
        assertThrows(
            DownloadInventoryException.class,
            () -> new DownloadInventoryReader().read(input, LIMITS, new Sink(), () -> false));
    assertEquals(DownloadInventoryException.Reason.IO, typed.reason());
    assertSame(io, typed.getCause());
  }

  @Test
  void cancellationDuringBitmapAndFinalEofPreventsCompletionAndDoesNotCloseInput()
      throws Exception {
    byte[] source = fixture(3, 1, 2, 9);
    for (boolean finalEof : new boolean[] {false, true}) {
      var canceled = new java.util.concurrent.atomic.AtomicBoolean();
      var closed = new java.util.concurrent.atomic.AtomicBoolean();
      var input =
          new ByteArrayInputStream(source) {
            @Override
            public synchronized int read() {
              int b = super.read();
              if (finalEof && b == -1) canceled.set(true);
              return b;
            }

            @Override
            public synchronized int read(byte[] bytes, int offset, int count) {
              int n = super.read(bytes, offset, count);
              if (!finalEof && pos == source.length) canceled.set(true);
              return n;
            }

            @Override
            public void close() {
              closed.set(true);
            }
          };
      var sink = new Sink();
      assertEquals(
          DownloadInventoryException.Reason.CANCELLED,
          assertThrows(
                  DownloadInventoryException.class,
                  () -> new DownloadInventoryReader().read(input, LIMITS, sink, canceled::get))
              .reason());
      assertEquals(finalEof ? 1 : 0, sink.tags.size());
      assertFalse(closed.get());
    }
    assertEquals(
        DownloadInventoryException.Reason.CANCELLED,
        assertThrows(
                DownloadInventoryException.class,
                () ->
                    new DownloadInventoryReader()
                        .read(new ByteArrayInputStream(source), LIMITS, new Sink(), () -> true))
            .reason());
  }

  @Test
  void millionEntriesStreamWithoutAnInventoryCollection() throws Exception {
    int count = 1000000;
    var header = new ByteArrayOutputStream();
    var out = new DataOutputStream(header);
    out.writeBytes("DL");
    out.writeByte(1);
    out.writeByte(16);
    out.writeByte(0);
    out.writeInt(count);
    out.writeShort(0);
    byte[] prefix = header.toByteArray();
    long length = prefix.length + 22L * count;
    var input =
        new InputStream() {
          long position;

          public int read() {
            if (position >= length) return -1;
            return position < prefix.length ? prefix[(int) position++] & 255 : zero();
          }

          int zero() {
            position++;
            return 0;
          }

          public int read(byte[] target, int offset, int requested) {
            assertTrue(requested <= 8192);
            if (position >= length) return -1;
            int n = (int) Math.min(requested, length - position);
            for (int i = 0; i < n; i++) target[offset + i] = (byte) read();
            return n;
          }
        };
    var seen = new long[1];
    var consumer =
        new DownloadInventoryReader.Consumer() {
          public void header(DownloadInventoryReader.Header h) {
            assertEquals(count, h.entryCount());
          }

          public void entry(long ordinal, DownloadInventoryReader.Entry e) {
            assertEquals(seen[0]++, ordinal);
          }

          public void tag(int ordinal, DownloadInventoryReader.Tag tag) {
            fail("No declared tags");
          }
        };
    var result =
        new DownloadInventoryReader()
            .read(
                input,
                new DownloadInventoryReader.Limits(length, count, 0, 0, 0, 0, 0),
                consumer,
                () -> false);
    assertEquals(count, seen[0]);
    assertEquals(length, result.consumedBytes());
  }
}
