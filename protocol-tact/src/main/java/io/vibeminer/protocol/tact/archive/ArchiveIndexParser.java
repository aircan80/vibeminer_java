package io.vibeminer.protocol.tact.archive;

import static io.vibeminer.protocol.tact.archive.ArchiveIndexException.Reason.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Archive-only v1 index authentication. A match authenticates index metadata, not archive bytes.
 *
 * <p>Supported layout follows TACTSharp GroupIndex/IndexInstance at revision
 * 67fd53ac852ca297e9bf95bb41d1a97da202eba4: footer metadata is little-endian while page sizes and
 * archive offsets are big-endian. Caller must keep input arrays unchanged during calls. Returned
 * bindings defensively retain their own metadata.
 *
 * <p>Filename identity is MD5 of the final 28 bytes, not MD5 of the entire index. Advertised
 * configuration lengths are deliberately not used as authenticated geometry.
 */
public final class ArchiveIndexParser {
  private static final int PAGE_BYTES = 4096, RECORD_BYTES = 24, ENTRIES_PER_PAGE = 170;

  public record Limits(long maxEntries, int maxPages, long maxIndexBytes) {
    public Limits {
      if (maxEntries < 0 || maxPages < 0 || maxIndexBytes < 28)
        throw new IllegalArgumentException("Invalid archive index bounds");
    }
  }

  public static final class Footer {
    private final String identity;
    private final long entries, total;
    private final int pages;
    private final byte[] tocHash;

    private Footer(String identity, long entries, int pages, long total, byte[] tocHash) {
      this.identity = identity;
      this.entries = entries;
      this.pages = pages;
      this.total = total;
      this.tocHash = tocHash.clone();
    }

    public String indexIdentity() {
      return identity;
    }

    public long entryCount() {
      return entries;
    }

    public int pageCount() {
      return pages;
    }

    public long indexBytes() {
      return total;
    }

    public long tocOffset() {
      return (long) pages * PAGE_BYTES;
    }

    public int tocBytes() {
      return pages * 24;
    }
  }

  public static final class Toc {
    private final Footer footer;
    private final byte[] bytes;

    private Toc(Footer footer, byte[] bytes) {
      this.footer = footer;
      this.bytes = bytes.clone();
    }

    private String maximum(int page) {
      return key(bytes, page * 16);
    }

    /** Archive boundaries are maximum keys; choose first maximum >= requested key. */
    public Optional<PageReference> pageFor(String requested) {
      String target = canonical(requested);
      int lo = 0, hi = footer.pages;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (maximum(mid).compareTo(target) < 0) lo = mid + 1;
        else hi = mid;
      }
      return lo == footer.pages ? Optional.empty() : Optional.of(new PageReference(this, lo));
    }
  }

  public static final class PageReference {
    private final Toc owner;
    private final int ordinal;

    private PageReference(Toc owner, int ordinal) {
      this.owner = owner;
      this.ordinal = ordinal;
    }

    public int ordinal() {
      return ordinal;
    }

    public long indexOffset() {
      return (long) ordinal * PAGE_BYTES;
    }

    public int length() {
      return PAGE_BYTES;
    }
  }

  public record IndexMatch(
      String indexIdentity, String encodingKey, long archiveOffset, long encodedSize) {
    public long archiveEndExclusive() {
      return Math.addExact(archiveOffset, encodedSize);
    }
  }

  public Footer authenticateFooter(
      byte[] footer,
      String expectedIdentity,
      long observedTotal,
      Limits limits,
      BooleanSupplier cancelled)
      throws ArchiveIndexException {
    Objects.requireNonNull(footer);
    Objects.requireNonNull(limits);
    check(cancelled);
    String identity = canonical(expectedIdentity);
    if (footer.length != 28) throw fail(MALFORMED, "Archive footer must contain exactly 28 bytes");
    if (!HexFormat.of().formatHex(md5(footer, cancelled)).equals(identity))
      throw fail(INTEGRITY, "Archive filename/footer MD5 mismatch");
    byte[] internal = Arrays.copyOfRange(footer, 8, 28);
    Arrays.fill(internal, 12, 20, (byte) 0);
    if (!matches(md5(internal, cancelled), footer, 20, 8))
      throw fail(INTEGRITY, "Archive footer checksum mismatch");
    if (footer[8] != 1
        || footer[9] != 0
        || footer[10] != 0
        || footer[11] != 4
        || footer[12] != 4
        || footer[13] != 4
        || footer[14] != 16
        || footer[15] != 8)
      throw fail(UNSUPPORTED, "Unsupported archive index version, flags or widths");
    long count = 0;
    for (int i = 0; i < 4; i++) count |= (long) (footer[16 + i] & 255) << (8 * i);
    long pages = (count + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE,
        total = pages * (PAGE_BYTES + 24L) + 28;
    if (count > limits.maxEntries
        || pages > limits.maxPages
        || pages * 24 > Integer.MAX_VALUE
        || total > limits.maxIndexBytes) throw fail(LIMIT, "Archive index geometry exceeds bounds");
    if (total != observedTotal)
      throw fail(INTEGRITY, "Observed archive index length conflicts with authenticated geometry");
    return new Footer(identity, count, (int) pages, total, Arrays.copyOf(footer, 8));
  }

  public Toc authenticateToc(Footer footer, byte[] bytes, BooleanSupplier cancelled)
      throws ArchiveIndexException {
    Objects.requireNonNull(footer);
    Objects.requireNonNull(bytes);
    check(cancelled);
    if (bytes.length != footer.tocBytes()) throw fail(MALFORMED, "Archive TOC length mismatch");
    if (!matches(md5(bytes, cancelled), footer.tocHash, 0, 8))
      throw fail(INTEGRITY, "Archive TOC checksum mismatch");
    String previous = null;
    for (int page = 0; page < footer.pages; page++) {
      check(cancelled);
      String maximum = key(bytes, page * 16);
      if (zero(bytes, page * 16, 16) || previous != null && maximum.compareTo(previous) <= 0)
        throw fail(MALFORMED, "Archive maximum-key boundaries are empty or unordered");
      previous = maximum;
    }
    return new Toc(footer, bytes);
  }

  public Optional<IndexMatch> lookup(
      Toc toc, PageReference page, byte[] bytes, String requested, BooleanSupplier cancelled)
      throws ArchiveIndexException {
    Objects.requireNonNull(toc);
    Objects.requireNonNull(page);
    Objects.requireNonNull(bytes);
    check(cancelled);
    String target = canonical(requested);
    if (page.owner != toc) throw fail(MALFORMED, "Archive page belongs to another TOC");
    if (bytes.length != PAGE_BYTES) throw fail(MALFORMED, "Archive page length mismatch");
    if (target.compareTo(toc.maximum(page.ordinal)) > 0
        || page.ordinal > 0 && target.compareTo(toc.maximum(page.ordinal - 1)) <= 0)
      throw fail(MALFORMED, "Requested key does not belong to selected maximum-key interval");
    int hashOffset = toc.footer.pages * 16 + page.ordinal * 8;
    if (!matches(md5(bytes, cancelled), toc.bytes, hashOffset, 8))
      throw fail(INTEGRITY, "Archive padded-page checksum mismatch");
    int count =
        (int)
            Math.min(ENTRIES_PER_PAGE, toc.footer.entries - (long) page.ordinal * ENTRIES_PER_PAGE);
    String previous = page.ordinal == 0 ? null : toc.maximum(page.ordinal - 1);
    IndexMatch found = null;
    for (int record = 0; record < count; record++) {
      check(cancelled);
      int at = record * RECORD_BYTES;
      String key = key(bytes, at);
      if (zero(bytes, at, 16) || previous != null && key.compareTo(previous) <= 0)
        throw fail(MALFORMED, "Archive entry keys are empty or unordered");
      long size = u32(bytes, at + 16), offset = u32(bytes, at + 20);
      if (size == 0) throw fail(MALFORMED, "Archive entry has zero encoded size");
      if (key.equals(target)) found = new IndexMatch(toc.footer.identity, key, offset, size);
      previous = key;
    }
    if (!toc.maximum(page.ordinal).equals(previous))
      throw fail(MALFORMED, "Archive page last key differs from TOC maximum");
    for (int at = count * RECORD_BYTES; at < PAGE_BYTES; at++) {
      if (at % 512 == 0) check(cancelled);
      if (bytes[at] != 0) throw fail(MALFORMED, "Nonzero archive page padding");
    }
    check(cancelled);
    return Optional.ofNullable(found);
  }

  private static byte[] md5(byte[] bytes, BooleanSupplier cancelled) throws ArchiveIndexException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
    for (int at = 0; at < bytes.length; at += 8192) {
      check(cancelled);
      digest.update(bytes, at, Math.min(8192, bytes.length - at));
    }
    check(cancelled);
    return digest.digest();
  }

  private static boolean matches(byte[] actual, byte[] expected, int at, int count) {
    int difference = 0;
    for (int i = 0; i < count; i++) difference |= actual[i] ^ expected[at + i];
    return difference == 0;
  }

  private static String canonical(String key) {
    if (key == null || !key.matches("[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Complete 16-byte key required");
    return key.toLowerCase(Locale.ROOT);
  }

  private static String key(byte[] bytes, int at) {
    return HexFormat.of().formatHex(bytes, at, at + 16);
  }

  private static boolean zero(byte[] bytes, int at, int count) {
    for (int i = 0; i < count; i++) if (bytes[at + i] != 0) return false;
    return true;
  }

  private static long u32(byte[] bytes, int at) {
    long value = 0;
    for (int i = 0; i < 4; i++) value = (value << 8) | (bytes[at + i] & 255);
    return value;
  }

  private static void check(BooleanSupplier cancelled) throws ArchiveIndexException {
    if (Objects.requireNonNull(cancelled).getAsBoolean())
      throw fail(CANCELLED, "Archive index lookup cancelled");
  }

  private static ArchiveIndexException fail(ArchiveIndexException.Reason reason, String message) {
    return new ArchiveIndexException(reason, message);
  }
}
