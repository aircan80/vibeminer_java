package io.vibeminer.infrastructure.item;

import io.vibeminer.domain.*;
import java.nio.*;
import java.security.*;
import java.util.*;

final class ItemSourceFixture {
  static byte[] decoded() {
    byte[] a = new byte[702];
    a[0] = 'W';
    a[1] = 'D';
    a[2] = 'C';
    a[3] = '5';
    var b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(4, 5);
    b.putInt(136, 2);
    b.putInt(140, 15);
    b.putInt(144, 11);
    b.putInt(152, 0x50238ec2);
    b.putInt(156, 0x996192aa);
    b.putInt(160, 101);
    b.putInt(164, 202);
    b.putShort(172, (short) 4);
    b.putShort(174, (short) 0xffff);
    b.putInt(176, 15);
    b.putInt(188, 360);
    b.putInt(200, 1);
    b.putInt(212, 664);
    b.putInt(216, 2);
    b.putInt(228, 8);
    b.putInt(240, 1);
    for (int i = 0; i < 15; i++) {
      int at = 304 + 24 * i;
      b.putShort(at + 2, (short) 8);
      b.putInt(at + 8, 1);
      b.putInt(at + 16, 8);
    }
    a[664] = 3;
    a[675] = 4;
    b.putInt(686, 101);
    b.putInt(690, 202);
    b.putInt(694, 303);
    b.putInt(698, 101);
    return a;
  }

  static byte[] encoded() {
    return encode(decoded());
  }

  static byte[] encode(byte[] decoded) {
    byte[] a = new byte[37 + decoded.length];
    a[0] = 'B';
    a[1] = 'L';
    a[2] = 'T';
    a[3] = 'E';
    var b = ByteBuffer.wrap(a).order(ByteOrder.BIG_ENDIAN);
    b.putInt(4, 36);
    a[8] = 15;
    a[11] = 1;
    b.putInt(12, decoded.length + 1);
    b.putInt(16, decoded.length);
    a[36] = 'N';
    System.arraycopy(decoded, 0, a, 37, decoded.length);
    System.arraycopy(digest("MD5", Arrays.copyOfRange(a, 36, a.length)), 0, a, 20, 16);
    return a;
  }

  static byte[] digest(String algo, byte[] a) {
    try {
      return MessageDigest.getInstance(algo).digest(a);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static String key() {
    return HexFormat.of().formatHex(digest("MD5", Arrays.copyOf(encoded(), 36)));
  }

  static String sha() {
    return HexFormat.of().formatHex(digest("SHA-256", encoded()));
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
            841626,
            "1df6b6dbae36878cea020a62b15b9bd6",
            1,
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

  static io.vibeminer.domain.selectedfile.SelectedFileAssociation association(
      String version, java.time.Instant observed) {
    return association(version, observed, encoded(), decoded().length);
  }
}
