package io.vibeminer.protocol.tact.archive;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ArchiveIndexParserTest {
  final ArchiveIndexParser parser = new ArchiveIndexParser();
  final ArchiveIndexParser.Limits limits = new ArchiveIndexParser.Limits(10000, 100, 1000000);

  record Fixture(byte[] footer, byte[] toc, byte[][] pages, String identity, long total) {}

  static byte[] digest(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("MD5").digest(bytes);
  }

  static String key(int n) {
    return String.format("%032x", n);
  }

  Fixture fixture(int count) throws Exception {
    int pages = (count + 169) / 170;
    byte[][] data = new byte[pages][4096];
    byte[] toc = new byte[pages * 24];
    for (int i = 0; i < count; i++) {
      int page = i / 170, at = i % 170 * 24;
      System.arraycopy(HexFormat.of().parseHex(key(i + 1)), 0, data[page], at, 16);
      ByteBuffer.wrap(data[page]).putInt(at + 16, 7).putInt(at + 20, -16);
    }
    for (int p = 0; p < pages; p++) {
      System.arraycopy(
          HexFormat.of().parseHex(key(Math.min(count, (p + 1) * 170))), 0, toc, p * 16, 16);
      System.arraycopy(digest(data[p]), 0, toc, pages * 16 + p * 8, 8);
    }
    byte[] footer = new byte[28];
    System.arraycopy(digest(toc), 0, footer, 0, 8);
    footer[8] = 1;
    footer[11] = 4;
    footer[12] = 4;
    footer[13] = 4;
    footer[14] = 16;
    footer[15] = 8;
    ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).putInt(16, count);
    System.arraycopy(digest(java.util.Arrays.copyOfRange(footer, 8, 28)), 0, footer, 20, 8);
    return new Fixture(
        footer, toc, data, HexFormat.of().formatHex(digest(footer)), pages * 4120L + 28);
  }

  @Test
  void maxBoundarySelectsCorrectPageAndUnsignedOffsets() throws Exception {
    var f = fixture(171);
    var footer = parser.authenticateFooter(f.footer, f.identity, f.total, limits, () -> false);
    var toc = parser.authenticateToc(footer, f.toc, () -> false);
    var ref = toc.pageFor(key(171)).orElseThrow();
    assertEquals(1, ref.ordinal());
    var result = parser.lookup(toc, ref, f.pages[1], key(171), () -> false).orElseThrow();
    assertEquals(4294967280L, result.archiveOffset());
    assertEquals(7, result.encodedSize());
    assertTrue(toc.pageFor(key(172)).isEmpty());
    assertEquals(0, toc.pageFor(key(170)).orElseThrow().ordinal());
  }

  @Test
  void footerIdentityGeometryAndUnsupportedShapeRejected() throws Exception {
    var f = fixture(1);
    assertThrows(
        ArchiveIndexException.class,
        () -> parser.authenticateFooter(f.footer, key(1), f.total, limits, () -> false));
    assertThrows(
        ArchiveIndexException.class,
        () -> parser.authenticateFooter(f.footer, f.identity, f.total + 1, limits, () -> false));
    f.footer[12] = 6;
    java.util.Arrays.fill(f.footer, 20, 28, (byte) 0);
    System.arraycopy(digest(java.util.Arrays.copyOfRange(f.footer, 8, 28)), 0, f.footer, 20, 8);
    String name = HexFormat.of().formatHex(digest(f.footer));
    assertEquals(
        ArchiveIndexException.Reason.UNSUPPORTED,
        assertThrows(
                ArchiveIndexException.class,
                () -> parser.authenticateFooter(f.footer, name, f.total, limits, () -> false))
            .reason());
  }

  @Test
  void tocAndPageTamperingRejected() throws Exception {
    var f = fixture(171);
    var footer = parser.authenticateFooter(f.footer, f.identity, f.total, limits, () -> false);
    var toc = parser.authenticateToc(footer, f.toc, () -> false);
    f.toc[0] ^= 1;
    assertThrows(
        ArchiveIndexException.class, () -> parser.authenticateToc(footer, f.toc, () -> false));
    f.pages[1][100] = 1;
    assertThrows(
        ArchiveIndexException.class,
        () ->
            parser.lookup(
                toc, toc.pageFor(key(171)).orElseThrow(), f.pages[1], key(171), () -> false));
  }

  Fixture reseal(Fixture f) throws Exception {
    int pages = f.pages.length;
    for (int p = 0; p < pages; p++)
      System.arraycopy(digest(f.pages[p]), 0, f.toc, pages * 16 + p * 8, 8);
    System.arraycopy(digest(f.toc), 0, f.footer, 0, 8);
    java.util.Arrays.fill(f.footer, 20, 28, (byte) 0);
    System.arraycopy(digest(java.util.Arrays.copyOfRange(f.footer, 8, 28)), 0, f.footer, 20, 8);
    return new Fixture(
        f.footer, f.toc, f.pages, HexFormat.of().formatHex(digest(f.footer)), f.total);
  }

  @Test
  void authenticatedDirtyPaddingDuplicateKeysAndWrongMaximumAreMalformed() throws Exception {
    var original = fixture(171);
    original.pages[1][100] = 1;
    var dirty = reseal(original);
    var ft =
        parser.authenticateFooter(dirty.footer, dirty.identity, dirty.total, limits, () -> false);
    var toc = parser.authenticateToc(ft, dirty.toc, () -> false);
    assertEquals(
        ArchiveIndexException.Reason.MALFORMED,
        assertThrows(
                ArchiveIndexException.class,
                () ->
                    parser.lookup(
                        toc,
                        toc.pageFor(key(171)).orElseThrow(),
                        dirty.pages[1],
                        key(171),
                        () -> false))
            .reason());
    var duplicate = fixture(2);
    System.arraycopy(duplicate.pages[0], 0, duplicate.pages[0], 24, 16);
    duplicate = reseal(duplicate);
    final var dup = duplicate;
    var dt =
        parser.authenticateToc(
            parser.authenticateFooter(dup.footer, dup.identity, dup.total, limits, () -> false),
            dup.toc,
            () -> false);
    assertThrows(
        ArchiveIndexException.class,
        () ->
            parser.lookup(dt, dt.pageFor(key(2)).orElseThrow(), dup.pages[0], key(2), () -> false));
    var mismatch = fixture(1);
    mismatch.toc[15] = 2;
    mismatch = reseal(mismatch);
    final var mm = mismatch;
    var mt =
        parser.authenticateToc(
            parser.authenticateFooter(mm.footer, mm.identity, mm.total, limits, () -> false),
            mm.toc,
            () -> false);
    assertThrows(
        ArchiveIndexException.class,
        () ->
            parser.lookup(mt, mt.pageFor(key(1)).orElseThrow(), mm.pages[0], key(1), () -> false));
  }

  @Test
  void zeroSizeUnsignedEndAbsentKeyAndBinding() throws Exception {
    var original = fixture(1);
    ByteBuffer.wrap(original.pages[0]).putInt(16, -1).putInt(20, -1);
    var f = reseal(original);
    var ft = parser.authenticateFooter(f.footer, f.identity, f.total, limits, () -> false);
    var a = parser.authenticateToc(ft, f.toc, () -> false);
    var b = parser.authenticateToc(ft, f.toc, () -> false);
    var result =
        parser
            .lookup(a, a.pageFor(key(1)).orElseThrow(), f.pages[0], key(1), () -> false)
            .orElseThrow();
    assertEquals(8589934590L, result.archiveEndExclusive());
    assertTrue(
        parser
            .lookup(a, a.pageFor(key(0)).orElseThrow(), f.pages[0], key(0), () -> false)
            .isEmpty());
    assertThrows(
        ArchiveIndexException.class,
        () -> parser.lookup(a, b.pageFor(key(1)).orElseThrow(), f.pages[0], key(1), () -> false));
    ByteBuffer.wrap(f.pages[0]).putInt(16, 0);
    var zero = reseal(f);
    var z =
        parser.authenticateToc(
            parser.authenticateFooter(zero.footer, zero.identity, zero.total, limits, () -> false),
            zero.toc,
            () -> false);
    assertThrows(
        ArchiveIndexException.class,
        () ->
            parser.lookup(z, z.pageFor(key(1)).orElseThrow(), zero.pages[0], key(1), () -> false));
  }

  @Test
  void footerInternalChecksumLimitsAndCancellation() throws Exception {
    var f = fixture(171);
    f.footer[20] ^= 1;
    String identity = HexFormat.of().formatHex(digest(f.footer));
    assertEquals(
        ArchiveIndexException.Reason.INTEGRITY,
        assertThrows(
                ArchiveIndexException.class,
                () -> parser.authenticateFooter(f.footer, identity, f.total, limits, () -> false))
            .reason());
    var good = fixture(171);
    assertEquals(
        ArchiveIndexException.Reason.LIMIT,
        assertThrows(
                ArchiveIndexException.class,
                () ->
                    parser.authenticateFooter(
                        good.footer,
                        good.identity,
                        good.total,
                        new ArchiveIndexParser.Limits(1, 1, 5000),
                        () -> false))
            .reason());
    assertEquals(
        ArchiveIndexException.Reason.CANCELLED,
        assertThrows(
                ArchiveIndexException.class,
                () ->
                    parser.authenticateFooter(
                        good.footer, good.identity, good.total, limits, () -> true))
            .reason());
  }

  @Test
  void observedGeometryWorksWithoutTrustingConflictingAdvertisedSize() throws Exception {
    var f = fixture(7060);
    assertEquals(173068, f.total);
    var footer = parser.authenticateFooter(f.footer, f.identity, 173068, limits, () -> false);
    assertEquals(42, footer.pageCount());
    assertEquals(1008, footer.tocBytes());
    assertThrows(
        ArchiveIndexException.class,
        () -> parser.authenticateFooter(f.footer, f.identity, 135988, limits, () -> false));
  }

  @Test
  void tocOrderingExactLengthsAndLateCancellation() throws Exception {
    var original = fixture(171);
    System.arraycopy(original.toc, 0, original.toc, 16, 16);
    var f = reseal(original);
    var footer = parser.authenticateFooter(f.footer, f.identity, f.total, limits, () -> false);
    assertEquals(
        ArchiveIndexException.Reason.MALFORMED,
        assertThrows(
                ArchiveIndexException.class,
                () -> parser.authenticateToc(footer, f.toc, () -> false))
            .reason());
    var good = fixture(1);
    var gf = parser.authenticateFooter(good.footer, good.identity, good.total, limits, () -> false);
    assertThrows(
        ArchiveIndexException.class, () -> parser.authenticateToc(gf, new byte[23], () -> false));
    assertEquals(
        ArchiveIndexException.Reason.CANCELLED,
        assertThrows(
                ArchiveIndexException.class, () -> parser.authenticateToc(gf, good.toc, () -> true))
            .reason());
    var toc = parser.authenticateToc(gf, good.toc, () -> false);
    assertThrows(
        ArchiveIndexException.class,
        () ->
            parser.lookup(
                toc, toc.pageFor(key(1)).orElseThrow(), new byte[4095], key(1), () -> false));
    assertEquals(
        ArchiveIndexException.Reason.CANCELLED,
        assertThrows(
                ArchiveIndexException.class,
                () ->
                    parser.lookup(
                        toc, toc.pageFor(key(1)).orElseThrow(), good.pages[0], key(1), () -> true))
            .reason());
  }
}
