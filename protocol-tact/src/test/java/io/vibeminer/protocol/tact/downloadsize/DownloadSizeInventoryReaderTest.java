package io.vibeminer.protocol.tact.downloadsize;

import static io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DownloadSizeInventoryReaderTest {
  final DownloadSizeInventoryReader reader = new DownloadSizeInventoryReader();
  final DownloadSizeInventoryReader.Limits limits =
      new DownloadSizeInventoryReader.Limits(8192, 100, 20, 100, 100, 1000);

  record Tag(String name, int type, byte[] mask) {}

  record Row(byte[] key, long size) {}

  static byte[] key(int width, int value) {
    byte[] b = new byte[width];
    b[width - 1] = (byte) value;
    return b;
  }

  static Row row(int width, int value, long size) {
    return new Row(key(width, value), size);
  }

  @Test
  void parsesTagsBeforeAllEntriesAndPreservesShortPrefixes() throws Exception {
    byte[] data =
        fixture(
            9,
            12,
            new Tag[] {new Tag("enUS", 0x1234, new byte[] {(byte) 0xa0})},
            row(9, 1, 4),
            row(9, 2, 3),
            row(9, 1, 5));
    List<String> order = new ArrayList<>();
    List<DownloadSizeInventoryReader.Entry> rows = new ArrayList<>();
    var completion =
        reader.read(
            new ByteArrayInputStream(data),
            limits,
            new DownloadSizeInventoryReader.Consumer() {
              public void header(DownloadSizeInventoryReader.Header h) {
                order.add("H");
                assertEquals(9, h.keyBytes());
              }

              public void tag(long ordinal, DownloadSizeInventoryReader.Tag tag) {
                order.add("T" + ordinal);
                assertEquals("enUS", tag.name());
                assertEquals(0x1234, tag.type());
                assertTrue(tag.includesEntry(0));
                assertFalse(tag.includesEntry(1));
                assertTrue(tag.includesEntry(2));
                assertThrows(IndexOutOfBoundsException.class, () -> tag.includesEntry(3));
                byte[] copy = tag.membershipBytes();
                copy[0] = 0;
                assertTrue(tag.includesEntry(0));
              }

              public void entry(long ordinal, DownloadSizeInventoryReader.Entry e) {
                order.add("E" + ordinal);
                rows.add(e);
              }
            },
            () -> false);
    assertEquals(List.of("H", "T0", "E0", "E1", "E2"), order);
    assertEquals(rows.get(0).encodedKey(), rows.get(2).encodedKey());
    assertEquals(18, rows.getFirst().encodedKey().hex().length());
    assertTrue(rows.getFirst().encodedKey().fullEncodingKey().isEmpty());
    assertEquals(12, completion.measuredEncodedBytes());
    assertEquals(3, completion.observedEntries());
    assertEquals(1, completion.observedTags());
    assertEquals(data.length, completion.decodedBytesRead());
  }

  @Test
  void supportsFullKeysUnsignedSizeAndUnsigned40Total() throws Exception {
    var rows = new ArrayList<DownloadSizeInventoryReader.Entry>();
    long total = 0xffffffffL * 2;
    var result =
        reader.read(
            new ByteArrayInputStream(
                fixture(16, total, new Tag[0], row(16, 1, 0xffffffffL), row(16, 2, 0xffffffffL))),
            limits,
            (n, e) -> rows.add(e),
            () -> false);
    assertEquals(0xffffffffL, rows.getFirst().encodedBytes());
    assertEquals(
        Optional.of(HexFormat.of().formatHex(key(16, 1))),
        rows.getFirst().encodedKey().fullEncodingKey());
    assertEquals(total, result.header().declaredTotalEncodedBytes());
  }

  @Test
  void preservesZeroKeysEmptyTagsUnknownTypesAndPaddingBits() throws Exception {
    var data =
        fixture(9, 0, new Tag[] {new Tag("", 65535, new byte[] {(byte) 0xff})}, row(9, 0, 0));
    reader.read(
        new ByteArrayInputStream(data),
        limits,
        new DownloadSizeInventoryReader.Consumer() {
          public void tag(long n, DownloadSizeInventoryReader.Tag t) {
            assertEquals("", t.name());
            assertEquals(65535, t.type());
            assertArrayEquals(new byte[] {(byte) 255}, t.membershipBytes());
          }

          public void entry(long n, DownloadSizeInventoryReader.Entry e) {
            assertEquals("0".repeat(18), e.encodedKey().hex());
            assertEquals(0, e.encodedBytes());
          }
        },
        () -> false);
  }

  @Test
  void acceptsEmptyManifestAndZeroEntryTagBitmap() throws Exception {
    var result =
        reader.read(
            new ByteArrayInputStream(fixture(9, 0, new Tag[] {new Tag("empty", 1, new byte[0])})),
            limits,
            (n, e) -> fail(),
            () -> false);
    assertEquals(0, result.observedEntries());
    assertEquals(1, result.observedTags());
  }

  @Test
  void lateTotalMismatchAndTrailingByteNeverProduceCompletion() throws Exception {
    AtomicInteger count = new AtomicInteger();
    var wrong = fixture(9, 8, new Tag[0], row(9, 1, 7));
    assertEquals(
        MALFORMED,
        assertThrows(
                DownloadSizeInventoryException.class,
                () ->
                    reader.read(
                        new ByteArrayInputStream(wrong),
                        limits,
                        (n, e) -> count.incrementAndGet(),
                        () -> false))
            .reason());
    assertEquals(1, count.get());
    var good = fixture(9, 7, new Tag[0], row(9, 1, 7));
    assertEquals(
        MALFORMED,
        assertThrows(
                DownloadSizeInventoryException.class,
                () -> parse(Arrays.copyOf(good, good.length + 1)))
            .reason());
  }

  @Test
  void rejectsEveryTruncatedHeaderTagAndEntryPhase() throws Exception {
    var data = fixture(9, 9, new Tag[] {new Tag("tag", 1, new byte[] {(byte) 128})}, row(9, 1, 9));
    for (int length : new int[] {0, 1, 4, 8, 14, 16, 19, 20, 23, data.length - 1})
      assertEquals(
          MALFORMED,
          assertThrows(
                  DownloadSizeInventoryException.class, () -> parse(Arrays.copyOf(data, length)))
              .reason(),
          "length=" + length);
  }

  @Test
  void unsupportedMagicVersionAndWidthsDoNotBecomeFullKeys() throws Exception {
    for (int at : List.of(0, 2, 3)) {
      var b = fixture(9, 0, new Tag[0]);
      b[at] = 7;
      assertEquals(
          UNSUPPORTED, assertThrows(DownloadSizeInventoryException.class, () -> parse(b)).reason());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new DownloadSizeInventoryReader.KeyReference(9, "a".repeat(32)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DownloadSizeInventoryReader.KeyReference(8, "a".repeat(16)));
  }

  @Test
  void explicitCountsByteAndBitmapLimitsRefuseBeforeBodyRead() throws Exception {
    byte[] data = fixture(9, 1, new Tag[] {new Tag("a", 1, new byte[] {(byte) 128})}, row(9, 1, 1));
    for (var l :
        List.of(
            new DownloadSizeInventoryReader.Limits(data.length - 1, 100, 20, 100, 100, 1000),
            new DownloadSizeInventoryReader.Limits(8192, 0, 20, 100, 100, 1000),
            new DownloadSizeInventoryReader.Limits(8192, 100, 0, 100, 100, 1000),
            new DownloadSizeInventoryReader.Limits(8192, 100, 20, 100, 0, 1000)))
      assertEquals(
          LIMIT,
          assertThrows(
                  DownloadSizeInventoryException.class,
                  () ->
                      reader.read(new ByteArrayInputStream(data), l, (n, e) -> fail(), () -> false))
              .reason());
    var tracking = new Tracking(data, 1, false);
    assertEquals(
        LIMIT,
        assertThrows(
                DownloadSizeInventoryException.class,
                () ->
                    reader.read(
                        tracking,
                        new DownloadSizeInventoryReader.Limits(8192, 100, 20, 100, 0, 1000),
                        (n, e) -> fail(),
                        () -> false))
            .reason());
    assertEquals(15, tracking.at);
  }

  @Test
  void boundsNamesAggregateTagWorkAndStrictUtf8() throws Exception {
    var data = fixture(9, 1, new Tag[] {new Tag("abc", 1, new byte[] {(byte) 128})}, row(9, 1, 1));
    for (var l :
        List.of(
            new DownloadSizeInventoryReader.Limits(8192, 100, 20, 2, 100, 1000),
            new DownloadSizeInventoryReader.Limits(8192, 100, 20, 100, 100, 6)))
      assertEquals(
          LIMIT,
          assertThrows(
                  DownloadSizeInventoryException.class,
                  () ->
                      reader.read(new ByteArrayInputStream(data), l, (n, e) -> fail(), () -> false))
              .reason());
    var bad = data.clone();
    bad[15] = (byte) 0xc3;
    bad[16] = 0x28;
    assertEquals(
        MALFORMED, assertThrows(DownloadSizeInventoryException.class, () -> parse(bad)).reason());
  }

  @Test
  void preservesUnicodeWithoutNormalizationAndBitmapBoundary() throws Exception {
    var rows = new Row[9];
    Arrays.setAll(rows, n -> row(9, n, 0));
    var b =
        fixture(
            9,
            0,
            new Tag[] {
              new Tag("Café 🗡", 2, new byte[] {0, (byte) 128}),
              new Tag("Cafe\u0301", 3, new byte[] {0, 0})
            },
            rows);
    reader.read(
        new ByteArrayInputStream(b),
        limits,
        new DownloadSizeInventoryReader.Consumer() {
          public void tag(long n, DownloadSizeInventoryReader.Tag t) {
            assertEquals(n == 0 ? "Café 🗡" : "Cafe\u0301", t.name());
            if (n == 0) {
              assertTrue(t.includesEntry(8));
              assertFalse(t.includesEntry(7));
            }
          }

          public void entry(long n, DownloadSizeInventoryReader.Entry e) {}
        },
        () -> false);
  }

  @Test
  void cancellationAfterTagAndEntryIsProvisionalAndDoesNotCloseInput() throws Exception {
    var data = fixture(9, 1, new Tag[] {new Tag("a", 1, new byte[] {(byte) 128})}, row(9, 1, 1));
    for (boolean inTag : List.of(true, false)) {
      AtomicBoolean cancel = new AtomicBoolean();
      Tracking in = new Tracking(data, 1, false);
      assertEquals(
          CANCELLED,
          assertThrows(
                  DownloadSizeInventoryException.class,
                  () ->
                      reader.read(
                          in,
                          limits,
                          new DownloadSizeInventoryReader.Consumer() {
                            public void tag(long n, DownloadSizeInventoryReader.Tag t) {
                              if (inTag) cancel.set(true);
                            }

                            public void entry(long n, DownloadSizeInventoryReader.Entry e) {
                              cancel.set(true);
                            }
                          },
                          cancel::get))
              .reason());
      assertFalse(in.closed);
    }
  }

  @Test
  void cancellationDuringBitmapAndFinalEofIsObserved() throws Exception {
    var data = fixture(9, 1, new Tag[] {new Tag("a", 1, new byte[] {(byte) 128})}, row(9, 1, 1));
    Tracking in = new Tracking(data, 1, false);
    assertEquals(
        CANCELLED,
        assertThrows(
                DownloadSizeInventoryException.class,
                () -> reader.read(in, limits, (n, e) -> fail(), () -> in.at >= 19))
            .reason());
    AtomicBoolean cancelled = new AtomicBoolean();
    var finalIn =
        new ByteArrayInputStream(data) {
          public synchronized int read(byte[] b, int o, int l) {
            int n = super.read(b, o, l);
            if (n < 0) cancelled.set(true);
            return n;
          }
        };
    assertEquals(
        CANCELLED,
        assertThrows(
                DownloadSizeInventoryException.class,
                () -> reader.read(finalIn, limits, (n, e) -> {}, cancelled::get))
            .reason());
  }

  @Test
  void sinkAndIoFailureKeepActualCauseAndStreamOwnership() throws Exception {
    var data = fixture(9, 1, new Tag[0], row(9, 1, 1));
    Tracking in = new Tracking(data, 1, false);
    RuntimeException refused = new IllegalStateException("sink refused");
    assertSame(
        refused,
        assertThrows(
            IllegalStateException.class,
            () ->
                reader.read(
                    in,
                    limits,
                    (n, e) -> {
                      throw refused;
                    },
                    () -> false)));
    assertFalse(in.closed);
    InputStream broken =
        new InputStream() {
          public int read() throws IOException {
            throw new IOException("broken");
          }
        };
    var ex =
        assertThrows(
            DownloadSizeInventoryException.class,
            () -> reader.read(broken, limits, (n, e) -> {}, () -> false));
    assertEquals(IO, ex.reason());
    assertInstanceOf(IOException.class, ex.getCause());
  }

  @Test
  void fragmentedAndZeroBulkInputsUseBoundedReads() throws Exception {
    var data = fixture(9, 1, new Tag[] {new Tag("a", 1, new byte[] {(byte) 128})}, row(9, 1, 1));
    for (int f : List.of(1, 7, 8192))
      for (boolean zero : List.of(true, false)) {
        Tracking in = new Tracking(data, f, zero);
        AtomicInteger seen = new AtomicInteger();
        var c = reader.read(in, limits, (n, e) -> seen.incrementAndGet(), () -> false);
        assertEquals(1, seen.get());
        assertEquals(data.length, c.decodedBytesRead());
        assertFalse(in.closed);
        assertTrue(in.largest <= 8192);
      }
  }

  @Test
  void streamsMillionEntriesAndOneBitmapWithoutWholeManifestMaterialization() throws Exception {
    int count = 1_000_000, bitmap = (count + 7) / 8;
    byte[] header = fixture(9, count, new Tag[0]);
    ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).putInt(4, count).putShort(8, (short) 1);
    header[10] = 0;
    ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).putInt(11, count);
    byte[] tagPrefix = {'a', 'l', 'l', 0, 0, 0};
    long recordStart = 15L + tagPrefix.length + bitmap, total = recordStart + count * 13L;
    InputStream generated =
        new InputStream() {
          long at;

          public int read() {
            if (at == total) return -1;
            long n = at++;
            if (n < 15) return header[(int) n] & 255;
            if (n < 21) return tagPrefix[(int) (n - 15)] & 255;
            if (n < recordStart) return 255;
            return (n - recordStart) % 13 == 12 ? 1 : 0;
          }

          public int read(byte[] b, int off, int length) {
            if (at == total) return -1;
            int n = (int) Math.min(length, total - at);
            for (int i = 0; i < n; i++) b[off + i] = (byte) read();
            return n;
          }
        };
    AtomicLong seen = new AtomicLong();
    var result =
        reader.read(
            generated,
            new DownloadSizeInventoryReader.Limits(total, count, 1, 3, bitmap, bitmap + 6L),
            new DownloadSizeInventoryReader.Consumer() {
              public void tag(long ordinal, DownloadSizeInventoryReader.Tag tag) {
                assertEquals(0, ordinal);
                assertTrue(tag.includesEntry(0));
                assertTrue(tag.includesEntry(count - 1));
              }

              public void entry(long ordinal, DownloadSizeInventoryReader.Entry entry) {
                assertEquals(seen.getAndIncrement(), ordinal);
                assertEquals(9, entry.encodedKey().byteLength());
                assertEquals(1, entry.encodedBytes());
              }
            },
            () -> false);
    assertEquals(count, seen.get());
    assertEquals(count, result.measuredEncodedBytes());
    assertEquals(total, result.decodedBytesRead());
  }

  void parse(byte[] b) throws Exception {
    reader.read(new ByteArrayInputStream(b), limits, (n, e) -> {}, () -> false);
  }

  static class Tracking extends InputStream {
    final byte[] data;
    final int fragment;
    final boolean zero;
    int at, largest;
    boolean closed;

    Tracking(byte[] d, int f, boolean z) {
      data = d;
      fragment = f;
      zero = z;
    }

    public int read() {
      return at == data.length ? -1 : data[at++] & 255;
    }

    public int read(byte[] b, int o, int l) {
      largest = Math.max(largest, l);
      if (zero) return 0;
      if (at == data.length) return -1;
      int n = Math.min(l, Math.min(fragment, data.length - at));
      System.arraycopy(data, at, b, o, n);
      at += n;
      return n;
    }

    public void close() {
      closed = true;
    }
  }

  static byte[] fixture(int width, long total, Tag[] tags, Row... rows) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var b = new DataOutputStream(bytes);
    b.writeBytes("DS");
    b.writeByte(1);
    b.writeByte(width);
    b.writeInt(rows.length);
    b.writeShort(tags.length);
    b.writeByte((int) (total >>> 32));
    b.writeInt((int) total);
    for (var tag : tags) {
      b.write(tag.name.getBytes(StandardCharsets.UTF_8));
      b.writeByte(0);
      b.writeShort(tag.type);
      b.write(tag.mask);
    }
    for (var row : rows) {
      b.write(row.key);
      b.writeInt((int) row.size);
    }
    return bytes.toByteArray();
  }
}
