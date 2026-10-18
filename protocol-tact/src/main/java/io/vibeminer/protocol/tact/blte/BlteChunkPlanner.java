package io.vibeminer.protocol.tact.blte;

import static io.vibeminer.protocol.tact.blte.BlteException.Reason.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Authenticates a complete nonzero BLTE header before exposing any selected-chunk recipe. Expected
 * EKey and whole encoded/decoded lengths must come from an independently pinned source. Inputs must
 * remain unchanged during calls. This API never establishes a complete decoded CKey.
 */
public final class BlteChunkPlanner {
    public record Limits(
            int maxHeaderBytes,
            int maxChunks,
            long maxWholeEncodedBytes,
            long maxWholeDecodedBytes,
            int maxChunkEncodedBytes,
            int maxChunkDecodedBytes) {
        public Limits {
            if (maxHeaderBytes < 12
                    || maxChunks < 1
                    || maxWholeEncodedBytes < 13
                    || maxWholeDecodedBytes < 0
                    || maxChunkEncodedBytes < 1
                    || maxChunkDecodedBytes < 0)
                throw new IllegalArgumentException("Invalid authenticated chunk-plan bounds");
        }
    }

    public enum VerificationScope {
        AUTHENTICATED_CHUNK_ONLY
    }

    /** Created only after complete header authentication and checked totals. */
    public static final class AuthenticatedPlan {
        private final byte[] encodingKey;
        private final long wholeEncodedBytes, wholeDecodedBytes;
        private final int headerBytes;
        private final List<Chunk> chunks;

        private AuthenticatedPlan(
                byte[] key,
                long encoded,
                long decoded,
                int header,
                List<ChunkSpec> specifications,
                BooleanSupplier cancelled)
                throws BlteException {
            encodingKey = key.clone();
            wholeEncodedBytes = encoded;
            wholeDecodedBytes = decoded;
            headerBytes = header;
            var descriptors = new ArrayList<Chunk>(specifications.size());
            for (int i = 0; i < specifications.size(); i++) {
                checkCancelled(cancelled);
                descriptors.add(new Chunk(this, i, specifications.get(i)));
            }
            chunks = List.copyOf(descriptors);
        }

        public byte[] encodingKey() {
            return encodingKey.clone();
        }

        public long wholeEncodedBytes() {
            return wholeEncodedBytes;
        }

        public long wholeDecodedBytes() {
            return wholeDecodedBytes;
        }

        public int headerBytes() {
            return headerBytes;
        }

        public List<Chunk> chunks() {
            return chunks;
        }
    }

    /**
     * Descriptor belongs to one authenticated plan; neither constructor nor mutable hashes are
     * exposed.
     */
    public static final class Chunk {
        private final AuthenticatedPlan owner;
        private final int index, encodedBytes, decodedBytes;
        private final long encodedOffset, decodedOffset;
        private final byte[] encodedMd5;

        private Chunk(AuthenticatedPlan owner, int index, ChunkSpec spec) {
            this.owner = owner;
            this.index = index;
            encodedOffset = spec.encodedOffset();
            decodedOffset = spec.decodedOffset();
            encodedBytes = spec.encodedBytes();
            decodedBytes = spec.decodedBytes();
            encodedMd5 = spec.hash().clone();
        }

        public int index() {
            return index;
        }

        public long encodedOffset() {
            return encodedOffset;
        }

        public long decodedOffset() {
            return decodedOffset;
        }

        public int encodedBytes() {
            return encodedBytes;
        }

        public int decodedBytes() {
            return decodedBytes;
        }

        public byte[] encodedMd5() {
            return encodedMd5.clone();
        }
    }

    /**
     * Verified selected decoded bytes only: this is neither whole-file readiness nor a CKey proof.
     */
    public static final class VerifiedChunk {
        private final Chunk chunk;
        private final byte[] decoded;

        private VerifiedChunk(Chunk chunk, byte[] ownedDecoded) {
            this.chunk = chunk;
            decoded = ownedDecoded;
        }

        public Chunk chunk() {
            return chunk;
        }

        public byte[] decodedBytes() {
            return decoded.clone();
        }

        public VerificationScope scope() {
            return VerificationScope.AUTHENTICATED_CHUNK_ONLY;
        }
    }

    private record ChunkSpec(
            long encodedOffset,
            long decodedOffset,
            int encodedBytes,
            int decodedBytes,
            byte[] hash) {}

    public AuthenticatedPlan authenticate(
            byte[] header,
            byte[] expectedEncodingKey,
            long expectedEncodedBytes,
            long expectedDecodedBytes,
            Limits limits,
            BooleanSupplier cancelled)
            throws BlteException {
        Objects.requireNonNull(header);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if (expectedEncodingKey == null || expectedEncodingKey.length != 16)
            throw new IllegalArgumentException(
                    "Authenticated header requires a mandatory 16-byte EKey");
        if (expectedEncodedBytes < 9 || expectedDecodedBytes < 0)
            throw new IllegalArgumentException("Invalid pinned whole-object lengths");
        if (header.length > limits.maxHeaderBytes()
                || expectedEncodedBytes > limits.maxWholeEncodedBytes()
                || expectedDecodedBytes > limits.maxWholeDecodedBytes())
            throw error(LIMIT, "BLTE header/whole-object budget exceeded");
        if (header.length < 8
                || header[0] != 'B'
                || header[1] != 'L'
                || header[2] != 'T'
                || header[3] != 'E') throw error(MALFORMED, "Missing complete BLTE header prefix");
        long declaredHeader = u32(header, 4);
        if (declaredHeader == 0)
            throw error(
                    UNSUPPORTED,
                    "Zero-header BLTE requires whole-object verification; no authenticated partial"
                            + " plan");
        if (declaredHeader < 12
                || declaredHeader != header.length
                || declaredHeader >= expectedEncodedBytes)
            throw error(MALFORMED, "Complete header length does not match pinned BLTE object");
        BlteDecoder.verifyHash(header, 0, header.length, expectedEncodingKey, cancelled);
        if (header[8] != 15)
            throw error(UNSUPPORTED, "Unsupported authenticated BLTE table format");
        int count = ((header[9] & 255) << 16) | ((header[10] & 255) << 8) | (header[11] & 255);
        if (count == 0) throw error(MALFORMED, "Authenticated chunk table is empty");
        if (count > limits.maxChunks())
            throw error(LIMIT, "Authenticated chunk count exceeds budget");
        if (12L + 24L * count != declaredHeader)
            throw error(MALFORMED, "Chunk count does not match complete header");
        var specifications = new ArrayList<ChunkSpec>(count);
        long encodedOffset = declaredHeader, decodedOffset = 0;
        for (int i = 0; i < count; i++) {
            checkCancelled(cancelled);
            int at = 12 + 24 * i;
            long encodedSize = u32(header, at), decodedSize = u32(header, at + 4);
            if (encodedSize < 1) throw error(MALFORMED, "Authenticated chunk lacks a mode byte");
            if (encodedSize > limits.maxChunkEncodedBytes()
                    || decodedSize > limits.maxChunkDecodedBytes())
                throw error(LIMIT, "Selected chunk size exceeds decoder budget");
            specifications.add(
                    new ChunkSpec(
                            encodedOffset,
                            decodedOffset,
                            (int) encodedSize,
                            (int) decodedSize,
                            Arrays.copyOfRange(header, at + 8, at + 24)));
            encodedOffset = checkedAdd(encodedOffset, encodedSize);
            decodedOffset = checkedAdd(decodedOffset, decodedSize);
            if (encodedOffset > expectedEncodedBytes || decodedOffset > expectedDecodedBytes)
                throw error(
                        MALFORMED, "Authenticated chunk totals exceed pinned whole-object lengths");
        }
        if (encodedOffset != expectedEncodedBytes || decodedOffset != expectedDecodedBytes)
            throw error(
                    MALFORMED,
                    "Authenticated chunk totals differ from pinned whole-object lengths");
        checkCancelled(cancelled);
        var plan =
                new AuthenticatedPlan(
                        expectedEncodingKey,
                        expectedEncodedBytes,
                        expectedDecodedBytes,
                        header.length,
                        specifications,
                        cancelled);
        checkCancelled(cancelled);
        return plan;
    }

    public VerifiedChunk decodeSelected(
            AuthenticatedPlan plan, Chunk descriptor, byte[] encoded, BooleanSupplier cancelled)
            throws BlteException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(encoded);
        Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if (descriptor.owner != plan)
            throw new IllegalArgumentException(
                    "Chunk descriptor does not belong to this authenticated plan");
        if (encoded.length != descriptor.encodedBytes)
            throw error(MALFORMED, "Selected encoded chunk length mismatch");
        BlteDecoder.verifyHash(encoded, 0, encoded.length, descriptor.encodedMd5, cancelled);
        byte[] decoded = new byte[descriptor.decodedBytes];
        BlteDecoder.decodeBlock(encoded, 0, encoded.length, decoded, 0, decoded.length, cancelled);
        checkCancelled(cancelled);
        return new VerifiedChunk(descriptor, decoded);
    }

    private static long checkedAdd(long a, long b) throws BlteException {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            throw error(MALFORMED, "Authenticated BLTE size arithmetic overflow");
        }
    }

    private static long u32(byte[] data, int at) {
        return ((long) (data[at] & 255) << 24)
                | ((long) (data[at + 1] & 255) << 16)
                | ((long) (data[at + 2] & 255) << 8)
                | (data[at + 3] & 255);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws BlteException {
        if (cancelled.getAsBoolean())
            throw error(CANCELLED, "Authenticated BLTE chunk operation cancelled");
    }

    private static BlteException error(BlteException.Reason reason, String message) {
        return new BlteException(reason, message);
    }
}
