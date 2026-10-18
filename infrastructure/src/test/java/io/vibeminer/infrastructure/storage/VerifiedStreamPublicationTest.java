package io.vibeminer.infrastructure.storage;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.CompletionVerification;
import io.vibeminer.application.ObjectStoreException;
import io.vibeminer.domain.ChecksumAlgorithm;
import io.vibeminer.domain.ExpectedObjectChecksum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedStreamPublicationTest {
    @TempDir Path root;
    private static final byte[] DATA = "encoded bytes whose local SHA is initially unknown".getBytes(StandardCharsets.UTF_8);
    private static final CancellationToken NONE = CancellationToken.none();

    @Test void verificationRunsOnceAfterExactEofBeforePhysicalPublicationAndCallerKeepsStreamOwnership() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var source = new ObservedInput(DATA); var calls = new AtomicInteger();
            CompletionVerification verification = cancellation -> {
                assertTrue(source.eof); assertFalse(source.closed); assertEquals(1, calls.incrementAndGet());
                assertEquals(0, objectCount()); assertEquals(1, stageCount());
            };
            var object = store.publishVerified(source, DATA.length, 1024, verification, NONE);
            assertEquals(1, calls.get()); assertFalse(source.closed); assertEquals(1, objectCount()); assertEquals(0, stageCount());
            assertEquals(sha256(DATA), object.digest().value()); assertEquals(DATA.length, object.length());
            try (var lease = store.openRead(object.digest(), NONE)) { assertArrayEquals(DATA, lease.stream().readAllBytes()); }
            source.close(); assertTrue(source.closed);
        }
    }

    @Test void failedCompletionGateNeverPublishesEvenWhenAllEncodedBytesWereRead() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var source = new ObservedInput(DATA); var rejection = new IllegalStateException("Synthetic decoded CKey mismatch");
            assertSame(rejection, assertThrows(IllegalStateException.class, () -> store.publishVerified(source, DATA.length, 1024, cancellation -> {
                assertTrue(source.eof); throw rejection;
            }, NONE)));
            assertEquals(0, objectCount()); assertEquals(0, stageCount()); assertFalse(source.closed);
        }
    }

    @Test void shortLongOrEofIoFailureCannotInvokeTheCompletionGate() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var calls = new AtomicInteger(); CompletionVerification verify = cancellation -> calls.incrementAndGet();
            for (long length : new long[]{DATA.length - 1, DATA.length + 1}) {
                var failure = assertThrows(ObjectStoreException.class, () -> store.publishVerified(new ObservedInput(DATA), length, 1024, verify, NONE));
                assertEquals(ObjectStoreException.Code.INTEGRITY_FAILURE, failure.code());
            }
            var failedEof = new java.io.InputStream() {
                private final ByteArrayInputStream bytes = new ByteArrayInputStream(DATA);
                @Override public int read(byte[] output, int offset, int length) throws IOException {
                    if (bytes.available() == 0) throw new IOException("Synthetic failure instead of EOF");
                    return bytes.read(output, offset, length);
                }
                @Override public int read() throws IOException {
                    if (bytes.available() == 0) throw new IOException("Synthetic failure instead of EOF");
                    return bytes.read();
                }
            };
            var ioFailure = assertThrows(ObjectStoreException.class, () -> store.publishVerified(failedEof, DATA.length, 1024, verify, NONE));
            assertEquals(ObjectStoreException.Code.IO_FAILURE, ioFailure.code());
            assertEquals(0, calls.get()); assertEquals(0, objectCount()); assertEquals(0, stageCount());
        }
    }

    @Test void cancellationObservedAtEofSkipsGateAndPublication() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var cancelled = new AtomicBoolean(); var calls = new AtomicInteger();
            var source = new ByteArrayInputStream(DATA) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    int count = super.read(bytes, offset, length); if (count < 0) cancelled.set(true); return count;
                }
            };
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> store.publishVerified(source, DATA.length, 1024, cancellation -> calls.incrementAndGet(), cancelled::get));
            assertEquals(0, calls.get()); assertEquals(0, objectCount()); assertEquals(0, stageCount());
        }
    }

    @Test void cancellationDuringGatePreventsPromotionEvenIfGateReturnsNormally() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var cancelled = new AtomicBoolean(); var calls = new AtomicInteger();
            assertThrows(java.util.concurrent.CancellationException.class, () -> store.publishVerified(new ObservedInput(DATA), DATA.length, 1024, token -> {
                calls.incrementAndGet(); assertFalse(token.isCancellationRequested()); cancelled.set(true);
            }, cancelled::get));
            assertEquals(1, calls.get()); assertEquals(0, objectCount()); assertEquals(0, stageCount());
        }
    }

    @Test void gateCancellationAndConfiguredLimitLeaveNoPartialObjects() throws Exception {
        try (var store = new FileObjectStore(root)) {
            assertThrows(java.util.concurrent.CancellationException.class, () -> store.publishVerified(new ObservedInput(DATA), DATA.length, 1024,
                    token -> { throw new java.util.concurrent.CancellationException("Verifier cancelled"); }, NONE));
            var calls = new AtomicInteger(); var source = new ObservedInput(DATA);
            var failure = assertThrows(ObjectStoreException.class, () -> store.publishVerified(source, DATA.length, 1, token -> calls.incrementAndGet(), NONE));
            assertEquals(ObjectStoreException.Code.LIMIT_EXCEEDED, failure.code()); assertEquals(0, source.position());
            assertEquals(0, calls.get()); assertEquals(0, objectCount()); assertEquals(0, stageCount());
        }
    }

    @Test void concurrentChecksumAndCompletionVerifiedPublishersShareOnePhysicalBlob() throws Exception {
        try (var store = new FileObjectStore(root); var workers = Executors.newFixedThreadPool(4)) {
            var callbacks = new AtomicInteger();
            var expected = new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, sha256(DATA), DATA.length);
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(i -> workers.submit(() -> i % 2 == 0
                    ? store.publish(new ByteArrayInputStream(DATA), expected, 1024, NONE)
                    : store.publishVerified(new ObservedInput(DATA), DATA.length, 1024, token -> callbacks.incrementAndGet(), NONE))).toList();
            var object = tasks.getFirst().get(3, TimeUnit.SECONDS);
            for (var task : tasks) assertEquals(object, task.get(3, TimeUnit.SECONDS));
            assertEquals(4, callbacks.get()); assertEquals(1, objectCount()); assertEquals(0, stageCount());
        }
    }

    @Test void existingObjectCannotBypassThisSourceCompletionVerification() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var object = store.publish(new ByteArrayInputStream(DATA), new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, sha256(DATA), DATA.length), 1024, NONE);
            assertThrows(IllegalStateException.class, () -> store.publishVerified(new ObservedInput(DATA), DATA.length, 1024,
                    token -> { throw new IllegalStateException("This source did not verify"); }, NONE));
            assertEquals(1, objectCount()); assertTrue(store.verify(object.digest(), NONE).isPresent()); assertEquals(0, stageCount());
        }
    }

    @Test void completionPublicationPreservesFailClosedCorruptionAndActiveOperationProtection() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var object = store.publishVerified(new ObservedInput(DATA), DATA.length, 1024, token -> {
                var busy = assertThrows(ObjectStoreException.class, store::close); assertEquals(ObjectStoreException.Code.BUSY, busy.code());
            }, NONE);
            var path = root.resolve("objects").resolve(object.digest().value().substring(0, 2)).resolve(object.digest().value().substring(2));
            Files.writeString(path, "corrupt");
            var failure = assertThrows(ObjectStoreException.class,
                    () -> store.publishVerified(new ObservedInput(DATA), DATA.length, 1024, token -> {}, NONE));
            assertEquals(ObjectStoreException.Code.INTEGRITY_FAILURE, failure.code()); assertEquals("corrupt", Files.readString(path));
            assertEquals(1, objectCount()); assertEquals(0, stageCount());
        }
    }

    private long objectCount() {
        try (var paths = Files.walk(root.resolve("objects"))) { return paths.filter(Files::isRegularFile).count(); }
        catch (IOException failure) { throw new AssertionError(failure); }
    }
    private long stageCount() {
        try (var paths = Files.list(root.resolve("work"))) { return paths.count(); }
        catch (IOException failure) { throw new AssertionError(failure); }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static final class ObservedInput extends ByteArrayInputStream {
        private boolean eof; private boolean closed;
        private ObservedInput(byte[] bytes) { super(bytes); }
        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, length); if (count < 0) eof = true; return count;
        }
        @Override public synchronized int read() { int value = super.read(); if (value < 0) eof = true; return value; }
        int position() { return pos; }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
