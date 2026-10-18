package io.vibeminer.infrastructure.storage;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.ObjectStoreException;
import io.vibeminer.domain.ChecksumAlgorithm;
import io.vibeminer.domain.ExpectedObjectChecksum;
import io.vibeminer.domain.ObjectDigest;
import io.vibeminer.domain.StoredObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class FileObjectStoreTest {
    @TempDir Path root;
    private static final byte[] DATA = "synthetic shared evidence".getBytes(StandardCharsets.UTF_8);

    @Test void wholeStreamMd5AndSha256InputsShareOnePhysicalObjectAndReopenOffline() throws Exception {
        StoredObject object;
        try (var store = new FileObjectStore(root)) {
            object = publish(store, DATA, ChecksumAlgorithm.MD5);
            assertEquals(publish(store, DATA, ChecksumAlgorithm.SHA256), object);
            assertEquals(digest(DATA, "SHA-256"), object.digest().value());
            assertEquals(DATA.length, object.length());
            assertEquals(1, physicalObjectCount());
        }
        try (var reopened = new FileObjectStore(root); var lease = reopened.openRead(object.digest(), CancellationToken.none())) {
            assertArrayEquals(DATA, lease.stream().readAllBytes());
            assertEquals(object, lease.object());
        }
    }

    @Test void sourceHashAndShortLongLengthsNeverPublishPartialObjects() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var wrongHash = new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, "0".repeat(32), DATA.length);
            assertFailure(ObjectStoreException.Code.INTEGRITY_FAILURE,
                    () -> store.publish(new ByteArrayInputStream(DATA), wrongHash, 1024, CancellationToken.none()));
            for (int length : List.of(DATA.length - 1, DATA.length + 1)) {
                var wrongLength = new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, digest(DATA, "SHA-256"), length);
                assertFailure(ObjectStoreException.Code.INTEGRITY_FAILURE,
                        () -> store.publish(new ByteArrayInputStream(DATA), wrongLength, 1024, CancellationToken.none()));
            }
            assertEquals(0, physicalObjectCount());
            assertEquals(0, stagedFileCount());
        }
    }

    @Test void expectedLengthOverLimitIsRejectedWithoutReadingInput() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var read = new AtomicBoolean();
            var input = new InputStream() { public int read() { read.set(true); return -1; } };
            assertFailure(ObjectStoreException.Code.LIMIT_EXCEEDED,
                    () -> store.publish(input, expected(DATA, ChecksumAlgorithm.MD5), 1, CancellationToken.none()));
            assertFalse(read.get());
        }
    }

    @Test void midStreamCancellationRemovesStagingAndKeepsCallerInputOwnership() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var cancel = new AtomicBoolean();
            var closed = new AtomicBoolean();
            var input = new ByteArrayInputStream(DATA) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    int count = super.read(bytes, offset, Math.min(3, length));
                    cancel.set(true);
                    return count;
                }
                @Override public void close() { closed.set(true); }
            };
            assertThrows(CancellationException.class, () -> store.publish(input, expected(DATA, ChecksumAlgorithm.MD5), 1024, cancel::get));
            assertFalse(closed.get());
            assertEquals(0, physicalObjectCount());
            assertEquals(0, stagedFileCount());
        }
    }

    @Test void zeroLengthBulkReadsCannotSpinForeverOrLoseInput() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var source = new ByteArrayInputStream(DATA) {
                int emptyReads;
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    if (++emptyReads > DATA.length + 2) throw new IllegalStateException("Bulk reads made no progress");
                    return 0;
                }
            };
            var object = store.publish(source, expected(DATA, ChecksumAlgorithm.MD5), 1024, CancellationToken.none());
            assertEquals(DATA.length, object.length());
        }
    }

    @Test void intermediateSymlinkCannotEscapeTheOwnedObjectDirectory() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var digest = new ObjectDigest(digest(DATA, "SHA-256"));
            var outside = Files.createTempDirectory(root, "outside-");
            Files.write(outside.resolve(digest.value().substring(2)), DATA);
            try { Files.createSymbolicLink(root.resolve("objects").resolve(digest.value().substring(0, 2)), outside); }
            catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic link creation is unavailable on this filesystem");
            }
            assertFailure(ObjectStoreException.Code.INTEGRITY_FAILURE, () -> store.verify(digest, CancellationToken.none()));
            assertFailure(ObjectStoreException.Code.INTEGRITY_FAILURE, () -> store.openRead(digest, CancellationToken.none()));
        }
    }

    @Test void inputIoFailureDoesNotPublishAndDoesNotLeakAStagingFile() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var input = new InputStream() { public int read() throws IOException { throw new IOException("synthetic interrupted source"); } };
            assertFailure(ObjectStoreException.Code.IO_FAILURE,
                    () -> store.publish(input, expected(DATA, ChecksumAlgorithm.MD5), 1024, CancellationToken.none()));
            assertEquals(0, physicalObjectCount());
            assertEquals(0, stagedFileCount());
        }
    }

    @Test void concurrentPublicationConvergesWithoutASecondPhysicalCopy() throws Exception {
        try (var store = new FileObjectStore(root); var workers = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(i -> workers.submit(() -> publish(store, DATA, i % 2 == 0 ? ChecksumAlgorithm.MD5 : ChecksumAlgorithm.SHA256))).toList();
            var expected = tasks.getFirst().get(3, TimeUnit.SECONDS);
            for (var task : tasks) assertEquals(expected, task.get(3, TimeUnit.SECONDS));
            assertEquals(1, physicalObjectCount());
            assertEquals(0, stagedFileCount());
        }
    }

    @Test void corruptionCannotBeReturnedOrSilentlyOverwrittenByDuplicatePublication() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var object = publish(store, DATA, ChecksumAlgorithm.MD5);
            var path = objectPath(object.digest());
            Files.writeString(path, "corrupted evidence");
            assertFailure(ObjectStoreException.Code.INTEGRITY_FAILURE, () -> store.verify(object.digest(), CancellationToken.none()));
            assertFailure(ObjectStoreException.Code.INTEGRITY_FAILURE, () -> store.openRead(object.digest(), CancellationToken.none()));
            assertFailure(ObjectStoreException.Code.INTEGRITY_FAILURE, () -> publish(store, DATA, ChecksumAlgorithm.MD5));
            assertEquals("corrupted evidence", Files.readString(path));
            assertEquals(0, stagedFileCount());
        }
    }

    @Test void missingObjectDiffersFromCorruptObject() {
        try (var store = new FileObjectStore(root)) {
            var absent = new ObjectDigest("a".repeat(64));
            assertTrue(store.verify(absent, CancellationToken.none()).isEmpty());
            assertFailure(ObjectStoreException.Code.MISSING_OBJECT, () -> store.openRead(absent, CancellationToken.none()));
        }
    }

    @Test void processLockRejectsSecondWriterAndCanBeReacquiredAfterClose() {
        try (var first = new FileObjectStore(root)) {
            assertFailure(ObjectStoreException.Code.STORE_IN_USE, () -> new FileObjectStore(root));
        }
        try (var reopened = new FileObjectStore(root)) { assertNotNull(reopened); }
    }

    @Test void restartRemovesOnlyOwnedRegularStagingFilesAndPreservesPublishedObjects() throws Exception {
        StoredObject object;
        try (var store = new FileObjectStore(root)) { object = publish(store, DATA, ChecksumAlgorithm.SHA256); }
        Files.writeString(root.resolve("work/stage-abandoned.tmp"), "partial");
        Files.writeString(root.resolve("work/unknown.keep"), "do not delete");
        try (var reopened = new FileObjectStore(root)) {
            assertFalse(Files.exists(root.resolve("work/stage-abandoned.tmp")));
            assertTrue(Files.exists(root.resolve("work/unknown.keep")));
            assertTrue(reopened.verify(object.digest(), CancellationToken.none()).isPresent());
        }
    }

    @Test void activeReadLeaseProtectsStoreOwnershipUntilClosed() throws Exception {
        var store = new FileObjectStore(root);
        var object = publish(store, DATA, ChecksumAlgorithm.MD5);
        var lease = store.openRead(object.digest(), CancellationToken.none());
        assertFailure(ObjectStoreException.Code.BUSY, store::close);
        assertArrayEquals(DATA, lease.stream().readAllBytes());
        lease.close();
        lease.close();
        store.close();
        store.close();
        assertFailure(ObjectStoreException.Code.CLOSED, () -> store.verify(object.digest(), CancellationToken.none()));
    }

    @Test void emptyObjectIsAValidVerifiedObject() throws Exception {
        try (var store = new FileObjectStore(root)) {
            var object = publish(store, new byte[0], ChecksumAlgorithm.SHA256);
            assertEquals(0, object.length());
            assertTrue(store.verify(object.digest(), CancellationToken.none()).isPresent());
        }
    }

    private StoredObject publish(FileObjectStore store, byte[] bytes, ChecksumAlgorithm algorithm) {
        return store.publish(new ByteArrayInputStream(bytes), expected(bytes, algorithm), 1024, CancellationToken.none());
    }
    private ExpectedObjectChecksum expected(byte[] bytes, ChecksumAlgorithm algorithm) {
        return new ExpectedObjectChecksum(algorithm, digest(bytes, algorithm.jcaName()), bytes.length);
    }
    private static String digest(byte[] bytes, String algorithm) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private Path objectPath(ObjectDigest digest) {
        return root.resolve("objects").resolve(digest.value().substring(0, 2)).resolve(digest.value().substring(2));
    }
    private long physicalObjectCount() throws IOException {
        try (var paths = Files.walk(root.resolve("objects"))) { return paths.filter(Files::isRegularFile).count(); }
    }
    private long stagedFileCount() throws IOException {
        try (var paths = Files.list(root.resolve("work"))) { return paths.filter(path -> path.getFileName().toString().startsWith("stage-")).count(); }
    }
    private static void assertFailure(ObjectStoreException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ObjectStoreException.class, action).code());
    }
}
