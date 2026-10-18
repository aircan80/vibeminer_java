package io.vibeminer.infrastructure.itemnames;

import io.vibeminer.domain.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class SparseNameSourceFixture {
  static byte[] record1() {
    return "desc\0three\0two\0one\0Authored α\0tail".getBytes(StandardCharsets.UTF_8);
  }

  static byte[] record2() {
    return new byte[] {0, 0, 0, 0, 0, (byte) 0xff};
  }

  static byte[] decoded() {
    int area = record1().length + record2().length, end = 4096 + area;
    byte[] a = new byte[end + 36];
    a[0] = 'W';
    a[1] = 'D';
    a[2] = 'C';
    a[3] = '5';
    var b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(4, 5);
    b.putInt(136, 2);
    b.putInt(140, 67);
    b.putInt(144, 328);
    b.putInt(152, 0x919be54e);
    b.putInt(156, 0x1c17d17f);
    b.putInt(160, 101);
    b.putInt(164, 202);
    b.putShort(172, (short) 5);
    b.putInt(176, 67);
    b.putInt(180, 328);
    b.putInt(188, 1608);
    b.putInt(200, 1);
    b.putInt(212, 4096);
    b.putInt(216, 2);
    b.putInt(224, end);
    b.putInt(228, 8);
    b.putInt(236, 2);
    b.putInt(240, 1);
    System.arraycopy(record1(), 0, a, 4096, record1().length);
    System.arraycopy(record2(), 0, a, 4096 + record1().length, record2().length);
    b.position(end);
    b.putInt(101)
        .putInt(202)
        .putInt(303)
        .putInt(101)
        .putInt(4096)
        .putShort((short) record1().length)
        .putInt(4096 + record1().length)
        .putShort((short) record2().length)
        .putInt(101)
        .putInt(202);
    return a;
  }

  static byte[] encoded() {
    return encode(decoded());
  }

  static byte[] encode(byte[] decoded) {
    int recordEnd = decoded.length - 36;
    int[] cuts = {0, 4096, 4096 + record1().length / 2, recordEnd, decoded.length};
    int header = 12 + 24 * 4;
    byte[] a = new byte[header + decoded.length + 4];
    a[0] = 'B';
    a[1] = 'L';
    a[2] = 'T';
    a[3] = 'E';
    var b = ByteBuffer.wrap(a).order(ByteOrder.BIG_ENDIAN);
    b.putInt(4, header);
    a[8] = 15;
    a[11] = 4;
    int at = header;
    for (int n = 0; n < 4; n++) {
      int len = cuts[n + 1] - cuts[n];
      b.putInt(12 + n * 24, len + 1);
      b.putInt(16 + n * 24, len);
      a[at] = 'N';
      System.arraycopy(decoded, cuts[n], a, at + 1, len);
      System.arraycopy(
          digest("MD5", Arrays.copyOfRange(a, at, at + len + 1)), 0, a, 20 + n * 24, 16);
      at += len + 1;
    }
    return a;
  }

  static byte[] digest(String algo, byte[] a) {
    try {
      return MessageDigest.getInstance(algo).digest(a);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static io.vibeminer.domain.selectedfile.SelectedFileAssociation association(
      String version, java.time.Instant observed, byte[] sourceBytes, long decodedLength) {
    var build =
        new BuildSnapshot(
            "wow",
            "Fixture",
            "current",
            "us",
            version,
            "c".repeat(32),
            "d".repeat(32),
            observed,
            true);
    var configSource = new ResolvedSourceIdentity("config", "c".repeat(32), "v1");
    var rootSource = new ResolvedSourceIdentity("root", "root", "v1");
    var config =
        new VerifiedSourceAlias(
            configSource,
            new StoredObject(new ObjectDigest("1".repeat(64)), 10),
            new SourceVerificationEvidence("v1", "https://us.cdn.blizzard.com/config", observed));
    var root =
        new VerifiedSourceAlias(
            rootSource,
            new StoredObject(new ObjectDigest("2".repeat(64)), 20),
            new SourceVerificationEvidence("v1", "https://us.cdn.blizzard.com/root", observed));
    var variant =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            build,
            rootSource,
            1572924,
            "bb0ecbdc2fd4988ddd3c1c5e95c34165",
            0x202,
            2,
            3,
            4,
            5,
            Optional.empty());
    var descriptor =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            variant,
            HexFormat.of()
                .formatHex(
                    digest(
                        "MD5",
                        Arrays.copyOf(
                            sourceBytes,
                            ByteBuffer.wrap(sourceBytes).order(ByteOrder.BIG_ENDIAN).getInt(4) == 0
                                ? sourceBytes.length
                                : ByteBuffer.wrap(sourceBytes)
                                    .order(ByteOrder.BIG_ENDIAN)
                                    .getInt(4)))),
            io.vibeminer.domain.selectedfile.ContentRepresentation.VERIFIER_SEMANTICS);
    var source =
        io.vibeminer.domain.selectedfile.ContentRepresentation.encodedIdentity(
            HexFormat.of()
                .formatHex(
                    digest(
                        "MD5",
                        Arrays.copyOf(
                            sourceBytes,
                            ByteBuffer.wrap(sourceBytes).order(ByteOrder.BIG_ENDIAN).getInt(4) == 0
                                ? sourceBytes.length
                                : ByteBuffer.wrap(sourceBytes)
                                    .order(ByteOrder.BIG_ENDIAN)
                                    .getInt(4)))));
    var evidence =
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/encoded", observed);
    var encoded =
        new VerifiedSourceAlias(
            source,
            new StoredObject(
                new ObjectDigest(HexFormat.of().formatHex(digest("SHA-256", sourceBytes))),
                sourceBytes.length),
            evidence);
    var receipt =
        new io.vibeminer.domain.selectedfile.EncodedSourceReceipt(
            io.vibeminer.domain.selectedfile.EncodedSourceReceipt.Kind.LOOSE,
            evidence.sourceUrl(),
            HexFormat.of()
                .formatHex(
                    digest(
                        "MD5",
                        Arrays.copyOf(
                            sourceBytes,
                            ByteBuffer.wrap(sourceBytes).order(ByteOrder.BIG_ENDIAN).getInt(4) == 0
                                ? sourceBytes.length
                                : ByteBuffer.wrap(sourceBytes)
                                    .order(ByteOrder.BIG_ENDIAN)
                                    .getInt(4)))),
            sourceBytes.length,
            0,
            sourceBytes.length,
            observed,
            1,
            sourceBytes.length,
            "3".repeat(64),
            Optional.empty());
    var representation =
        new io.vibeminer.domain.selectedfile.ContentRepresentation(
            variant.slot(),
            encoded,
            io.vibeminer.domain.selectedfile.EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS,
            receipt);
    var proof =
        new io.vibeminer.domain.selectedfile.FileAssociationProof(
            rootSource,
            new ResolvedSourceIdentity("encoding", "encoding", "v1"),
            "association-v2",
            "4".repeat(64),
            java.util.OptionalLong.of(decodedLength),
            List.of(
                new io.vibeminer.domain.selectedfile.EncodedCandidate(
                    HexFormat.of()
                        .formatHex(
                            digest(
                                "MD5",
                                Arrays.copyOf(
                                    sourceBytes,
                                    ByteBuffer.wrap(sourceBytes)
                                                .order(ByteOrder.BIG_ENDIAN)
                                                .getInt(4)
                                            == 0
                                        ? sourceBytes.length
                                        : ByteBuffer.wrap(sourceBytes)
                                            .order(ByteOrder.BIG_ENDIAN)
                                            .getInt(4)))),
                    java.util.OptionalLong.of(sourceBytes.length),
                    java.util.OptionalLong.empty())));
    return new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
        descriptor, config, root, proof, representation);
  }

  static io.vibeminer.domain.selectedfile.SelectedFileAssociation association() {
    return association("12.1.5.70448", java.time.Instant.EPOCH, encoded(), decoded().length);
  }
}
