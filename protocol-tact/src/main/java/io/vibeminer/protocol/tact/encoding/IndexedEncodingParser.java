package io.vibeminer.protocol.tact.encoding;

import static io.vibeminer.protocol.tact.encoding.EncodingManifestException.Reason.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Bounded EN-v1 selected-page grammar and hash validation. Outer source authentication belongs to
 * the caller.
 */
public final class IndexedEncodingParser {
  public enum Table {
    CONTENT,
    ENCODING
  }

  public record Limits(
      int maxPages, int maxIndexBytes, int maxPageBytes, int maxRecordsPerPage, int maxCandidates) {
    public Limits {
      if (maxPages < 0
          || maxIndexBytes < 0
          || maxPageBytes < 1024
          || maxRecordsPerPage < 1
          || maxCandidates < 1
          || maxCandidates > 255) throw new IllegalArgumentException("Invalid indexed EN bounds");
    }
  }

  public static final class Layout {
    private final int cp, ep;
    private final long cn, en, spec;

    private Layout(int cp, int ep, long cn, long en, long spec) {
      this.cp = cp;
      this.ep = ep;
      this.cn = cn;
      this.en = en;
      this.spec = spec;
    }

    public long specBytes() {
      return spec;
    }

    public long pageCount(Table t) {
      return t == Table.CONTENT ? cn : en;
    }

    public int pageBytes(Table t) {
      return t == Table.CONTENT ? cp : ep;
    }

    public long indexOffset(Table t) {
      return t == Table.CONTENT ? 22 + spec : 22 + spec + cn * 32 + cn * cp;
    }

    public long pagesOffset(Table t) {
      return indexOffset(t) + pageCount(t) * 32;
    }

    public long minimumDecodedBytes() {
      return pagesOffset(Table.ENCODING) + en * ep;
    }
  }

  public static final class PageReference {
    private final PageIndex owner;
    private final int ordinal;

    private PageReference(PageIndex owner, int ordinal) {
      this.owner = owner;
      this.ordinal = ordinal;
    }

    public int ordinal() {
      return ordinal;
    }

    public long decodedOffset() {
      return owner.layout.pagesOffset(owner.table)
          + (long) ordinal * owner.layout.pageBytes(owner.table);
    }

    public int length() {
      return owner.layout.pageBytes(owner.table);
    }

    public String firstKey() {
      return owner.key(ordinal);
    }

    public String expectedMd5() {
      return HexFormat.of().formatHex(owner.bytes, ordinal * 32 + 16, ordinal * 32 + 32);
    }
  }

  public static final class PageIndex {
    private final Layout layout;
    private final Table table;
    private final byte[] bytes;

    private PageIndex(Layout layout, Table table, byte[] bytes) {
      this.layout = layout;
      this.table = table;
      this.bytes = bytes.clone();
    }

    private String key(int n) {
      return HexFormat.of().formatHex(bytes, n * 32, n * 32 + 16);
    }

    public Optional<PageReference> pageFor(String lookup) {
      String k = canonical(lookup);
      int lo = 0, hi = bytes.length / 32 - 1, result = -1;
      while (lo <= hi) {
        int m = (lo + hi) >>> 1;
        if (key(m).compareTo(k) <= 0) {
          result = m;
          lo = m + 1;
        } else hi = m - 1;
      }
      return result < 0 ? Optional.empty() : Optional.of(new PageReference(this, result));
    }
  }

  /**
   * ESpec text is deliberately unresolved; resolving it requires a separate bounded string-table
   * operation.
   */
  public record EncodedMetadata(String encodingKey, long encodedSize, long specIndex) {}

  public Layout parseHeader(byte[] bytes, Limits limits) throws EncodingManifestException {
    Objects.requireNonNull(bytes);
    Objects.requireNonNull(limits);
    if (bytes.length != 22) throw fail(MALFORMED, "EN header must be exactly 22 bytes");
    if (bytes[0] != 'E' || bytes[1] != 'N') throw fail(MALFORMED, "Invalid EN signature");
    if (bytes[2] != 1 || bytes[3] != 16 || bytes[4] != 16 || bytes[17] != 0)
      throw fail(UNSUPPORTED, "Unsupported EN layout");
    int cp = u16(bytes, 5) * 1024, ep = u16(bytes, 7) * 1024;
    long cn = u32(bytes, 9), en = u32(bytes, 13);
    if (cp == 0 || ep == 0) throw fail(MALFORMED, "Empty EN page size");
    if (cp > limits.maxPageBytes
        || ep > limits.maxPageBytes
        || cn + en > limits.maxPages
        || cn * 32 > limits.maxIndexBytes
        || en * 32 > limits.maxIndexBytes) throw fail(LIMIT, "EN index/page bounds exceeded");
    return new Layout(cp, ep, cn, en, u32(bytes, 18));
  }

  public PageIndex parseIndex(
      Layout layout, Table table, byte[] bytes, Limits limits, BooleanSupplier cancelled)
      throws EncodingManifestException {
    Objects.requireNonNull(layout);
    Objects.requireNonNull(table);
    Objects.requireNonNull(bytes);
    Objects.requireNonNull(limits);
    check(cancelled);
    if (bytes.length > limits.maxIndexBytes || layout.pageCount(table) > limits.maxPages)
      throw fail(LIMIT, "EN index bounds exceeded");
    if (bytes.length != layout.pageCount(table) * 32)
      throw fail(MALFORMED, "EN index length mismatch");
    PageIndex index = new PageIndex(layout, table, bytes);
    String previous = null;
    for (int i = 0; i < bytes.length / 32; i++) {
      check(cancelled);
      String key = index.key(i);
      if (zero(bytes, i * 32, 16) || previous != null && key.compareTo(previous) <= 0)
        throw fail(MALFORMED, "EN index keys empty or unordered");
      previous = key;
    }
    return index;
  }

  public Optional<EncodingManifest.ContentEntry> lookupContent(
      PageIndex index,
      PageReference page,
      byte[] bytes,
      String lookup,
      Limits limits,
      BooleanSupplier cancelled)
      throws EncodingManifestException {
    String target = canonical(lookup);
    validatePage(index, page, bytes, Table.CONTENT, limits, cancelled);
    validateTargetInterval(index, page, target);
    String previous = null;
    EncodingManifest.ContentEntry found = null;
    int records = 0, at = 0;
    while (at < bytes.length) {
      check(cancelled);
      int count = bytes[at] & 255;
      if (count == 0) {
        requireZeroPadding(bytes, at, cancelled);
        break;
      }
      if (count > limits.maxCandidates) throw fail(LIMIT, "EN candidate limit exceeded");
      int recordBytes = 22 + 16 * count;
      if (recordBytes > bytes.length - at) throw fail(MALFORMED, "Content record crosses page");
      String key = key(bytes, at + 6);
      validateRecordOrder(index, page, key, previous);
      if (++records > limits.maxRecordsPerPage) throw fail(LIMIT, "EN page record limit exceeded");
      var candidates = new ArrayList<String>(count);
      for (int i = 0; i < count; i++) {
        String candidate = key(bytes, at + 22 + i * 16);
        if (zero(bytes, at + 22 + i * 16, 16)) throw fail(MALFORMED, "Empty encoding key");
        candidates.add(candidate);
      }
      if (key.equals(target))
        found = new EncodingManifest.ContentEntry(key, u40(bytes, at + 1), candidates);
      previous = key;
      at += recordBytes;
    }
    if (previous == null) throw fail(MALFORMED, "Empty indexed page");
    return Optional.ofNullable(found);
  }

  public Optional<EncodedMetadata> lookupEncoding(
      PageIndex index,
      PageReference page,
      byte[] bytes,
      String lookup,
      Limits limits,
      BooleanSupplier cancelled)
      throws EncodingManifestException {
    String target = canonical(lookup);
    validatePage(index, page, bytes, Table.ENCODING, limits, cancelled);
    validateTargetInterval(index, page, target);
    String previous = null;
    EncodedMetadata found = null;
    int records = 0, at = 0;
    while (at < bytes.length) {
      check(cancelled);
      if (bytes.length - at < 16) {
        requireZeroPadding(bytes, at, cancelled);
        break;
      }
      if (zero(bytes, at, 16)) {
        int rest = at + 16;
        if (bytes.length - rest >= 4 && u32(bytes, rest) == 0xffffffffL) {
          if (page.ordinal != index.layout.pageCount(Table.ENCODING) - 1)
            throw fail(MALFORMED, "Early EN final marker");
          rest += 4;
        }
        requireZeroPadding(bytes, rest, cancelled);
        break;
      }
      if (bytes.length - at < 25) throw fail(MALFORMED, "Encoding record crosses page");
      String key = key(bytes, at);
      validateRecordOrder(index, page, key, previous);
      if (++records > limits.maxRecordsPerPage) throw fail(LIMIT, "EN page record limit exceeded");
      if (key.equals(target))
        found = new EncodedMetadata(key, u40(bytes, at + 20), u32(bytes, at + 16));
      previous = key;
      at += 25;
    }
    if (previous == null) throw fail(MALFORMED, "Empty indexed page");
    return Optional.ofNullable(found);
  }

  private static void validatePage(
      PageIndex index,
      PageReference page,
      byte[] bytes,
      Table table,
      Limits limits,
      BooleanSupplier cancelled)
      throws EncodingManifestException {
    Objects.requireNonNull(index);
    Objects.requireNonNull(page);
    Objects.requireNonNull(bytes);
    Objects.requireNonNull(limits);
    check(cancelled);
    if (page.owner != index || index.table != table)
      throw fail(MALFORMED, "Page/index binding mismatch");
    if (bytes.length > limits.maxPageBytes) throw fail(LIMIT, "EN page byte limit exceeded");
    if (bytes.length != page.length()) throw fail(MALFORMED, "EN page length mismatch");
    verifyPageHash(page, bytes, cancelled);
  }

  private static void verifyPageHash(PageReference page, byte[] bytes, BooleanSupplier cancelled)
      throws EncodingManifestException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
    for (int at = 0; at < bytes.length; at += 8192) {
      check(cancelled);
      md.update(bytes, at, Math.min(8192, bytes.length - at));
    }
    if (!HexFormat.of().formatHex(md.digest()).equals(page.expectedMd5()))
      throw fail(INTEGRITY, "EN selected page MD5 mismatch");
  }

  private static void validateTargetInterval(PageIndex index, PageReference page, String target)
      throws EncodingManifestException {
    if (target.compareTo(page.firstKey()) < 0
        || page.ordinal + 1 < index.bytes.length / 32
            && target.compareTo(index.key(page.ordinal + 1)) >= 0)
      throw fail(MALFORMED, "Lookup key does not belong to selected index interval");
  }

  private static void validateRecordOrder(
      PageIndex index, PageReference page, String key, String previous)
      throws EncodingManifestException {
    if (key.equals("00000000000000000000000000000000")
        || previous == null && !key.equals(page.firstKey())
        || previous != null && key.compareTo(previous) <= 0
        || page.ordinal + 1 < index.bytes.length / 32
            && key.compareTo(index.key(page.ordinal + 1)) >= 0)
      throw fail(MALFORMED, "EN page key outside ordered index interval");
  }

  private static void requireZeroPadding(byte[] bytes, int at, BooleanSupplier cancelled)
      throws EncodingManifestException {
    for (; at < bytes.length; at++) {
      if (at % 8192 == 0) check(cancelled);
      if (bytes[at] != 0) throw fail(MALFORMED, "Nonzero EN padding");
    }
  }

  private static String canonical(String key) {
    if (key == null || !key.matches("[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Complete 16-byte key required");
    return key.toLowerCase(Locale.ROOT);
  }

  private static String key(byte[] b, int at) {
    return HexFormat.of().formatHex(b, at, at + 16);
  }

  private static boolean zero(byte[] b, int at, int count) {
    for (int i = 0; i < count; i++) if (b[at + i] != 0) return false;
    return true;
  }

  private static int u16(byte[] b, int at) {
    return (b[at] & 255) * 256 + (b[at + 1] & 255);
  }

  private static long u32(byte[] b, int at) {
    return (long) u16(b, at) * 65536 + u16(b, at + 2);
  }

  private static long u40(byte[] b, int at) {
    return (long) (b[at] & 255) * 4294967296L + u32(b, at + 1);
  }

  private static void check(BooleanSupplier cancelled) throws EncodingManifestException {
    if (Objects.requireNonNull(cancelled).getAsBoolean())
      throw fail(CANCELLED, "EN lookup cancelled");
  }

  private static EncodingManifestException fail(
      EncodingManifestException.Reason reason, String message) {
    return new EncodingManifestException(reason, message);
  }
}
