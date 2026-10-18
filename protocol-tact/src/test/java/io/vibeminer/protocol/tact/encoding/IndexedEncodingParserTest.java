package io.vibeminer.protocol.tact.encoding;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class IndexedEncodingParserTest {
  final IndexedEncodingParser parser = new IndexedEncodingParser();
  final IndexedEncodingParser.Limits limits =
      new IndexedEncodingParser.Limits(8, 4096, 4096, 32, 8);
  final String key = "20000000000000000000000000000000";

  byte[] header() {
    var b = ByteBuffer.allocate(22);
    b.put((byte) 'E')
        .put((byte) 'N')
        .put((byte) 1)
        .put((byte) 16)
        .put((byte) 16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(2)
        .putInt(0)
        .put((byte) 0)
        .putInt(16000000);
    return b.array();
  }

  byte[] page(String key, int candidates) {
    byte[] p = new byte[1024];
    p[0] = (byte) candidates;
    p[5] = 42;
    System.arraycopy(HexFormat.of().parseHex(key), 0, p, 6, 16);
    for (int i = 0; i < candidates; i++) p[22 + i * 16] = (byte) (i + 1);
    return p;
  }

  byte[] index(byte[] first, byte[] second) throws Exception {
    byte[] result = new byte[64];
    System.arraycopy(first, 6, result, 0, 16);
    System.arraycopy(MessageDigest.getInstance("MD5").digest(first), 0, result, 16, 16);
    System.arraycopy(second, 6, result, 32, 16);
    System.arraycopy(MessageDigest.getInstance("MD5").digest(second), 0, result, 48, 16);
    return result;
  }

  @Test
  void selectedPagePreservesAllCandidatesWithoutSpecTable() throws Exception {
    var layout = parser.parseHeader(header(), limits);
    byte[] first = page("10000000000000000000000000000000", 1), second = page(key, 2);
    var index =
        parser.parseIndex(
            layout, IndexedEncodingParser.Table.CONTENT, index(first, second), limits, () -> false);
    var ref = index.pageFor(key).orElseThrow();
    assertEquals(1, ref.ordinal());
    assertEquals(16000000L + 22 + 64 + 1024, ref.decodedOffset());
    var found = parser.lookupContent(index, ref, second, key, limits, () -> false).orElseThrow();
    assertEquals(42, found.decodedSize());
    assertEquals(2, found.encodingKeys().size());
  }

  @Test
  void corruptedSelectedPageFailsIntegrity() throws Exception {
    byte[] a = page("10000000000000000000000000000000", 1), b = page(key, 1);
    var idx =
        parser.parseIndex(
            parser.parseHeader(header(), limits),
            IndexedEncodingParser.Table.CONTENT,
            index(a, b),
            limits,
            () -> false);
    b[100] = 1;
    assertEquals(
        EncodingManifestException.Reason.INTEGRITY,
        assertThrows(
                EncodingManifestException.class,
                () ->
                    parser.lookupContent(
                        idx, idx.pageFor(key).orElseThrow(), b, key, limits, () -> false))
            .reason());
  }

  @Test
  void indexLimitsOrderingCancellationAndExactLength() throws Exception {
    byte[] a = page(key, 1), b = page("10000000000000000000000000000000", 1);
    var layout = parser.parseHeader(header(), limits);
    assertThrows(
        EncodingManifestException.class,
        () ->
            parser.parseIndex(
                layout, IndexedEncodingParser.Table.CONTENT, index(a, b), limits, () -> false));
    assertThrows(
        EncodingManifestException.class,
        () ->
            parser.parseIndex(
                layout, IndexedEncodingParser.Table.CONTENT, new byte[63], limits, () -> false));
    assertEquals(
        EncodingManifestException.Reason.CANCELLED,
        assertThrows(
                EncodingManifestException.class,
                () ->
                    parser.parseIndex(
                        layout,
                        IndexedEncodingParser.Table.CONTENT,
                        new byte[64],
                        limits,
                        () -> true))
            .reason());
    assertThrows(
        EncodingManifestException.class,
        () -> parser.parseHeader(header(), new IndexedEncodingParser.Limits(1, 4096, 4096, 32, 8)));
  }

  @Test
  void encodedPageKeepsUnsignedSizesAndUnresolvedSpecIndex() throws Exception {
    byte[] h = header();
    ByteBuffer.wrap(h).putInt(9, 0).putInt(13, 1);
    byte[] page = new byte[1024];
    System.arraycopy(HexFormat.of().parseHex(key), 0, page, 0, 16);
    ByteBuffer.wrap(page).putInt(16, 123456);
    page[20] = 1;
    page[24] = 7;
    ByteBuffer.wrap(page).putInt(41, -1);
    byte[] idx = new byte[32];
    System.arraycopy(page, 0, idx, 0, 16);
    System.arraycopy(MessageDigest.getInstance("MD5").digest(page), 0, idx, 16, 16);
    var index =
        parser.parseIndex(
            parser.parseHeader(h, limits),
            IndexedEncodingParser.Table.ENCODING,
            idx,
            limits,
            () -> false);
    var result =
        parser
            .lookupEncoding(index, index.pageFor(key).orElseThrow(), page, key, limits, () -> false)
            .orElseThrow();
    assertEquals(4294967303L, result.encodedSize());
    assertEquals(123456, result.specIndex());
    assertTrue(index.pageFor("00000000000000000000000000000001").isEmpty());
  }

  @Test
  void hashValidButWrongFirstKeyAndForeignReferencesAreRejected() throws Exception {
    byte[] a = page("10000000000000000000000000000000", 1), b = page(key, 1), idx = index(a, b);
    idx[0] = 5;
    var layout = parser.parseHeader(header(), limits);
    var one =
        parser.parseIndex(layout, IndexedEncodingParser.Table.CONTENT, idx, limits, () -> false);
    var two =
        parser.parseIndex(layout, IndexedEncodingParser.Table.CONTENT, idx, limits, () -> false);
    assertThrows(
        EncodingManifestException.class,
        () ->
            parser.lookupContent(
                one,
                one.pageFor("10000000000000000000000000000000").orElseThrow(),
                a,
                key,
                limits,
                () -> false));
    assertThrows(
        EncodingManifestException.class,
        () ->
            parser.lookupContent(one, two.pageFor(key).orElseThrow(), b, key, limits, () -> false));
  }

  @Test
  void correctPageCanProveLocalMissButHonorsRecordAndCandidateBounds() throws Exception {
    byte[] a = page("10000000000000000000000000000000", 1), b = page(key, 2);
    var idx =
        parser.parseIndex(
            parser.parseHeader(header(), limits),
            IndexedEncodingParser.Table.CONTENT,
            index(a, b),
            limits,
            () -> false);
    var ref = idx.pageFor(key).orElseThrow();
    assertTrue(
        parser
            .lookupContent(idx, ref, b, "21000000000000000000000000000000", limits, () -> false)
            .isEmpty());
    var tiny = new IndexedEncodingParser.Limits(8, 4096, 4096, 32, 1);
    assertEquals(
        EncodingManifestException.Reason.LIMIT,
        assertThrows(
                EncodingManifestException.class,
                () -> parser.lookupContent(idx, ref, b, key, tiny, () -> false))
            .reason());
    assertEquals(
        EncodingManifestException.Reason.CANCELLED,
        assertThrows(
                EncodingManifestException.class,
                () -> parser.lookupContent(idx, ref, b, key, limits, () -> true))
            .reason());
  }
}
