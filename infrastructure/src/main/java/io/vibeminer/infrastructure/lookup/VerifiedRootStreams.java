package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.lookup.FileLookupException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.protocol.tact.blte.*;

import java.io.*;
import java.security.*;
import java.util.*;

/**
 * Single-chunk working buffers. Encoded publication and decoded structural reading remain separate
 * passes.
 */
final class VerifiedRootStreams {
    static final class Encoded extends InputStream {
        private final RemoteBlteObject object;
        private final String contentKey;
        private final LookupWork work;
        private final MessageDigest content = md5();
        private byte[] current;
        private int offset, nextChunk;
        private boolean complete, closed;

        Encoded(RemoteBlteObject object, String contentKey, LookupWork work) {
            this.object = object;
            this.contentKey = contentKey;
            this.work = work;
            current = object.header;
        }

        @Override
        public int read(byte[] bytes, int start, int length) {
            Objects.checkFromIndexSize(start, length, bytes.length);
            if (length == 0) return 0;
            work.check();
            if (closed) throw LookupWork.fail(STORAGE_FAILURE, "Root encoded stream is closed");
            while (offset == current.length) {
                current = new byte[0];
                offset = 0;
                if (nextChunk == object.plan.chunks().size()) {
                    if (!complete) {
                        verify(content, contentKey);
                        complete = true;
                    }
                    return -1;
                }
                var chunk = object.chunk(object.plan.chunks().get(nextChunk++));
                content.update(chunk.decoded());
                current = chunk.encoded();
            }
            int count = Math.min(length, current.length - offset);
            System.arraycopy(current, offset, bytes, start, count);
            offset += count;
            return count;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        void verifyComplete(CancellationToken token) {
            token.throwIfCancellationRequested();
            work.check();
            if (!complete)
                throw LookupWork.fail(
                        INTEGRITY_FAILURE, "Full encoded root EOF/CKey gate has not completed");
        }

        @Override
        public void close() {
            closed = true;
            current = new byte[0];
        }
    }

    static final class Decoded extends InputStream {
        private final InputStream encoded;
        private final BlteChunkPlanner.AuthenticatedPlan plan;
        private final LookupWork work;
        private final LookupBudgets budgets;
        private final String contentKey;
        private final MessageDigest content = md5();
        private final BlteChunkPlanner decoder = new BlteChunkPlanner();
        private byte[] current = new byte[0];
        private int offset, nextChunk;
        private boolean complete, closed;

        Decoded(
                InputStream encoded,
                String encodingKey,
                String contentKey,
                long encodedBytes,
                long decodedBytes,
                LookupBudgets budgets,
                LookupWork work) {
            this.encoded = encoded;
            this.contentKey = contentKey;
            this.budgets = budgets;
            this.work = work;
            byte[] prefix = readExactly(encoded, 8, work);
            long size =
                    ((long) (prefix[4] & 255) << 24)
                            | ((long) (prefix[5] & 255) << 16)
                            | ((long) (prefix[6] & 255) << 8)
                            | (prefix[7] & 255);
            if (size == 0)
                throw LookupWork.fail(UNSUPPORTED, "Retained zero-header root is unsupported");
            if (size < 12 || size > budgets.maximumHeaderBytes() || size >= encodedBytes)
                throw LookupWork.fail(INTEGRITY_FAILURE, "Retained root header length is invalid");
            byte[] header = new byte[(int) size];
            System.arraycopy(prefix, 0, header, 0, 8);
            byte[] rest = readExactly(encoded, (int) size - 8, work);
            System.arraycopy(rest, 0, header, 8, rest.length);
            try {
                plan =
                        decoder.authenticate(
                                header,
                                HexFormat.of().parseHex(encodingKey),
                                encodedBytes,
                                decodedBytes,
                                RemoteBlteObject.limits(budgets, false),
                                work::cancelled);
            } catch (BlteException failure) {
                throw LookupWork.failure(failure);
            }
            for (var chunk : plan.chunks()) checkChunk(chunk, budgets);
        }

        @Override
        public int read(byte[] bytes, int start, int length) {
            Objects.checkFromIndexSize(start, length, bytes.length);
            if (length == 0) return 0;
            work.check();
            if (closed) throw LookupWork.fail(STORAGE_FAILURE, "Root decoded stream is closed");
            while (offset == current.length) {
                current = new byte[0];
                offset = 0;
                if (nextChunk == plan.chunks().size()) {
                    if (!complete) {
                        try {
                            if (encoded.read() != -1)
                                throw LookupWork.fail(
                                        INTEGRITY_FAILURE,
                                        "Retained root has trailing encoded bytes");
                        } catch (IOException failure) {
                            throw LookupWork.failure(failure);
                        }
                        verify(content, contentKey);
                        complete = true;
                    }
                    return -1;
                }
                var chunk = plan.chunks().get(nextChunk++);
                work.decode(chunk.decodedBytes());
                byte[] bytesEncoded = readExactly(encoded, chunk.encodedBytes(), work);
                try {
                    current =
                            decoder.decodeSelected(plan, chunk, bytesEncoded, work::cancelled)
                                    .decodedBytes();
                } catch (BlteException failure) {
                    throw LookupWork.failure(failure);
                }
                content.update(current);
            }
            int count = Math.min(length, current.length - offset);
            System.arraycopy(current, offset, bytes, start, count);
            offset += count;
            return count;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        void requireComplete() {
            work.check();
            if (!complete)
                throw LookupWork.fail(
                        INTEGRITY_FAILURE, "Root parser did not consume complete protocol EOF");
        }

        @Override
        public void close() {
            closed = true;
            current = new byte[0];
        }
    }

    static void checkChunk(BlteChunkPlanner.Chunk chunk, LookupBudgets budgets) {
        if (chunk.encodedBytes() > budgets.maximumEncodedChunkBytes()
                || chunk.decodedBytes() > budgets.maximumDecodedChunkBytes())
            throw LookupWork.fail(
                    BUDGET_EXCEEDED, "Root chunk exceeds encoded/decoded buffer bounds");
    }

    static byte[] readExactly(InputStream source, int count, LookupWork work) {
        byte[] bytes = new byte[count];
        int at = 0;
        try {
            while (at < count) {
                work.check();
                int length = source.read(bytes, at, count - at);
                if (length < 0)
                    throw LookupWork.fail(INTEGRITY_FAILURE, "Truncated retained root bytes");
                if (length == 0) {
                    int value = source.read();
                    if (value < 0)
                        throw LookupWork.fail(INTEGRITY_FAILURE, "Truncated retained root bytes");
                    bytes[at++] = (byte) value;
                } else at += length;
            }
        } catch (IOException failure) {
            throw LookupWork.failure(failure);
        }
        return bytes;
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void verify(MessageDigest digest, String expected) {
        if (!MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(expected)))
            throw LookupWork.fail(INTEGRITY_FAILURE, "Complete decoded root CKey mismatch");
    }
}
