package io.vibeminer.protocol.tact.encoding;

import static io.vibeminer.protocol.tact.encoding.FullEncodingInventoryException.Reason.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Complete streaming enumeration of the existing admitted EN-v1 grammar, not source authenticity.
 */
public final class FullEncodingInventory {
  public record Limits(
      long maxDecodedBytes,
      long maxRecords,
      long maxCandidateReferences,
      int maxPages,
      int maxIndexBytes,
      int maxPageBytes,
      int maxRecordsPerPage,
      int maxKeysPerContent,
      int maxSpecStrings,
      int maxSpecBytes,
      int maxTrailerBytes) {
    public Limits {
      if (maxDecodedBytes < 22
          || maxRecords < 0
          || maxCandidateReferences < 0
          || maxPages < 0
          || maxIndexBytes < 0
          || maxIndexBytes > Integer.MAX_VALUE - 8
          || maxPageBytes < 1024
          || maxPageBytes > Integer.MAX_VALUE - 8
          || maxRecordsPerPage < 1
          || maxKeysPerContent < 1
          || maxKeysPerContent > 255
          || maxSpecStrings < 0
          || maxSpecBytes < 0
          || maxSpecBytes > Integer.MAX_VALUE - 8
          || maxTrailerBytes < 0
          || maxTrailerBytes > Integer.MAX_VALUE - 8)
        throw new IllegalArgumentException("Invalid full EN bounds");
    }

    IndexedEncodingParser.Limits indexed(int records) {
      return new IndexedEncodingParser.Limits(
          maxPages, maxIndexBytes, maxPageBytes, records, maxKeysPerContent);
    }
  }

  /**
   * All calls are provisional through complete trailer/EOF and the caller's separate source proof.
   */
  public interface Consumer {
    void header(IndexedEncodingParser.Layout layout);

    void specification(int index, String value);

    void content(int page, long ordinal, EncodingManifest.ContentEntry entry);

    void encoding(int page, long ordinal, EncodingManifest.EncodedEntry entry);

    void trailer(String value);
  }

  public record Completion(
      int contentPages,
      int encodingPages,
      long contentRecords,
      long encodingRecords,
      long candidateReferences,
      int specifications,
      int trailerBytes,
      long consumedBytes) {}

  /**
   * Never closes input. Retains bounded ESpec strings, one current index (raw bytes plus the
   * existing validator's defensive copy), and one padded page. Each page has one existing
   * hash/grammar validation followed by one emission grammar walk: two grammar passes plus one MD5
   * pass. Per-page validation work is bounded independently by page/record/candidate limits, even
   * if an aggregate emission limit subsequently refuses that page. No complete CKey/EKey maps or
   * reverse join are retained. Trailer termination permits one EOF probe beyond the admitted byte
   * ceiling.
   */
  public Completion read(
      InputStream input, Limits limits, Consumer consumer, BooleanSupplier cancelled)
      throws FullEncodingInventoryException {
    Objects.requireNonNull(input);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(consumer);
    Objects.requireNonNull(cancelled);
    var reader = new Reader(input, limits.maxDecodedBytes(), cancelled);
    var parser = new IndexedEncodingParser();
    IndexedEncodingParser.Layout layout;
    try {
      layout = parser.parseHeader(reader.bytes(22), limits.indexed(limits.maxRecordsPerPage()));
    } catch (EncodingManifestException failure) {
      throw protocol(failure);
    }
    if (layout.specBytes() > limits.maxSpecBytes()
        || layout.minimumDecodedBytes() > limits.maxDecodedBytes())
      throw fail(LIMIT, "EN declared layout/specification bytes exceed policy");
    if (layout.pageCount(IndexedEncodingParser.Table.CONTENT)
                + layout.pageCount(IndexedEncodingParser.Table.ENCODING)
            > limits.maxRecords()
        || layout.pageCount(IndexedEncodingParser.Table.CONTENT) > limits.maxCandidateReferences())
      throw fail(LIMIT, "EN nonempty page minimum exceeds record/candidate policy");
    reader.check();
    consumer.header(layout);
    reader.check();
    var specs = specifications(reader.bytes((int) layout.specBytes()), limits, reader);
    for (int i = 0; i < specs.size(); i++) {
      reader.check();
      consumer.specification(i, specs.get(i));
      reader.check();
    }
    var state = new State();
    table(
        parser,
        layout,
        IndexedEncodingParser.Table.CONTENT,
        reader,
        limits,
        consumer,
        specs,
        state);
    table(
        parser,
        layout,
        IndexedEncodingParser.Table.ENCODING,
        reader,
        limits,
        consumer,
        specs,
        state);
    String trailer = reader.trailer(limits.maxTrailerBytes());
    reader.check();
    consumer.trailer(trailer);
    reader.check();
    return new Completion(
        (int) layout.pageCount(IndexedEncodingParser.Table.CONTENT),
        (int) layout.pageCount(IndexedEncodingParser.Table.ENCODING),
        state.contents,
        state.encodings,
        state.candidates,
        specs.size(),
        trailer.length(),
        reader.consumed);
  }

  private static final class State {
    long contents, encodings, candidates;
  }

  private static void table(
      IndexedEncodingParser parser,
      IndexedEncodingParser.Layout layout,
      IndexedEncodingParser.Table table,
      Reader reader,
      Limits limits,
      Consumer consumer,
      List<String> specs,
      State state)
      throws FullEncodingInventoryException {
    int count = (int) layout.pageCount(table);
    byte[] rawIndex = reader.bytes(Math.toIntExact((long) count * 32));
    IndexedEncodingParser.PageIndex index;
    try {
      index =
          parser.parseIndex(
              layout,
              table,
              rawIndex,
              limits.indexed(limits.maxRecordsPerPage()),
              reader.cancelled);
    } catch (EncodingManifestException failure) {
      throw protocol(failure);
    }
    String previous = null;
    for (int ordinal = 0; ordinal < count; ordinal++) {
      reader.check();
      long remaining = limits.maxRecords() - state.contents - state.encodings;
      if (remaining <= 0)
        throw fail(LIMIT, "EN total record policy exhausted before next nonempty page");
      int perPage = (int) Math.min(limits.maxRecordsPerPage(), remaining);
      var pageLimits = limits.indexed(perPage);
      String first = key(rawIndex, ordinal * 32);
      var reference =
          index
              .pageFor(first)
              .orElseThrow(() -> new AssertionError("Validated index lost first key"));
      byte[] page = reader.bytes(layout.pageBytes(table));
      try {
        if (table == IndexedEncodingParser.Table.CONTENT)
          parser.lookupContent(index, reference, page, first, pageLimits, reader.cancelled);
        else parser.lookupEncoding(index, reference, page, first, pageLimits, reader.cancelled);
      } catch (EncodingManifestException failure) {
        throw protocol(failure);
      }
      int at = 0;
      while (at < page.length) {
        reader.check();
        if (table == IndexedEncodingParser.Table.CONTENT) {
          int candidates = page[at] & 255;
          if (candidates == 0) break;
          String content = key(page, at + 6);
          if (previous != null && content.compareTo(previous) <= 0)
            throw fail(MALFORMED, "EN global CKey order differs");
          if (candidates > limits.maxCandidateReferences() - state.candidates)
            throw fail(LIMIT, "EN total candidate-reference policy exceeded");
          var keys = new ArrayList<String>(candidates);
          for (int n = 0; n < candidates; n++) {
            reader.check();
            keys.add(key(page, at + 22 + n * 16));
          }
          var entry = new EncodingManifest.ContentEntry(content, u40(page, at + 1), keys);
          consumer.content(ordinal, state.contents, entry);
          reader.check();
          state.contents++;
          state.candidates += candidates;
          previous = content;
          at += 22 + 16 * candidates;
        } else {
          if (page.length - at < 16 || zeroKey(page, at)) break;
          String encoding = key(page, at);
          long spec = u32(page, at + 16);
          if (previous != null && encoding.compareTo(previous) <= 0)
            throw fail(MALFORMED, "EN global EKey order differs");
          if (spec >= specs.size()) throw fail(MALFORMED, "EN ESpec reference is unavailable");
          var entry =
              new EncodingManifest.EncodedEntry(
                  encoding, u40(page, at + 20), spec, specs.get((int) spec));
          consumer.encoding(ordinal, state.encodings, entry);
          reader.check();
          state.encodings++;
          previous = encoding;
          at += 25;
        }
      }
    }
  }

  private static List<String> specifications(byte[] bytes, Limits limits, Reader reader)
      throws FullEncodingInventoryException {
    var result = new ArrayList<String>();
    int from = 0;
    for (int at = 0; at < bytes.length; at++) {
      reader.check();
      if (bytes[at] == 0) {
        addSpec(result, new String(bytes, from, at - from, StandardCharsets.US_ASCII), limits);
        from = at + 1;
      } else if ((bytes[at] & 255) < 32 || (bytes[at] & 255) > 126)
        throw fail(UNSUPPORTED, "EN ESpec metadata must use printable ASCII");
    }
    if (bytes.length > 0) {
      if (from != bytes.length) throw fail(MALFORMED, "Unterminated EN ESpec string table");
      addSpec(result, "", limits);
    }
    return List.copyOf(result);
  }

  private static void addSpec(List<String> specs, String value, Limits limits)
      throws FullEncodingInventoryException {
    if (specs.size() >= limits.maxSpecStrings())
      throw fail(LIMIT, "EN specification-count policy exceeded");
    specs.add(value);
  }

  private static String key(byte[] bytes, int at) {
    return HexFormat.of().formatHex(bytes, at, at + 16);
  }

  private static boolean zeroKey(byte[] bytes, int at) {
    for (int i = 0; i < 16; i++) if (bytes[at + i] != 0) return false;
    return true;
  }

  private static long u32(byte[] bytes, int at) {
    return ((long) (bytes[at] & 255) << 24)
        | ((long) (bytes[at + 1] & 255) << 16)
        | ((long) (bytes[at + 2] & 255) << 8)
        | (bytes[at + 3] & 255);
  }

  private static long u40(byte[] bytes, int at) {
    return ((long) (bytes[at] & 255) << 32) | u32(bytes, at + 1);
  }

  private static FullEncodingInventoryException protocol(EncodingManifestException failure) {
    return new FullEncodingInventoryException(
        FullEncodingInventoryException.Reason.valueOf(failure.reason().name()),
        failure.getMessage(),
        failure);
  }

  private static FullEncodingInventoryException fail(
      FullEncodingInventoryException.Reason reason, String message) {
    return new FullEncodingInventoryException(reason, message);
  }

  private static final class Reader {
    final InputStream input;
    final long maximum;
    final BooleanSupplier cancelled;
    long consumed;

    Reader(InputStream input, long maximum, BooleanSupplier cancelled) {
      this.input = input;
      this.maximum = maximum;
      this.cancelled = cancelled;
    }

    void check() throws FullEncodingInventoryException {
      if (cancelled.getAsBoolean()) throw fail(CANCELLED, "EN enumeration canceled");
    }

    byte[] bytes(int count) throws FullEncodingInventoryException {
      check();
      if (count < 0 || count > maximum - consumed)
        throw fail(LIMIT, "EN read exceeds decoded byte policy");
      byte[] result = new byte[count];
      int at = 0;
      while (at < count) {
        check();
        int n;
        try {
          n = input.read(result, at, Math.min(8192, count - at));
        } catch (IOException failure) {
          throw new FullEncodingInventoryException(IO, "Cannot read EN inventory", failure);
        }
        check();
        if (n < 0) throw fail(MALFORMED, "Truncated EN inventory");
        if (n == 0) {
          int b = one(false);
          if (b < 0) throw fail(MALFORMED, "Truncated EN inventory");
          result[at++] = (byte) b;
        } else {
          at += n;
          consumed += n;
        }
      }
      return result;
    }

    int one(boolean eofProbe) throws FullEncodingInventoryException {
      check();
      if (!eofProbe && consumed == maximum) throw fail(LIMIT, "EN decoded byte policy exhausted");
      int b;
      try {
        b = input.read();
      } catch (IOException failure) {
        throw new FullEncodingInventoryException(IO, "Cannot read EN inventory", failure);
      }
      check();
      if (b >= 0) {
        if (consumed == maximum) throw fail(LIMIT, "EN decoded byte ceiling exceeded");
        consumed++;
      }
      return b;
    }

    String trailer(int limit) throws FullEncodingInventoryException {
      var bytes = new ByteArrayOutputStream(Math.min(64, limit));
      while (true) {
        int b = one(true);
        if (b < 0) break;
        if (bytes.size() == limit) throw fail(LIMIT, "EN opaque trailer policy exceeded");
        if (b < 32 || b > 126)
          throw fail(UNSUPPORTED, "EN opaque trailer must use printable ASCII");
        bytes.write(b);
      }
      check();
      return bytes.toString(StandardCharsets.US_ASCII);
    }
  }
}
