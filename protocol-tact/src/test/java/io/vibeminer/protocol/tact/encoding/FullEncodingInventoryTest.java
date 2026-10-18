package io.vibeminer.protocol.tact.encoding;

import static io.vibeminer.protocol.tact.encoding.EncodingManifestParserTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FullEncodingInventoryTest {
  static final FullEncodingInventory.Limits LIMITS =
      new FullEncodingInventory.Limits(
          1_000_000, 10000, 100000, 100, 100000, 4096, 1000, 255, 100, 10000, 10000);

  static class Sink implements FullEncodingInventory.Consumer {
    final List<String> specs = new ArrayList<>();
    final List<EncodingManifest.ContentEntry> contents = new ArrayList<>();
    final List<EncodingManifest.EncodedEntry> encoded = new ArrayList<>();
    String trailer;

    public void header(IndexedEncodingParser.Layout layout) {}

    public void specification(int index, String value) {
      assertEquals(specs.size(), index);
      specs.add(value);
    }

    public void content(int page, long ordinal, EncodingManifest.ContentEntry entry) {
      assertEquals(contents.size(), ordinal);
      contents.add(entry);
    }

    public void encoding(int page, long ordinal, EncodingManifest.EncodedEntry entry) {
      assertEquals(encoded.size(), ordinal);
      encoded.add(entry);
    }

    public void trailer(String value) {
      trailer = value;
    }
  }

  @Test
  void completeEnumerationMatchesReviewedFullParserAndPreservesDuplicateCandidates()
      throws Exception {
    byte[] bytes =
        fixture(
            new byte[][] {cp(1, 0x123456789aL, 10, 10, 11), cp(2, 0, 12)},
            new byte[][] {ep(10, 0, 0xabcdef0123L), ep(11, 2, 0), ep(12, 1, 5)},
            true,
            "b:{*=z}");
    var sink = new Sink();
    var complete =
        new FullEncodingInventory()
            .read(new ByteArrayInputStream(bytes), LIMITS, sink, () -> false);
    var old =
        new EncodingManifestParser()
            .parse(
                bytes,
                new EncodingManifestParser.Limits(
                    1000000, 100, 4096, 10000, 255, 100, 10000, 10000),
                () -> false);
    assertEquals(
        old.contentEntries().values().stream()
            .sorted(Comparator.comparing(EncodingManifest.ContentEntry::contentKey))
            .toList(),
        sink.contents);
    assertEquals(
        old.encodingEntries().values().stream()
            .sorted(Comparator.comparing(EncodingManifest.EncodedEntry::encodingKey))
            .toList(),
        sink.encoded);
    assertEquals(List.of("z", "n", ""), sink.specs);
    assertEquals(old.fileEncodingSpec(), sink.trailer);
    assertEquals(2, complete.contentRecords());
    assertEquals(3, complete.encodingRecords());
    assertEquals(4, complete.candidateReferences());
    assertEquals(bytes.length, complete.consumedBytes());
  }

  @Test
  void missingCandidateMetadataAndEmptyTablesPreserveExistingGrammar() throws Exception {
    var sink = new Sink();
    new FullEncodingInventory()
        .read(
            new ByteArrayInputStream(
                fixture(new byte[][] {cp(1, 0, 10, 11)}, new byte[0][], false, "")),
            LIMITS,
            sink,
            () -> false);
    assertEquals(List.of(key(10), key(11)), sink.contents.getFirst().encodingKeys());
    assertTrue(sink.encoded.isEmpty());
    byte[] empty = Arrays.copyOf(fixture(new byte[0][], new byte[0][], false, ""), 22);
    java.nio.ByteBuffer.wrap(empty).putInt(18, 0);
    var result =
        new FullEncodingInventory()
            .read(new ByteArrayInputStream(empty), LIMITS, new Sink(), () -> false);
    assertEquals(0, result.specifications());
    assertEquals(22, result.consumedBytes());
  }

  @Test
  void everyTruncatedPrefixFailsWithoutCompletion() throws Exception {
    byte[] complete = sample();
    for (int end = 0; end < complete.length; end++) {
      byte[] prefix = Arrays.copyOf(complete, end);
      assertEquals(
          FullEncodingInventoryException.Reason.MALFORMED,
          assertThrows(
                  FullEncodingInventoryException.class,
                  () ->
                      new FullEncodingInventory()
                          .read(new ByteArrayInputStream(prefix), LIMITS, new Sink(), () -> false))
              .reason(),
          "prefix " + end);
    }
  }

  @Test
  void fullPageHashesIndexFirstKeysAndGlobalOrderingCannotBeSkipped() throws Exception {
    byte[] source = sample();
    int cIndex = 22 + SPECS.length, cPage = cIndex + 32, eIndex = cPage + PAGE, ePage = eIndex + 32;
    for (int at : new int[] {cIndex + 16, cPage + 50, eIndex + 16, ePage + 100}) {
      byte[] changed = source.clone();
      changed[at] ^= 1;
      assertEquals(
          FullEncodingInventoryException.Reason.INTEGRITY,
          assertThrows(FullEncodingInventoryException.class, () -> read(changed)).reason());
    }
    byte[] changed = source.clone();
    changed[cIndex] = 2;
    assertEquals(
        FullEncodingInventoryException.Reason.MALFORMED,
        assertThrows(FullEncodingInventoryException.class, () -> read(changed)).reason());
    for (byte[] duplicate :
        List.of(
            fixture(new byte[][] {cp(1, 1, 10), cp(1, 2, 11)}, new byte[0][], false, ""),
            fixture(new byte[0][], new byte[][] {ep(10, 0, 1), ep(10, 1, 2)}, false, "")))
      assertEquals(
          FullEncodingInventoryException.Reason.MALFORMED,
          assertThrows(FullEncodingInventoryException.class, () -> read(duplicate)).reason());
    byte[] interval =
        fixture(
            new byte[][] {concat(cp(1, 1, 10), cp(3, 2, 11)), cp(2, 1, 12)},
            new byte[0][],
            false,
            "");
    assertEquals(
        FullEncodingInventoryException.Reason.MALFORMED,
        assertThrows(FullEncodingInventoryException.class, () -> read(interval)).reason());
  }

  @Test
  void everyEncodedSpecReferenceAndOnlyFinalSentinelAreValidated() throws Exception {
    byte[] invalid =
        fixture(new byte[0][], new byte[][] {concat(ep(10, 0, 1), ep(11, 99, 2))}, true, "");
    var sink = new Sink();
    assertEquals(
        FullEncodingInventoryException.Reason.MALFORMED,
        assertThrows(
                FullEncodingInventoryException.class,
                () ->
                    new FullEncodingInventory()
                        .read(new ByteArrayInputStream(invalid), LIMITS, sink, () -> false))
            .reason());
    assertEquals(1, sink.encoded.size(), "Earlier rows are provisional, not final completion");
    byte[] early = fixture(new byte[0][], new byte[][] {ep(10, 0, 1), ep(11, 0, 1)}, false, "");
    int firstPage = 22 + SPECS.length + 64;
    Arrays.fill(early, firstPage + 25 + 16, firstPage + 25 + 20, (byte) 255);
    rehash(early, false);
    assertEquals(
        FullEncodingInventoryException.Reason.MALFORMED,
        assertThrows(FullEncodingInventoryException.class, () -> read(early)).reason());
  }

  @Test
  void specsAndTrailerStayStrictBoundedOpaqueAscii() throws Exception {
    byte[] invalid = sample();
    invalid[22] = (byte) 255;
    assertEquals(
        FullEncodingInventoryException.Reason.UNSUPPORTED,
        assertThrows(FullEncodingInventoryException.class, () -> read(invalid)).reason());
    byte[] unterminated = sample();
    unterminated[22 + SPECS.length - 1] = 'x';
    assertEquals(
        FullEncodingInventoryException.Reason.MALFORMED,
        assertThrows(FullEncodingInventoryException.class, () -> read(unterminated)).reason());
    byte[] trailer = Arrays.copyOf(sample(), sample().length + 1);
    trailer[trailer.length - 1] = 0;
    assertEquals(
        FullEncodingInventoryException.Reason.UNSUPPORTED,
        assertThrows(FullEncodingInventoryException.class, () -> read(trailer)).reason());
    var sink = new Sink();
    new FullEncodingInventory()
        .read(
            new ByteArrayInputStream(
                fixture(new byte[0][], new byte[0][], false, "opaque not-guessed ESpec")),
            LIMITS,
            sink,
            () -> false);
    assertEquals("opaque not-guessed ESpec", sink.trailer);
  }

  @Test
  void boundsRefuseLayoutBeforeAllocationAndAggregateEmissionNeverOverruns() throws Exception {
    byte[] input = sample();
    var bounds =
        List.of(
            new FullEncodingInventory.Limits(
                22, 100, 100, 100, 10000, 4096, 1000, 255, 100, 1000, 1000),
            new FullEncodingInventory.Limits(
                100000, 1, 100, 100, 10000, 4096, 1000, 255, 100, 1000, 1000),
            new FullEncodingInventory.Limits(
                100000, 100, 100, 1, 10000, 4096, 1000, 255, 100, 1000, 1000),
            new FullEncodingInventory.Limits(
                100000, 100, 100, 100, 31, 4096, 1000, 255, 100, 1000, 1000),
            new FullEncodingInventory.Limits(
                100000, 100, 100, 100, 10000, 1024, 1000, 255, 100, 3, 1000));
    for (var limits : bounds) {
      var source = new ByteArrayInputStream(input);
      var sink = new Sink();
      assertEquals(
          FullEncodingInventoryException.Reason.LIMIT,
          assertThrows(
                  FullEncodingInventoryException.class,
                  () -> new FullEncodingInventory().read(source, limits, sink, () -> false))
              .reason());
      assertEquals(input.length - 22, source.available());
      assertTrue(sink.specs.isEmpty());
      assertTrue(sink.contents.isEmpty());
    }
    var noCandidates =
        new FullEncodingInventory.Limits(
            100000, 100, 1, 100, 10000, 4096, 1000, 255, 100, 1000, 1000);
    byte[] many = fixture(new byte[][] {cp(1, 1, 10, 11)}, new byte[0][], false, "");
    var sink = new Sink();
    assertEquals(
        FullEncodingInventoryException.Reason.LIMIT,
        assertThrows(
                FullEncodingInventoryException.class,
                () ->
                    new FullEncodingInventory()
                        .read(new ByteArrayInputStream(many), noCandidates, sink, () -> false))
            .reason());
    assertTrue(sink.contents.isEmpty());
    for (int at : new int[] {9, 13, 18}) {
      byte[] huge = input.clone();
      java.nio.ByteBuffer.wrap(huge).putInt(at, -1);
      assertEquals(
          FullEncodingInventoryException.Reason.LIMIT,
          assertThrows(FullEncodingInventoryException.class, () -> read(huge)).reason());
    }
  }

  @Test
  void fragmentedZeroBulkReadsAndExactCeilingDoNotCloseCallerInput() throws Exception {
    byte[] input = sample();
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var source =
        new ByteArrayInputStream(input) {
          public synchronized int read(byte[] target, int offset, int count) {
            assertTrue(count <= 8192);
            if (calls.incrementAndGet() % 2 == 0) return 0;
            return super.read(target, offset, Math.min(3, count));
          }

          public void close() {
            closed.set(true);
          }
        };
    var exact =
        new FullEncodingInventory.Limits(
            input.length, 100, 100, 100, 10000, 4096, 1000, 255, 100, 1000, 1000);
    assertEquals(
        input.length,
        new FullEncodingInventory().read(source, exact, new Sink(), () -> false).consumedBytes());
    assertFalse(closed.get());
    byte[] extra = Arrays.copyOf(input, input.length + 1);
    extra[extra.length - 1] = 'x';
    assertEquals(
        FullEncodingInventoryException.Reason.LIMIT,
        assertThrows(
                FullEncodingInventoryException.class,
                () ->
                    new FullEncodingInventory()
                        .read(new ByteArrayInputStream(extra), exact, new Sink(), () -> false))
            .reason());
  }

  @Test
  void callbackFailuresStayExactAndLateIoKeepsCauseWithoutCompletion() throws Exception {
    var original = new IllegalStateException("staging failure");
    for (int stage = 0; stage < 5; stage++) {
      int selected = stage;
      var sink =
          new Sink() {
            public void header(IndexedEncodingParser.Layout h) {
              if (selected == 0) throw original;
            }

            public void specification(int n, String v) {
              if (selected == 1) throw original;
              super.specification(n, v);
            }

            public void content(int p, long n, EncodingManifest.ContentEntry e) {
              if (selected == 2) throw original;
              super.content(p, n, e);
            }

            public void encoding(int p, long n, EncodingManifest.EncodedEntry e) {
              if (selected == 3) throw original;
              super.encoding(p, n, e);
            }

            public void trailer(String v) {
              if (selected == 4) throw original;
              super.trailer(v);
            }
          };
      assertSame(
          original,
          assertThrows(
              IllegalStateException.class,
              () ->
                  new FullEncodingInventory()
                      .read(new ByteArrayInputStream(sample()), LIMITS, sink, () -> false)));
    }
    var failure = new IOException("EOF transport failed");
    byte[] input = sample();
    var source =
        new FilterInputStream(new ByteArrayInputStream(input)) {
          public int read() throws IOException {
            int b = super.read();
            if (b == -1) throw failure;
            return b;
          }
        };
    var sink = new Sink();
    var failed =
        assertThrows(
            FullEncodingInventoryException.class,
            () -> new FullEncodingInventory().read(source, LIMITS, sink, () -> false));
    assertEquals(FullEncodingInventoryException.Reason.IO, failed.reason());
    assertSame(failure, failed.getCause());
    assertEquals(1, sink.encoded.size());
    assertNull(sink.trailer);
  }

  @Test
  void cancellationAtCallbackAndFinalEofCannotReturnCompletion() throws Exception {
    byte[] bytes = sample();
    for (boolean finalEof : new boolean[] {false, true}) {
      var canceled = new java.util.concurrent.atomic.AtomicBoolean();
      var source =
          new ByteArrayInputStream(bytes) {
            public synchronized int read() {
              int b = super.read();
              if (finalEof && b == -1) canceled.set(true);
              return b;
            }
          };
      var sink =
          new Sink() {
            public void content(int p, long n, EncodingManifest.ContentEntry e) {
              super.content(p, n, e);
              if (!finalEof) canceled.set(true);
            }
          };
      assertEquals(
          FullEncodingInventoryException.Reason.CANCELLED,
          assertThrows(
                  FullEncodingInventoryException.class,
                  () -> new FullEncodingInventory().read(source, LIMITS, sink, canceled::get))
              .reason());
      assertNull(sink.trailer);
    }
  }

  @Test
  void perPageRecordCandidateSpecAndTrailerLimitsAreWiredToTheExistingGrammar() throws Exception {
    byte[] records =
        fixture(new byte[][] {concat(cp(1, 1, 10), cp(2, 1, 11))}, new byte[0][], false, "");
    for (var limit :
        List.of(
            new FullEncodingInventory.Limits(
                100000, 1, 100, 100, 10000, 4096, 1000, 255, 100, 1000, 1000),
            new FullEncodingInventory.Limits(
                100000, 100, 100, 100, 10000, 4096, 1, 255, 100, 1000, 1000))) {
      var sink = new Sink();
      assertEquals(
          FullEncodingInventoryException.Reason.LIMIT,
          assertThrows(
                  FullEncodingInventoryException.class,
                  () ->
                      new FullEncodingInventory()
                          .read(new ByteArrayInputStream(records), limit, sink, () -> false))
              .reason());
      assertTrue(sink.contents.isEmpty(), "Page is validated before provisional emission");
    }
    byte[] candidates = fixture(new byte[][] {cp(1, 1, 10, 11)}, new byte[0][], false, "");
    var one =
        new FullEncodingInventory.Limits(
            100000, 100, 100, 100, 10000, 4096, 1000, 1, 100, 1000, 1000);
    assertEquals(
        FullEncodingInventoryException.Reason.LIMIT,
        assertThrows(
                FullEncodingInventoryException.class,
                () ->
                    new FullEncodingInventory()
                        .read(new ByteArrayInputStream(candidates), one, new Sink(), () -> false))
            .reason());
    var fewSpecs =
        new FullEncodingInventory.Limits(
            100000, 100, 100, 100, 10000, 4096, 1000, 255, 2, 1000, 1000);
    assertEquals(
        FullEncodingInventoryException.Reason.LIMIT,
        assertThrows(
                FullEncodingInventoryException.class,
                () ->
                    new FullEncodingInventory()
                        .read(
                            new ByteArrayInputStream(sample()), fewSpecs, new Sink(), () -> false))
            .reason());
    var shortTrailer =
        new FullEncodingInventory.Limits(
            100000, 100, 100, 100, 10000, 4096, 1000, 255, 100, 1000, 1);
    assertEquals(
        FullEncodingInventoryException.Reason.LIMIT,
        assertThrows(
                FullEncodingInventoryException.class,
                () ->
                    new FullEncodingInventory()
                        .read(
                            new ByteArrayInputStream(
                                fixture(new byte[0][], new byte[0][], false, "ab")),
                            shortTrailer,
                            new Sink(),
                            () -> false))
            .reason());
    for (int offset : new int[] {2, 3, 4, 17}) {
      byte[] unsupported = sample();
      unsupported[offset] = 2;
      assertEquals(
          FullEncodingInventoryException.Reason.UNSUPPORTED,
          assertThrows(FullEncodingInventoryException.class, () -> read(unsupported)).reason());
    }
    assertEquals(
        FullEncodingInventoryException.Reason.MALFORMED,
        assertThrows(
                FullEncodingInventoryException.class,
                () -> read(fixture(new byte[][] {cp(1, 1, 0)}, new byte[0][], false, "")))
            .reason());
  }

  @Test
  void cancellationDuringPageReadPrecedesAnyRecordEmission() throws Exception {
    byte[] bytes = sample();
    var canceled = new java.util.concurrent.atomic.AtomicBoolean();
    var source =
        new ByteArrayInputStream(bytes) {
          public synchronized int read(byte[] target, int offset, int length) {
            int n = super.read(target, offset, length);
            if (pos >= 22 + SPECS.length + 32 + PAGE) canceled.set(true);
            return n;
          }
        };
    var sink = new Sink();
    assertEquals(
        FullEncodingInventoryException.Reason.CANCELLED,
        assertThrows(
                FullEncodingInventoryException.class,
                () -> new FullEncodingInventory().read(source, LIMITS, sink, canceled::get))
            .reason());
    assertTrue(sink.contents.isEmpty());
  }

  @Test
  void thousandsOfPagesGenerateIncrementallyWithoutWholeSourceOrRecordMaps() throws Exception {
    int pages = 3000;
    var generator = new GeneratedInventory(pages);
    var totals = new long[2];
    var sink =
        new FullEncodingInventory.Consumer() {
          public void header(IndexedEncodingParser.Layout layout) {
            assertEquals(pages, layout.pageCount(IndexedEncodingParser.Table.CONTENT));
          }

          public void specification(int n, String value) {}

          public void content(int p, long n, EncodingManifest.ContentEntry e) {
            assertEquals(totals[0]++, n);
          }

          public void encoding(int p, long n, EncodingManifest.EncodedEntry e) {
            assertEquals(totals[1]++, n);
          }

          public void trailer(String value) {
            assertEquals("", value);
          }
        };
    var limits =
        new FullEncodingInventory.Limits(
            generator.length, 2L * pages, pages, 2 * pages, pages * 32, 1024, 1, 1, 2, 2, 0);
    var complete = new FullEncodingInventory().read(generator, limits, sink, () -> false);
    assertArrayEquals(new long[] {pages, pages}, totals);
    assertEquals(generator.length, complete.consumedBytes());
  }

  FullEncodingInventory.Completion read(byte[] bytes) throws Exception {
    return new FullEncodingInventory()
        .read(new ByteArrayInputStream(bytes), LIMITS, new Sink(), () -> false);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] result = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }

  static final class GeneratedInventory extends InputStream {
    final int pages;
    final byte[] prefix, encodingIndex;
    final long length;
    long position;
    byte[] current;
    int currentPage = -1;
    boolean currentEncoding;

    GeneratedInventory(int pages) throws Exception {
      this.pages = pages;
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeBytes("EN");
      out.write(new byte[] {1, 16, 16});
      out.writeShort(1);
      out.writeShort(1);
      out.writeInt(pages);
      out.writeInt(pages);
      out.writeByte(0);
      out.writeInt(2);
      out.write(new byte[] {'n', 0});
      var encoded = new ByteArrayOutputStream();
      var eout = new DataOutputStream(encoded);
      for (int i = 0; i < pages; i++) {
        byte[] content = page(i, false), encoding = page(i, true);
        out.write(number(i + 1));
        out.write(java.security.MessageDigest.getInstance("MD5").digest(content));
        eout.write(number(100000 + i));
        eout.write(java.security.MessageDigest.getInstance("MD5").digest(encoding));
      }
      prefix = bytes.toByteArray();
      encodingIndex = encoded.toByteArray();
      length = prefix.length + pages * 1024L + encodingIndex.length + pages * 1024L;
    }

    static byte[] number(int value) {
      byte[] key = new byte[16];
      java.nio.ByteBuffer.wrap(key).putInt(12, value);
      return key;
    }

    static byte[] page(int ordinal, boolean encoded) throws Exception {
      byte[] page = new byte[1024];
      var b = java.nio.ByteBuffer.wrap(page);
      if (encoded) {
        b.put(number(100000 + ordinal));
        b.putInt(0);
        b.put(new byte[] {0, 0, 0, 0, 32});
      } else {
        b.put((byte) 1);
        b.put(new byte[] {0, 0, 0, 0, 16});
        b.put(number(ordinal + 1));
        b.put(number(100000 + ordinal));
      }
      return page;
    }

    public int read() throws IOException {
      if (position >= length) return -1;
      long offset = position++;
      if (offset < prefix.length) return prefix[(int) offset] & 255;
      offset -= prefix.length;
      boolean encoding = false;
      if (offset >= pages * 1024L) {
        offset -= pages * 1024L;
        if (offset < encodingIndex.length) return encodingIndex[(int) offset] & 255;
        offset -= encodingIndex.length;
        encoding = true;
      }
      int page = (int) (offset / 1024);
      if (page != currentPage || encoding != currentEncoding) {
        try {
          current = page(page, encoding);
        } catch (Exception failed) {
          throw new IOException(failed);
        }
        currentPage = page;
        currentEncoding = encoding;
      }
      return current[(int) (offset % 1024)] & 255;
    }

    public int read(byte[] target, int offset, int count) throws IOException {
      assertTrue(count <= 8192);
      if (position >= length) return -1;
      int n = (int) Math.min(count, length - position);
      for (int i = 0; i < n; i++) target[offset + i] = (byte) read();
      return n;
    }
  }
}
