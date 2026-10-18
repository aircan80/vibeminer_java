package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.lookup.FileLookupException.Code.*;

import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.tact.blte.*;

import java.util.*;

/** Authenticated chunk descriptors plus bounded operation-local decoded EN chunks. */
final class RemoteBlteObject {
    final byte[] header;
    final BlteChunkPlanner.AuthenticatedPlan plan;
    final String key;
    private final CdnRequest.Source source;
    private final LookupTransport transport;
    private final LookupBudgets budgets;
    private final LookupWork work;
    private final Map<Integer, byte[]> cache = new HashMap<>();
    private final BlteChunkPlanner decoder = new BlteChunkPlanner();

    record ChunkBytes(byte[] encoded, byte[] decoded) {}

    RemoteBlteObject(
            CdnRequest.Source source,
            String key,
            long encoded,
            long decoded,
            LookupTransport transport,
            LookupBudgets budgets,
            LookupWork work,
            boolean metadata) {
        this.source = source;
        this.key = key;
        this.transport = transport;
        this.budgets = budgets;
        this.work = work;
        byte[] prefix = range(encoded, 0, 8);
        long length = u32(prefix, 4);
        if (prefix[0] != 'B' || prefix[1] != 'L' || prefix[2] != 'T' || prefix[3] != 'E')
            throw LookupWork.fail(UNSUPPORTED, "Unsupported encoded source signature");
        if (length == 0)
            throw LookupWork.fail(
                    UNSUPPORTED, "Zero-header source requires separate whole-object support");
        if (length < 12 || length > budgets.maximumHeaderBytes() || length >= encoded)
            throw LookupWork.fail(BUDGET_EXCEEDED, "BLTE header exceeds acquisition bounds");
        header = new byte[(int) length];
        System.arraycopy(prefix, 0, header, 0, 8);
        byte[] rest = range(encoded, 8, (int) length - 8);
        System.arraycopy(rest, 0, header, 8, rest.length);
        try {
            plan =
                    decoder.authenticate(
                            header,
                            HexFormat.of().parseHex(key),
                            encoded,
                            decoded,
                            limits(budgets, metadata),
                            work::cancelled);
        } catch (BlteException failure) {
            throw LookupWork.failure(failure);
        }
    }

    static BlteChunkPlanner.Limits limits(LookupBudgets budgets, boolean metadata) {
        return new BlteChunkPlanner.Limits(
                Math.max(12, budgets.maximumHeaderBytes()),
                budgets.maximumChunks(),
                metadata ? 2L << 30 : budgets.maximumRootEncodedBytes(),
                metadata ? 2L << 30 : budgets.maximumRootDecodedBytes(),
                512 << 20,
                512 << 20);
    }

    byte[] range(long whole, long start, int count) {
        var request = new CdnRangeRequest(source, key, whole, start, count, count);
        work.reserve(count);
        var acquired = work.await(transport.range(request));
        if (!acquired.request().equals(request)
                || !acquired.actualSourceUri().equals(request.uri()))
            throw LookupWork.fail(
                    INTEGRITY_FAILURE, "Range receipt does not match pinned lookup request");
        byte[] bytes = acquired.bytes();
        work.settle(count, bytes.length);
        return bytes;
    }

    ChunkBytes chunk(BlteChunkPlanner.Chunk chunk) {
        checkChunk(chunk);
        work.decode(chunk.decodedBytes());
        byte[] encoded =
                range(plan.wholeEncodedBytes(), chunk.encodedOffset(), chunk.encodedBytes());
        try {
            return new ChunkBytes(
                    encoded,
                    decoder.decodeSelected(plan, chunk, encoded, work::cancelled).decodedBytes());
        } catch (BlteException failure) {
            throw LookupWork.failure(failure);
        }
    }

    void checkChunk(BlteChunkPlanner.Chunk chunk) {
        if (chunk.encodedBytes() > budgets.maximumEncodedChunkBytes()
                || chunk.decodedBytes() > budgets.maximumDecodedChunkBytes())
            throw LookupWork.fail(BUDGET_EXCEEDED, "Complete chunk exceeds per-buffer budget");
    }

    byte[] authenticatedWindow(long start, int count) {
        if (start < 0
                || count < 0
                || start > plan.wholeDecodedBytes() - count
                || count > budgets.maximumIndexBytes())
            throw LookupWork.fail(BUDGET_EXCEEDED, "Authenticated EN window exceeds bounds");
        var bytes = new byte[count];
        int copied = 0;
        for (var chunk : plan.chunks()) {
            long from = Math.max(start, chunk.decodedOffset()),
                    to = Math.min(start + count, chunk.decodedOffset() + chunk.decodedBytes());
            if (from >= to) continue;
            byte[] decoded = cache.get(chunk.index());
            if (decoded == null) {
                decoded = chunk(chunk).decoded();
                cache.put(chunk.index(), decoded);
            }
            int length = (int) (to - from);
            System.arraycopy(
                    decoded,
                    (int) (from - chunk.decodedOffset()),
                    bytes,
                    (int) (from - start),
                    length);
            copied += length;
        }
        if (copied != count)
            throw LookupWork.fail(
                    INTEGRITY_FAILURE, "Authenticated EN window lacks complete coverage");
        return bytes;
    }

    /** Only the selected EN page hash from an authenticated index can authorize these raw bytes. */
    byte[] selectedPage(long start, int count) {
        if (start < 0
                || count < 1
                || count > budgets.maximumPageBytes()
                || start > plan.wholeDecodedBytes() - count)
            throw LookupWork.fail(BUDGET_EXCEEDED, "Selected EN page exceeds bounds");
        for (var chunk : plan.chunks()) {
            if (start < chunk.decodedOffset()
                    || start > chunk.decodedOffset() + chunk.decodedBytes() - count) continue;
            if (chunk.encodedBytes() == (long) chunk.decodedBytes() + 1) {
                byte[] mode = range(plan.wholeEncodedBytes(), chunk.encodedOffset(), 1);
                if (mode[0] != 'N')
                    throw LookupWork.fail(
                            UNSUPPORTED, "Direct EN page requires raw N representation");
                work.decode(count);
                return range(
                        plan.wholeEncodedBytes(),
                        chunk.encodedOffset() + 1 + start - chunk.decodedOffset(),
                        count);
            }
            byte[] decoded = cache.get(chunk.index());
            if (decoded == null) decoded = chunk(chunk).decoded();
            return Arrays.copyOfRange(
                    decoded,
                    (int) (start - chunk.decodedOffset()),
                    (int) (start - chunk.decodedOffset()) + count);
        }
        throw LookupWork.fail(
                UNSUPPORTED, "Selected EN page crosses unsupported parent chunk boundary");
    }

    private static long u32(byte[] b, int at) {
        return ((long) (b[at] & 255) << 24)
                | ((long) (b[at + 1] & 255) << 16)
                | ((long) (b[at + 2] & 255) << 8)
                | (b[at + 3] & 255);
    }
}
