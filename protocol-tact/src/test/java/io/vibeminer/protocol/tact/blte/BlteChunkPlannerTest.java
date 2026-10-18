package io.vibeminer.protocol.tact.blte;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

class BlteChunkPlannerTest {
    static final BlteChunkPlanner.Limits LIMITS =
            new BlteChunkPlanner.Limits(4096, 100, 1_000_000, 1_000_000, 10000, 10000);

    static void assertReason(
            BlteException.Reason reason, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason, assertThrows(BlteException.class, operation).reason());
    }

    static byte[] md5(byte[] value) throws Exception {
        return MessageDigest.getInstance("MD5").digest(value);
    }

    static byte[] raw(String value) {
        return ("N" + value).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] zip(String value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write('Z');
        try (var zip = new DeflaterOutputStream(bytes)) {
            zip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    static byte[] header(byte[][] encoded, long[] decoded) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeBytes("BLTE");
        out.writeInt(12 + 24 * encoded.length);
        out.writeByte(15);
        out.writeByte(encoded.length >>> 16);
        out.writeShort(encoded.length);
        for (int i = 0; i < encoded.length; i++) {
            out.writeInt(encoded[i].length);
            out.writeInt((int) decoded[i]);
            out.write(md5(encoded[i]));
        }
        return bytes.toByteArray();
    }

    @Test
    void authenticatesExactOffsetsAndDecodesOnlySelectedChunkWithNoWholeContentClaim()
            throws Exception {
        byte[][] chunks = {raw("first"), zip("second compressed")};
        long[] sizes = {5, 17};
        byte[] header = header(chunks, sizes);
        var planner = new BlteChunkPlanner();
        var plan =
                planner.authenticate(
                        header,
                        md5(header),
                        header.length + chunks[0].length + chunks[1].length,
                        22,
                        LIMITS,
                        () -> false);
        assertEquals(2, plan.chunks().size());
        assertEquals(60, plan.headerBytes());
        assertEquals(60, plan.chunks().getFirst().encodedOffset());
        assertEquals(66, plan.chunks().get(1).encodedOffset());
        assertEquals(5, plan.chunks().get(1).decodedOffset());
        var decoded = planner.decodeSelected(plan, plan.chunks().get(1), chunks[1], () -> false);
        assertEquals(
                "second compressed", new String(decoded.decodedBytes(), StandardCharsets.UTF_8));
        assertEquals(BlteChunkPlanner.VerificationScope.AUTHENTICATED_CHUNK_ONLY, decoded.scope());
        assertSame(plan.chunks().get(1), decoded.chunk());
    }

    @Test
    void authenticationAndChunkHashesCannotBeMutatedThroughArraysOrLists() throws Exception {
        byte[][] chunks = {raw("abc")};
        byte[] header = header(chunks, new long[] {3}), key = md5(header);
        var planner = new BlteChunkPlanner();
        var plan = planner.authenticate(header, key, header.length + 4, 3, LIMITS, () -> false);
        key[0] ^= 1;
        header[20] ^= 1;
        plan.encodingKey()[0] ^= 1;
        plan.chunks().getFirst().encodedMd5()[0] ^= 1;
        assertThrows(UnsupportedOperationException.class, () -> plan.chunks().clear());
        var verified =
                planner.decodeSelected(plan, plan.chunks().getFirst(), chunks[0], () -> false);
        verified.decodedBytes()[0] ^= 1;
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), verified.decodedBytes());
    }

    @Test
    void rejectsCorruptHeaderChunkAndUnrelatedAuthenticatedDescriptor() throws Exception {
        byte[][] chunks = {raw("abc")};
        byte[] header = header(chunks, new long[] {3});
        var planner = new BlteChunkPlanner();
        var plan =
                planner.authenticate(
                        header, md5(header), header.length + 4, 3, LIMITS, () -> false);
        byte[] corruptHeader = header.clone();
        corruptHeader[20] ^= 1;
        assertReason(
                BlteException.Reason.INTEGRITY,
                () ->
                        planner.authenticate(
                                corruptHeader,
                                md5(header),
                                header.length + 4,
                                3,
                                LIMITS,
                                () -> false));
        byte[] corruptChunk = chunks[0].clone();
        corruptChunk[1] ^= 1;
        assertReason(
                BlteException.Reason.INTEGRITY,
                () ->
                        planner.decodeSelected(
                                plan, plan.chunks().getFirst(), corruptChunk, () -> false));
        var other =
                planner.authenticate(
                        header, md5(header), header.length + 4, 3, LIMITS, () -> false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        planner.decodeSelected(
                                plan, other.chunks().getFirst(), chunks[0], () -> false));
    }

    @Test
    void requiresMandatoryEkeyAndExplicitWholeObjectForZeroHeader() throws Exception {
        var planner = new BlteChunkPlanner();
        byte[] zero = {'B', 'L', 'T', 'E', 0, 0, 0, 0};
        assertThrows(
                IllegalArgumentException.class,
                () -> planner.authenticate(zero, null, 9, 0, LIMITS, () -> false));
        assertReason(
                BlteException.Reason.UNSUPPORTED,
                () -> planner.authenticate(zero, new byte[16], 9, 0, LIMITS, () -> false));
    }

    @Test
    void verifiesTotalsLengthsTruncationCountsAndLimitsBeforeExposingAPlan() throws Exception {
        byte[][] chunks = {raw("abc")};
        byte[] header = header(chunks, new long[] {3});
        var planner = new BlteChunkPlanner();
        for (long[] totals :
                List.of(
                        new long[] {header.length + 5, 3},
                        new long[] {header.length + 4, 4},
                        new long[] {Long.MAX_VALUE, 3})) {
            var broad =
                    new BlteChunkPlanner.Limits(
                            4096, 100, Long.MAX_VALUE, Long.MAX_VALUE, 10000, 10000);
            assertReason(
                    BlteException.Reason.MALFORMED,
                    () ->
                            planner.authenticate(
                                    header, md5(header), totals[0], totals[1], broad, () -> false));
        }
        byte[] truncated = Arrays.copyOf(header, header.length - 1);
        assertReason(
                BlteException.Reason.MALFORMED,
                () ->
                        planner.authenticate(
                                truncated,
                                md5(truncated),
                                header.length + 4,
                                3,
                                LIMITS,
                                () -> false));
        var small = new BlteChunkPlanner.Limits(4096, 100, 1000000, 1000000, 3, 2);
        assertReason(
                BlteException.Reason.LIMIT,
                () ->
                        planner.authenticate(
                                header, md5(header), header.length + 4, 3, small, () -> false));
        assertReason(
                BlteException.Reason.CANCELLED,
                () ->
                        planner.authenticate(
                                header, md5(header), header.length + 4, 3, LIMITS, () -> true));
    }

    @Test
    void exactSelectedLengthCancellationAndUnsupportedModesStayExplicit() throws Exception {
        byte[][] chunks = {raw("abc")};
        byte[] header = header(chunks, new long[] {3});
        var planner = new BlteChunkPlanner();
        var plan =
                planner.authenticate(
                        header, md5(header), header.length + 4, 3, LIMITS, () -> false);
        assertReason(
                BlteException.Reason.MALFORMED,
                () ->
                        planner.decodeSelected(
                                plan, plan.chunks().getFirst(), new byte[3], () -> false));
        assertReason(
                BlteException.Reason.CANCELLED,
                () ->
                        planner.decodeSelected(
                                plan, plan.chunks().getFirst(), chunks[0], () -> true));
        byte[][] encrypted = {{'E', 0}};
        byte[] encryptedHeader = header(encrypted, new long[] {0});
        var encryptedPlan =
                planner.authenticate(
                        encryptedHeader,
                        md5(encryptedHeader),
                        encryptedHeader.length + 2,
                        0,
                        LIMITS,
                        () -> false);
        assertReason(
                BlteException.Reason.UNSUPPORTED,
                () ->
                        planner.decodeSelected(
                                encryptedPlan,
                                encryptedPlan.chunks().getFirst(),
                                encrypted[0],
                                () -> false));
    }

    @Test
    void planDescriptorsAndReceiptsHaveNoPublicOrPackageConstructors() {
        for (Class<?> type :
                List.of(
                        BlteChunkPlanner.AuthenticatedPlan.class,
                        BlteChunkPlanner.Chunk.class,
                        BlteChunkPlanner.VerifiedChunk.class)) {
            assertTrue(java.lang.reflect.Modifier.isFinal(type.getModifiers()));
            assertTrue(
                    Arrays.stream(type.getDeclaredConstructors())
                            .allMatch(
                                    constructor ->
                                            java.lang.reflect.Modifier.isPrivate(
                                                    constructor.getModifiers())));
        }
    }

    @Test
    void preservesLongOffsetsAndRejectsUnsignedChunkSizesOutsideJvmBudgets() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeBytes("BLTE");
        out.writeInt(84);
        out.writeInt(0x0f000003);
        for (int i = 0; i < 3; i++) {
            out.writeInt(2_000_000_000);
            out.writeInt(0);
            out.write(new byte[16]);
        }
        byte[] header = bytes.toByteArray();
        var planner = new BlteChunkPlanner();
        var broad =
                new BlteChunkPlanner.Limits(
                        4096,
                        100,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE);
        var plan = planner.authenticate(header, md5(header), 6_000_000_084L, 0, broad, () -> false);
        assertEquals(4_000_000_084L, plan.chunks().getLast().encodedOffset());
        byte[] oversized = header(new byte[][] {raw("abc")}, new long[] {3});
        java.nio.ByteBuffer.wrap(oversized).putInt(12, -1);
        assertReason(
                BlteException.Reason.LIMIT,
                () ->
                        planner.authenticate(
                                oversized,
                                md5(oversized),
                                oversized.length + 0xffff_ffffL,
                                3,
                                broad,
                                () -> false));
        java.nio.ByteBuffer.wrap(oversized).putInt(12, 4).putInt(16, -1);
        assertReason(
                BlteException.Reason.LIMIT,
                () ->
                        planner.authenticate(
                                oversized,
                                md5(oversized),
                                oversized.length + 4,
                                0xffff_ffffL,
                                broad,
                                () -> false));
    }

    @Test
    void malformedTablesAndTruncatedPrefixesNeverExposeDescriptors() throws Exception {
        var planner = new BlteChunkPlanner();
        byte[] good = header(new byte[][] {raw("abc")}, new long[] {3});
        for (int length = 0; length < good.length; length++) {
            byte[] prefix = Arrays.copyOf(good, length);
            assertReason(
                    BlteException.Reason.MALFORMED,
                    () ->
                            planner.authenticate(
                                    prefix, md5(good), good.length + 4, 3, LIMITS, () -> false));
        }
        byte[] empty = {'B', 'L', 'T', 'E', 0, 0, 0, 12, 15, 0, 0, 0};
        assertReason(
                BlteException.Reason.MALFORMED,
                () -> planner.authenticate(empty, md5(empty), 13, 0, LIMITS, () -> false));
        byte[] zero = good.clone();
        java.nio.ByteBuffer.wrap(zero).putInt(12, 0);
        assertReason(
                BlteException.Reason.MALFORMED,
                () ->
                        planner.authenticate(
                                zero, md5(zero), good.length + 4, 3, LIMITS, () -> false));
        byte[] count = good.clone();
        count[11] = 2;
        assertReason(
                BlteException.Reason.MALFORMED,
                () ->
                        planner.authenticate(
                                count, md5(count), good.length + 4, 3, LIMITS, () -> false));
        byte[] unsupported = good.clone();
        unsupported[8] = 16;
        assertReason(
                BlteException.Reason.UNSUPPORTED,
                () ->
                        planner.authenticate(
                                unsupported,
                                md5(unsupported),
                                good.length + 4,
                                3,
                                LIMITS,
                                () -> false));
    }

    @Test
    void headerCountWholeAndMidOperationCancellationBudgetsAreIndependent() throws Exception {
        var planner = new BlteChunkPlanner();
        byte[][] chunks = {raw("abc"), raw("def")};
        byte[] header = header(chunks, new long[] {3, 3});
        for (var limits :
                List.of(
                        new BlteChunkPlanner.Limits(36, 100, 1000, 1000, 100, 100),
                        new BlteChunkPlanner.Limits(4096, 1, 1000, 1000, 100, 100),
                        new BlteChunkPlanner.Limits(4096, 100, 60, 1000, 100, 100),
                        new BlteChunkPlanner.Limits(4096, 100, 1000, 5, 100, 100)))
            assertReason(
                    BlteException.Reason.LIMIT,
                    () ->
                            planner.authenticate(
                                    header,
                                    md5(header),
                                    header.length + 8,
                                    6,
                                    limits,
                                    () -> false));
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertReason(
                BlteException.Reason.CANCELLED,
                () ->
                        planner.authenticate(
                                header,
                                md5(header),
                                header.length + 8,
                                6,
                                LIMITS,
                                () -> checks.incrementAndGet() > 3));
        byte[][] large = {raw("a".repeat(20000))};
        byte[] largeHeader = header(large, new long[] {20000});
        var largeLimits = new BlteChunkPlanner.Limits(4096, 100, 1000000, 1000000, 100000, 100000);
        var plan =
                planner.authenticate(
                        largeHeader,
                        md5(largeHeader),
                        largeHeader.length + large[0].length,
                        20000,
                        largeLimits,
                        () -> false);
        checks.set(0);
        assertReason(
                BlteException.Reason.CANCELLED,
                () ->
                        planner.decodeSelected(
                                plan,
                                plan.chunks().getFirst(),
                                large[0],
                                () -> checks.incrementAndGet() > 3));
    }

    @Test
    void selectedRawAndZlibUseTheFullDecodersCheckedLengthAndTrailingDataRules() throws Exception {
        var planner = new BlteChunkPlanner();
        byte[] valid = zip("abc"), trailing = Arrays.copyOf(valid, valid.length + 1);
        record DecodeCase(byte[] encoded, int expectedDecodedBytes) {}
        var invalidCases =
                List.of(
                        new DecodeCase(raw("abc"), 2),
                        new DecodeCase(valid, 2),
                        new DecodeCase(trailing, 3),
                        new DecodeCase(new byte[] {'Z', 1, 2, 3}, 3));
        for (var invalid : invalidCases) {
            byte[] block = invalid.encoded();
            int expected = invalid.expectedDecodedBytes();
            byte[] header = header(new byte[][] {block}, new long[] {expected});
            var plan =
                    planner.authenticate(
                            header,
                            md5(header),
                            header.length + block.length,
                            expected,
                            LIMITS,
                            () -> false);
            assertReason(
                    BlteException.Reason.MALFORMED,
                    () ->
                            planner.decodeSelected(
                                    plan, plan.chunks().getFirst(), block, () -> false));
        }
    }

    @Test
    void cancellationWhileBindingAuthenticatedDescriptorsDoesNotReturnAPartialPlan()
            throws Exception {
        byte[][] chunks = {raw("abc"), raw("def")};
        byte[] header = header(chunks, new long[] {3, 3});
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertReason(
                BlteException.Reason.CANCELLED,
                () ->
                        new BlteChunkPlanner()
                                .authenticate(
                                        header,
                                        md5(header),
                                        header.length + 8,
                                        6,
                                        LIMITS,
                                        () -> checks.incrementAndGet() > 6));
    }
}
