package io.vibeminer.format.wow.root;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WowRootParserTest {
  final WowRootParser parser = new WowRootParser();
  final WowRootParser.Limits limits = new WowRootParser.Limits(8192, 100, 10, 50);

  @Test
  void preservesAllLocaleAndContentVariantsWithoutChoosingOne() throws Exception {
    var root =
        parse(
            fixture(
                20,
                new Block(
                    0, 2, new long[] {5, 2}, new int[] {1, 2}, new long[] {0xfedcba9876543210L, 0}),
                new Block(0x10000008L, 16, new long[] {5}, new int[] {3}, null)));
    var variants = root.findFileDataId(5);
    assertEquals(2, variants.size());
    assertEquals(3, root.recordCount());
    assertEquals(2, variants.getFirst().localeFlags());
    assertEquals(16, variants.getLast().localeFlags());
    assertEquals("01010101010101010101010101010101", variants.getFirst().contentKey());
    assertEquals(Optional.of("fedcba9876543210"), variants.getFirst().nameHash());
    assertTrue(variants.getLast().nameHash().isEmpty());
    assertEquals(Optional.of("0000000000000000"), root.findFileDataId(8).getFirst().nameHash());
    assertEquals(0x10000008L, variants.getLast().contentFlags());
    assertTrue(root.findFileDataId(999).isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> variants.clear());
    assertThrows(UnsupportedOperationException.class, () -> root.entriesByFileDataId().clear());
  }

  @Test
  void supportsExplicit20Or24ByteVersionOneHeadersOnly() throws Exception {
    for (int header : new int[] {20, 24}) {
      var root =
          parse(fixture(header, new Block(0, 0, new long[] {0}, new int[] {1}, new long[] {42})));
      assertEquals(header, root.headerSize());
      assertEquals(1, root.version());
    }
  }

  @Test
  void unsignedDeltaArithmeticAcceptsHighestFinalIdAndResetsPerBlock() throws Exception {
    var root =
        parse(
            fixture(
                20,
                new Block(0x10000000L, 0, new long[] {0xffffffffL}, new int[] {1}, null),
                new Block(0x10000000L, 0, new long[] {0xffffffffL}, new int[] {2}, null)));
    assertEquals(2, root.findFileDataId(0xffffffffL).size());
    failure(
        WowRootException.Reason.MALFORMED,
        () ->
            parse(
                fixture(
                    20,
                    new Block(
                        0x10000000L, 0, new long[] {0xffffffffL, 0}, new int[] {1, 2}, null))));
    failure(
        WowRootException.Reason.MALFORMED,
        () ->
            parse(
                fixture(
                    20,
                    new Block(
                        0x10000000L, 0, new long[] {1, 0xffffffffL}, new int[] {1, 2}, null))));
  }

  @Test
  void everyTruncationAndTrailingPartialBlockFails() throws Exception {
    byte[] full =
        fixture(20, new Block(0, 2, new long[] {1, 1}, new int[] {1, 2}, new long[] {1, 2}));
    for (int i = 0; i < full.length; i++) {
      byte[] part = Arrays.copyOf(full, i);
      failure(WowRootException.Reason.MALFORMED, () -> parse(part));
    }
    failure(WowRootException.Reason.MALFORMED, () -> parse(Arrays.copyOf(full, full.length + 1)));
  }

  @Test
  void rejectsUnsupportedSignatureVersionAndHeaderExtension() throws Exception {
    byte[] full = fixture(24, new Block(0x10000000L, 2, new long[] {1}, new int[] {1}, null));
    byte[] magic = full.clone();
    magic[0] = 'X';
    failure(WowRootException.Reason.UNSUPPORTED, () -> parse(magic));
    byte[] version = full.clone();
    put32(version, 8, 3);
    failure(WowRootException.Reason.UNSUPPORTED, () -> parse(version));
    byte[] extension = full.clone();
    extension[20] = 1;
    failure(WowRootException.Reason.UNSUPPORTED, () -> parse(extension));
    byte[] size = full.clone();
    put32(size, 4, 28);
    failure(WowRootException.Reason.UNSUPPORTED, () -> parse(size));
  }

  @Test
  void validatesDeclaredTotalAndNamedCountsAgainstAllVariants() throws Exception {
    byte[] full = fixture(20, new Block(0, 2, new long[] {1}, new int[] {1}, new long[] {1}));
    byte[] total = full.clone();
    put32(total, 12, 2);
    failure(WowRootException.Reason.MALFORMED, () -> parse(total));
    byte[] named = full.clone();
    put32(named, 16, 0);
    failure(WowRootException.Reason.MALFORMED, () -> parse(named));
    byte[] excessiveNamed = full.clone();
    put32(excessiveNamed, 16, 2);
    failure(WowRootException.Reason.MALFORMED, () -> parse(excessiveNamed));
  }

  @Test
  void acceptsEmptyManifestAndZeroEntryBlocks() throws Exception {
    assertTrue(parse(fixture(20)).entriesByFileDataId().isEmpty());
    assertEquals(
        0, parse(fixture(20, new Block(0, 2, new long[0], new int[0], new long[0]))).recordCount());
  }

  @Test
  void boundsInputHeaderCountsBlockCountsAndUnsignedBlockLength() throws Exception {
    failure(WowRootException.Reason.LIMIT, () -> parse(new byte[8193]));
    byte[] total = fixture(20);
    put32(total, 12, -1);
    failure(WowRootException.Reason.LIMIT, () -> parse(total));
    byte[] block = fixture(20, new Block(0, 2, new long[0], new int[0], new long[0]));
    put32(block, 20, -1);
    failure(WowRootException.Reason.LIMIT, () -> parse(block));
    var zeroBlocks = new WowRootParser.Limits(8192, 100, 0, 50);
    failure(
        WowRootException.Reason.LIMIT,
        () ->
            parser.parse(
                fixture(20, new Block(0, 2, new long[0], new int[0], new long[0])),
                zeroBlocks,
                () -> false));
  }

  @Test
  void cancellationAndInvalidCallerLookupsAreExplicit() throws Exception {
    failure(WowRootException.Reason.CANCELLED, () -> parser.parse(new byte[0], limits, () -> true));
    byte[] full = fixture(20, new Block(0, 2, new long[] {1}, new int[] {1}, new long[] {1}));
    int[] polls = {0};
    failure(
        WowRootException.Reason.CANCELLED, () -> parser.parse(full, limits, () -> ++polls[0] > 2));
    var root = parse(full);
    assertThrows(IllegalArgumentException.class, () -> root.findFileDataId(-1));
    assertThrows(IllegalArgumentException.class, () -> root.findFileDataId(0x100000000L));
  }

  @Test
  void versionTwoUsesPacked17ByteHeadersAndPreservesRawFlagTriplets() throws Exception {
    var root =
        parse(
            fixture2(
                new Block2(8, 0x10000000L, 2, 2, new long[] {5, 2}, new int[] {1, 2}, null),
                new Block2(
                    0,
                    0x80000000L,
                    0x80,
                    16,
                    new long[] {5},
                    new int[] {3},
                    new long[] {0xfedcba9876543210L})));
    assertEquals(2, root.version());
    assertEquals(24, root.headerSize());
    assertEquals(3, root.recordCount());
    assertEquals(1, root.namedRecordCount());
    var variants = root.findFileDataId(5);
    assertEquals(2, variants.size());
    assertEquals(0x10040008L, variants.getFirst().contentFlags());
    assertEquals(8, variants.getFirst().rawContentFlags().first());
    assertEquals(0x10000000L, variants.getFirst().rawContentFlags().second());
    assertEquals(2, variants.getFirst().rawContentFlags().third());
    assertTrue(variants.getFirst().nameHash().isEmpty());
    assertEquals(Optional.of("fedcba9876543210"), variants.getLast().nameHash());
    assertEquals(0x81000000L, variants.getLast().contentFlags());
    assertEquals(0x80, variants.getLast().rawContentFlags().third());
    assertEquals(2, variants.getFirst().localeFlags());
    assertEquals(16, variants.getLast().localeFlags());
  }

  @Test
  void versionTwoDoesNotDiscardRawDifferencesHiddenByNormalizedOr() throws Exception {
    var root =
        parse(
            fixture2(
                new Block2(8, 0, 0, 2, new long[] {1}, new int[] {1}, new long[] {1}),
                new Block2(0, 8, 0, 2, new long[] {1}, new int[] {1}, new long[] {1})));
    var variants = root.findFileDataId(1);
    assertEquals(variants.getFirst().contentFlags(), variants.getLast().contentFlags());
    assertNotEquals(variants.getFirst().rawContentFlags(), variants.getLast().rawContentFlags());
  }

  @Test
  void versionTwoChecksEveryTruncationCountsAndDeltaOverflow() throws Exception {
    byte[] full = fixture2(new Block2(0x10000000L, 0, 0, 2, new long[] {1}, new int[] {1}, null));
    for (int i = 0; i < full.length; i++) {
      byte[] part = Arrays.copyOf(full, i);
      failure(WowRootException.Reason.MALFORMED, () -> parse(part));
    }
    byte[] wrong = full.clone();
    put32(wrong, 12, 2);
    failure(WowRootException.Reason.MALFORMED, () -> parse(wrong));
    failure(
        WowRootException.Reason.MALFORMED,
        () ->
            parse(
                fixture2(
                    new Block2(
                        0x10000000L,
                        0,
                        0,
                        2,
                        new long[] {0xffffffffL, 0},
                        new int[] {1, 2},
                        null))));
  }

  @Test
  void formerlyUnenumeratedFlagsRemainOpaqueForNamedAndNamelessV1AndV2() throws Exception {
    for (long opaque : new long[] {0x20, 0x40, 0x60, 0xffffffffL}) {
      for (boolean nameless : new boolean[] {false, true}) {
        long flags = nameless ? opaque | 0x10000000L : opaque & ~0x10000000L;
        long[] hashes = nameless ? null : new long[] {42};
        var v1 =
            parse(fixture(20, new Block(flags, 0xffffffffL, new long[] {1}, new int[] {1}, hashes)))
                .findFileDataId(1)
                .getFirst();
        assertEquals(flags, v1.contentFlags());
        assertEquals(flags, v1.rawContentFlags().first());
        assertEquals(0xffffffffL, v1.localeFlags());
        assertEquals(!nameless, v1.nameHash().isPresent());
        var v2 =
            parse(
                    fixture2(
                        new Block2(
                            0, flags, 0, 0xffffffffL, new long[] {1}, new int[] {1}, hashes)))
                .findFileDataId(1)
                .getFirst();
        assertEquals(flags, v2.contentFlags());
        assertEquals(0, v2.rawContentFlags().first());
        assertEquals(flags, v2.rawContentFlags().second());
        assertEquals(!nameless, v2.nameHash().isPresent());
      }
    }
  }

  @Test
  void malformedOpaqueFlagBlockRetainsNumericDiagnosticContext() throws Exception {
    byte[] full =
        fixture2(new Block2(0x20, 0x40, 0xff, 2, new long[] {1}, new int[] {1}, new long[] {42}));
    byte[] truncated = Arrays.copyOf(full, full.length - 1);
    var error = assertThrows(WowRootException.class, () -> parse(truncated));
    assertEquals(WowRootException.Reason.MALFORMED, error.reason());
    assertTrue(error.getMessage().contains("version=2"));
    assertTrue(error.getMessage().contains("decodedOffset="));
    assertTrue(error.getMessage().contains("rawFlags1=32"));
    assertTrue(error.getMessage().contains("rawFlags2=64"));
    assertTrue(error.getMessage().contains("rawFlags3=255"));
  }

  WowRootManifest parse(byte[] bytes) throws WowRootException {
    return parser.parse(bytes, limits, () -> false);
  }

  static void failure(
      WowRootException.Reason reason, org.junit.jupiter.api.function.Executable action) {
    assertEquals(reason, assertThrows(WowRootException.class, action).reason());
  }

  record Block(long flags, long locale, long[] deltas, int[] contents, long[] hashes) {}

  record Block2(
      long flags1,
      long flags2,
      int flags3,
      long locale,
      long[] deltas,
      int[] contents,
      long[] hashes) {}

  static byte[] fixture2(Block2... blocks) throws Exception {
    int total = 0, named = 0;
    for (Block2 block : blocks) {
      total += block.deltas.length;
      if (((block.flags1 | block.flags2 | ((long) block.flags3 << 17)) & 0x10000000L) == 0)
        named += block.deltas.length;
    }
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("TSFM");
    le32(out, 24);
    le32(out, 2);
    le32(out, total);
    le32(out, named);
    le32(out, 0);
    for (Block2 block : blocks) {
      le32(out, block.deltas.length);
      le32(out, block.locale);
      le32(out, block.flags1);
      le32(out, block.flags2);
      out.writeByte(block.flags3);
      for (long delta : block.deltas) le32(out, delta);
      for (int value : block.contents) {
        byte[] hash = new byte[16];
        Arrays.fill(hash, (byte) value);
        out.write(hash);
      }
      if (((block.flags1 | block.flags2 | ((long) block.flags3 << 17)) & 0x10000000L) == 0)
        for (long hash : block.hashes) {
          le32(out, hash);
          le32(out, hash >>> 32);
        }
    }
    return bytes.toByteArray();
  }

  static byte[] fixture(int header, Block... blocks) throws Exception {
    int count = 0, named = 0;
    for (Block block : blocks) {
      count += block.deltas.length;
      if ((block.flags & 0x10000000L) == 0) named += block.deltas.length;
    }
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeBytes("TSFM");
    le32(out, header);
    le32(out, 1);
    le32(out, count);
    le32(out, named);
    if (header == 24) le32(out, 0);
    for (Block block : blocks) {
      le32(out, block.deltas.length);
      le32(out, block.flags);
      le32(out, block.locale);
      for (long delta : block.deltas) le32(out, delta);
      for (int value : block.contents) {
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) value);
        out.write(key);
      }
      if ((block.flags & 0x10000000L) == 0)
        for (long hash : block.hashes) {
          le32(out, hash);
          le32(out, hash >>> 32);
        }
    }
    return bytes.toByteArray();
  }

  static void le32(DataOutputStream out, long value) throws Exception {
    out.writeInt(Integer.reverseBytes((int) value));
  }

  static void put32(byte[] bytes, int at, int value) {
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
  }
}
