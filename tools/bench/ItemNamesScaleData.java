package io.vibeminer.infrastructure.item;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/**
 * Entirely authored scale corpus; streams the decoded record area to scratch, never a table array.
 */
final class ItemNamesScaleData {
  static final int ITEMS = 59859, NAMES = 175503, AREA = 60161452, FIRST = 1001;

  record Source(Path encoded, long decodedBytes, String ekey, String sha256, long bytes) {}

  static String name(int ordinal) {
    return switch (ordinal) {
      case 0 -> "Authored sword α";
      case 1 -> "";
      case 4 -> "Authored Sword";
      case 5 -> "Authored Café";
      case 6 -> "Authored Cafe\u0301";
      case 7 -> "Authored 🗡";
      case 8 -> "Authored 100%_literal \\path O'Brien";
      default -> "Authored candidate " + ordinal + " group-" + (ordinal % 97);
    };
  }

  static int nameId(int ordinal) {
    // Item IDs1003/1004 intentionally lack physical names; one is a declared Sparse copy.
    return FIRST + ordinal + (ordinal >= 2 ? 2 : 0);
  }

  static Source generate(Path dir, boolean sparse) throws Exception {
    Path decoded = dir.resolve(sparse ? "sparse.decoded.scratch" : "item.decoded.scratch");
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(decoded))) {
      if (sparse) sparse(out);
      else item(out);
    }
    Path encoded = dir.resolve(sparse ? "sparse.blte" : "item.blte");
    Source source = encode(decoded, encoded);
    Files.delete(decoded);
    return source;
  }

  private static ByteBuffer header(
      int count,
      int fields,
      int recordSize,
      int table,
      int layout,
      int flags,
      int storage,
      int offset) {
    var b = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[] {'W', 'D', 'C', '5'});
    b.putInt(4, 5);
    b.putInt(136, count);
    b.putInt(140, fields);
    b.putInt(144, recordSize);
    b.putInt(152, table);
    b.putInt(156, layout);
    b.putInt(160, FIRST);
    b.putInt(164, FIRST + count + 2);
    b.putShort(172, (short) flags);
    b.putInt(176, fields);
    b.putInt(188, storage);
    b.putInt(200, 1);
    b.putInt(212, offset);
    b.putInt(216, count);
    b.putInt(228, count * 4);
    b.putInt(240, 1);
    return b;
  }

  private static void item(OutputStream out) throws IOException {
    var b = header(ITEMS, 15, 11, 0x50238ec2, 0x996192aa, 4, 360, 664);
    b.putShort(174, (short) 0xffff);
    for (int i = 0; i < 15; i++) {
      int at = 304 + 24 * i;
      b.putShort(at + 2, (short) 8);
      b.putInt(at + 8, 1);
      b.putInt(at + 16, 8);
    }
    out.write(b.array());
    for (int i = 0; i < ITEMS; i++) {
      byte[] row = new byte[11];
      row[0] = (byte) (i % 127);
      out.write(row);
    }
    var ids = ByteBuffer.allocate(ITEMS * 4 + 8).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < ITEMS; i++) ids.putInt(FIRST + i);
    ids.putInt(900001).putInt(FIRST);
    out.write(ids.array());
  }

  private static void sparse(OutputStream out) throws IOException {
    int[] lengths = new int[NAMES];
    int base = AREA / NAMES, extra = AREA % NAMES;
    for (int i = 0; i < NAMES; i++) lengths[i] = base + (i < extra ? 1 : 0);
    var b = header(NAMES, 67, 328, 0x919be54e, 0x1c17d17f, 5, 1608, 4096);
    b.putInt(180, 328);
    b.putInt(224, 4096 + AREA);
    b.putInt(236, NAMES);
    out.write(b.array());
    for (int i = 0; i < NAMES; i++) {
      byte[] prefix = ("Authored note\0\0\0\0" + name(i) + "\0").getBytes(StandardCharsets.UTF_8);
      if (prefix.length > lengths[i]) throw new AssertionError("Prefix exceeds record");
      out.write(prefix);
      out.write(new byte[lengths[i] - prefix.length]);
    }
    var suffix = ByteBuffer.allocate(NAMES * 14 + 8).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < NAMES; i++) suffix.putInt(nameId(i));
    suffix.putInt(1003).putInt(FIRST);
    int at = 4096;
    for (int length : lengths) {
      suffix.putInt(at).putShort((short) length);
      at += length;
    }
    for (int i = 0; i < NAMES; i++) suffix.putInt(nameId(i));
    out.write(suffix.array());
  }

  private static Source encode(Path decoded, Path target) throws Exception {
    long size = Files.size(decoded);
    int chunk = 256 * 1024, count = (int) ((size + chunk - 1) / chunk), header = 12 + 24 * count;
    var h = ByteBuffer.allocate(header).order(ByteOrder.BIG_ENDIAN);
    h.put(new byte[] {'B', 'L', 'T', 'E'}).putInt(header);
    h.put((byte) 15).put((byte) (count >>> 16)).put((byte) (count >>> 8)).put((byte) count);
    Path body = target.resolveSibling(target.getFileName() + ".body.scratch");
    try (InputStream in = new BufferedInputStream(Files.newInputStream(decoded));
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(body))) {
      for (int i = 0; i < count; i++) {
        byte[] raw = in.readNBytes(chunk);
        var compressed = new ByteArrayOutputStream();
        compressed.write('Z');
        try (var z = new DeflaterOutputStream(compressed)) {
          z.write(raw);
        }
        byte[] encoded = compressed.toByteArray();
        h.putInt(encoded.length).putInt(raw.length).put(digest("MD5", encoded));
        out.write(encoded);
      }
      if (in.read() != -1) throw new AssertionError("Decoded trailing data");
    }
    var sha = MessageDigest.getInstance("SHA-256");
    try (OutputStream out = new DigestOutputStream(Files.newOutputStream(target), sha);
        InputStream bodyIn = Files.newInputStream(body)) {
      out.write(h.array());
      bodyIn.transferTo(out);
    }
    Files.delete(body);
    return new Source(
        target,
        size,
        HexFormat.of().formatHex(digest("MD5", h.array())),
        HexFormat.of().formatHex(sha.digest()),
        Files.size(target));
  }

  static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
