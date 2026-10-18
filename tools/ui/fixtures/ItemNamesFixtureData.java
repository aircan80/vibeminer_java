package io.vibeminer.infrastructure.item;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Portable authored Sparse data only; no game payloads, test helpers or private resources. */
final class ItemNamesFixtureData {
  static final long COPY_ID = 1003, MISSING_ID = 1004, SPARSE_ONLY_ID = 700001;

  static List<Long> physicalIds() {
    var result = new ArrayList<Long>(List.of(1001L, 1002L));
    for (long id = 1005; id <= 1061; id++) if (id % 3 == 0) result.add(id);
    result.add(SPARSE_ONLY_ID);
    return List.copyOf(result);
  }

  static byte[] decoded() {
    var ids = physicalIds();
    var records = new ArrayList<byte[]>();
    int area = 0;
    for (long id : ids) {
      String display =
          id == 1002
              ? ""
              : id == 1001
                  ? "Authored sword α"
                  : id == SPARSE_ONLY_ID ? "Authored Sparse-only" : "Authored item " + id;
      byte[] row = ("Authored note\0\0\0\0" + display + "\0tail").getBytes(StandardCharsets.UTF_8);
      records.add(row);
      area += row.length;
    }
    int end = 4096 + area;
    byte[] data = new byte[end + ids.size() * 14 + 8];
    data[0] = 'W';
    data[1] = 'D';
    data[2] = 'C';
    data[3] = '5';
    var b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(4, 5);
    b.putInt(136, ids.size());
    b.putInt(140, 67);
    b.putInt(144, 328);
    b.putInt(152, 0x919be54e);
    b.putInt(156, 0x1c17d17f);
    b.putInt(160, ids.getFirst().intValue());
    b.putInt(164, ids.getLast().intValue());
    b.putShort(172, (short) 5);
    b.putInt(176, 67);
    b.putInt(180, 328);
    b.putInt(188, 1608);
    b.putInt(200, 1);
    b.putInt(212, 4096);
    b.putInt(216, ids.size());
    b.putInt(224, end);
    b.putInt(228, ids.size() * 4);
    b.putInt(236, ids.size());
    b.putInt(240, 1);
    int at = 4096;
    for (var row : records) {
      System.arraycopy(row, 0, data, at, row.length);
      at += row.length;
    }
    b.position(end);
    for (long id : ids) b.putInt((int) id);
    b.putInt((int) COPY_ID).putInt(1001);
    at = 4096;
    for (var row : records) {
      b.putInt(at).putShort((short) row.length);
      at += row.length;
    }
    for (long id : ids) b.putInt((int) id);
    return data;
  }

  static byte[] encoded() {
    byte[] decoded = decoded();
    int count = (decoded.length + 255) / 256, header = 12 + 24 * count;
    byte[] data = new byte[header + decoded.length + count];
    data[0] = 'B';
    data[1] = 'L';
    data[2] = 'T';
    data[3] = 'E';
    var b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    b.putInt(4, header);
    data[8] = 15;
    data[9] = (byte) (count >>> 16);
    data[10] = (byte) (count >>> 8);
    data[11] = (byte) count;
    int out = header;
    for (int n = 0; n < count; n++) {
      int start = n * 256, length = Math.min(256, decoded.length - start);
      b.putInt(12 + n * 24, length + 1);
      b.putInt(16 + n * 24, length);
      data[out] = 'N';
      System.arraycopy(decoded, start, data, out + 1, length);
      System.arraycopy(
          digest("MD5", Arrays.copyOfRange(data, out, out + length + 1)), 0, data, 20 + n * 24, 16);
      out += length + 1;
    }
    return data;
  }

  static byte[] digest(String algorithm, byte[] data) {
    try {
      return MessageDigest.getInstance(algorithm).digest(data);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String hash(String algorithm, byte[] data) {
    return HexFormat.of().formatHex(digest(algorithm, data));
  }
}
