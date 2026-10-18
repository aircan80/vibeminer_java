package io.vibeminer.infrastructure.lookup;

import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisition.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;

/** Independent synthetic EN/BLTE/TSFM fixture; no real Blizzard bytes or external fixtures. */
final class RootLookupProbeFixture implements LookupTransport {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    final Map<String, byte[]> objects = new HashMap<>();
    final byte[] config, root, encodedRoot;
    final String rootCKey, rootEKey, encodingEKey;
    String secondRootEKey;
    final List<CdnRangeRequest> requests = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger configurationRequests = new AtomicInteger();
    volatile boolean rejectNetwork, wrongRangeIdentity, corruptEncodingPage, corruptRootChunk;
    volatile CompletableFuture<AcquiredRange> blockedRoot;
    volatile CdnRangeRequest blockedRequest;

    RootLookupProbeFixture() throws Exception {
        this(false, false);
    }

    RootLookupProbeFixture(boolean wrongRootContentKey, boolean malformedRoot) throws Exception {
        this(wrongRootContentKey, malformedRoot, false, false);
    }

    RootLookupProbeFixture(
            boolean wrongRootContentKey,
            boolean malformedRoot,
            boolean multipleCandidates,
            boolean missingMetadata)
            throws Exception {
        this(wrongRootContentKey, malformedRoot, multipleCandidates, missingMetadata, false);
    }

    RootLookupProbeFixture(
            boolean wrongRootContentKey,
            boolean malformedRoot,
            boolean multipleCandidates,
            boolean missingMetadata,
            boolean largeSpecificationSection)
            throws Exception {
        byte[] decoded = root();
        if (malformedRoot)
            ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 12345);
        root = decoded;
        String actual = hash("MD5", root);
        rootCKey =
                wrongRootContentKey
                        ? (actual.charAt(0) == 'a' ? "b" : "a") + actual.substring(1)
                        : actual;
        encodedRoot =
                blte(Arrays.copyOfRange(root, 0, 40), Arrays.copyOfRange(root, 40, root.length));
        rootEKey = ekey(encodedRoot);
        objects.put(rootEKey, encodedRoot);
        if (multipleCandidates) {
            byte[] second =
                    blte(
                            Arrays.copyOfRange(root, 0, 50),
                            Arrays.copyOfRange(root, 50, root.length));
            secondRootEKey = ekey(second);
            objects.put(secondRootEKey, second);
        }
        byte[] cp = new byte[1024], ep = new byte[1024];
        var content = ByteBuffer.wrap(cp).order(ByteOrder.BIG_ENDIAN);
        content.put((byte) (multipleCandidates ? 2 : 1));
        u40(content, root.length);
        content.put(HexFormat.of().parseHex(rootCKey)).put(HexFormat.of().parseHex(rootEKey));
        if (multipleCandidates) content.put(HexFormat.of().parseHex(secondRootEKey));
        var encoding = ByteBuffer.wrap(ep).order(ByteOrder.BIG_ENDIAN);
        var encodedKeys = new ArrayList<String>();
        encodedKeys.add(rootEKey);
        if (multipleCandidates && !missingMetadata) encodedKeys.add(secondRootEKey);
        encodedKeys.sort(String::compareTo);
        for (String key : encodedKeys) {
            encoding.put(HexFormat.of().parseHex(key)).putInt(key.equals(rootEKey) ? 0 : 1);
            u40(encoding, objects.get(key).length);
        }
        byte[] ci = concat(HexFormat.of().parseHex(rootCKey), md5(cp));
        byte[] ei = concat(HexFormat.of().parseHex(encodedKeys.getFirst()), md5(ep));
        var header = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
        byte[] specification = largeSpecificationSection ? new byte[16 << 20] : new byte[0];
        header.put(new byte[] {'E', 'N', 1, 16, 16})
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(1)
                .putInt(1)
                .put((byte) 0)
                .putInt(specification.length);
        byte[] enDecoded = concat(header.array(), specification, ci, cp, ei, ep);
        byte[] enEncoded =
                largeSpecificationSection
                        ? blte(header.array(), specification, ci, cp, ei, ep)
                        : blte(header.array(), ci, cp, ei, ep);
        encodingEKey = ekey(enEncoded);
        objects.put(encodingEKey, enEncoded);
        config =
                ("root = "
                                + rootCKey
                                + "\nencoding = "
                                + hash("MD5", enDecoded)
                                + " "
                                + encodingEKey
                                + "\nencoding-size = "
                                + enDecoded.length
                                + " "
                                + enEncoded.length
                                + "\n")
                        .getBytes(StandardCharsets.UTF_8);
    }

    BuildSnapshot build(String host, String version) throws Exception {
        String buildKey = hash("MD5", config), cdnKey = "d".repeat(32);
        String versions =
                "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
                        + buildKey
                        + "|"
                        + cdnKey
                        + "|"
                        + version
                        + "\n";
        String cdns =
                "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
                        + "us|tpr/wow|"
                        + host
                        + "|https://"
                        + host
                        + "/|tpr/configs/data\n";
        return new BuildSnapshot(
                "wow",
                "Synthetic",
                "Published",
                "us",
                version,
                buildKey,
                cdnKey,
                NOW,
                false,
                new BuildEvidence(
                        "",
                        "",
                        List.of(
                                new SourceDocument(
                                        "https://us.version.battle.net/v2/products/wow/versions",
                                        NOW,
                                        versions.getBytes(StandardCharsets.UTF_8)),
                                new SourceDocument(
                                        "https://us.version.battle.net/v2/products/wow/cdns",
                                        NOW,
                                        cdns.getBytes(StandardCharsets.UTF_8)))));
    }

    public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
        if (rejectNetwork) throw new AssertionError("Offline attempted configuration HTTP");
        configurationRequests.incrementAndGet();
        try {
            return CompletableFuture.completedFuture(
                    new AcquiredBytes(request, NOW, config, hash("SHA-256", config), true));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
        if (rejectNetwork) throw new AssertionError("Offline attempted range HTTP");
        requests.add(request);
        try {
            if (blockedRoot != null
                    && blockedRequest == null
                    && request.encodedKey().equals(rootEKey)
                    && request.start() >= 60) {
                blockedRequest = request;
                return blockedRoot;
            }
            byte[] source =
                    Objects.requireNonNull(objects.get(request.encodedKey()), "Unexpected object");
            if (request.wholeEncodedLength() != source.length)
                throw new AssertionError("Wrong whole length");
            byte[] bytes =
                    Arrays.copyOfRange(
                            source,
                            Math.toIntExact(request.start()),
                            Math.toIntExact(request.start() + request.count()));
            if (corruptRootChunk && request.encodedKey().equals(rootEKey) && request.start() >= 60)
                bytes[bytes.length - 1] ^= 1;
            if (corruptEncodingPage
                    && request.encodedKey().equals(encodingEKey)
                    && request.count() == 1024) bytes[bytes.length - 1] ^= 1;
            var actual =
                    wrongRangeIdentity
                            ? new CdnRangeRequest(
                                    request.source(),
                                    "f".repeat(32),
                                    request.wholeEncodedLength(),
                                    request.start(),
                                    request.count(),
                                    request.maximumBytes())
                            : request;
            return CompletableFuture.completedFuture(
                    new AcquiredRange(actual, actual.uri(), NOW, bytes, hash("SHA-256", bytes)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    static String hash(String algorithm, byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));
    }

    static byte[] md5(byte[] value) throws Exception {
        return MessageDigest.getInstance("MD5").digest(value);
    }

    static String ekey(byte[] encoded) throws Exception {
        return hash("MD5", Arrays.copyOf(encoded, ByteBuffer.wrap(encoded).getInt(4)));
    }

    static byte[] concat(byte[]... parts) throws IOException {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part);
        return out.toByteArray();
    }

    static void u40(ByteBuffer out, long value) {
        out.put((byte) (value >>> 32)).putInt((int) value);
    }

    static byte[] blte(byte[]... decoded) throws Exception {
        var header = new ByteArrayOutputStream();
        var out = new DataOutputStream(header);
        out.writeBytes("BLTE");
        out.writeInt(12 + 24 * decoded.length);
        out.writeInt(0x0f000000 | decoded.length);
        var body = new ByteArrayOutputStream();
        for (int i = 0; i < decoded.length; i++) {
            byte[] encoded;
            if (i % 2 == 0) encoded = concat(new byte[] {'N'}, decoded[i]);
            else {
                var zipped = new ByteArrayOutputStream();
                zipped.write('Z');
                try (var zip = new DeflaterOutputStream(zipped)) {
                    zip.write(decoded[i]);
                }
                encoded = zipped.toByteArray();
            }
            out.writeInt(encoded.length);
            out.writeInt(decoded[i].length);
            out.write(md5(encoded));
            body.write(encoded);
        }
        return concat(header.toByteArray(), body.toByteArray());
    }

    static byte[] root() {
        var out =
                ByteBuffer.allocate(24 + 17 + 3 * 28 + 17 + 2 * 20).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {'T', 'S', 'F', 'M'}).putInt(24).putInt(2).putInt(5).putInt(3).putInt(0);
        out.putInt(3).putInt(2).putInt(0x20000000).putInt(4).put((byte) 0);
        out.putInt(7).putInt(34).putInt(56);
        for (int i = 0; i < 3; i++)
            out.put(HexFormat.of().parseHex(Integer.toHexString(i + 1).repeat(32)));
        out.putLong(-1L).putLong(0).putLong(0x123);
        out.putInt(2).putInt(0x10).putInt(0x10000000).putInt(8).put((byte) 0x80);
        out.putInt(42).putInt(80);
        out.put(HexFormat.of().parseHex("4".repeat(32)))
                .put(HexFormat.of().parseHex("5".repeat(32)));
        return out.array();
    }
}
