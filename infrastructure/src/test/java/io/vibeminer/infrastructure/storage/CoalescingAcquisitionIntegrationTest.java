package io.vibeminer.infrastructure.storage;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.acquisition.*;
import io.vibeminer.domain.ChecksumAlgorithm;
import io.vibeminer.domain.ExpectedObjectChecksum;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.SourceVerificationEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CoalescingAcquisitionIntegrationTest {
    @TempDir Path directory;
    private static final byte[] DATA = "synthetic acquired object".getBytes(StandardCharsets.UTF_8);
    private static final ResolvedSourceIdentity SOURCE = new ResolvedSourceIdentity("synthetic-wow:encoded-object", "full-key-A", "synthetic:whole-stream-md5:v1");

    @Test void twoBuildConsumersShareOneSyntheticTransferAndOnePhysicalObjectThenReuseLocally() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1); var transfers = new AtomicInteger();
        try (var store = new FileObjectStore(directory); var executor = Executors.newFixedThreadPool(2)) {
            try (var service = new CoalescingAcquisitionService(store, (identity, cancellation) -> {
                assertEquals(SOURCE, identity); transfers.incrementAndGet(); started.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new java.util.concurrent.CancellationException(); }
                return verified(identity, store.publish(new ByteArrayInputStream(DATA), expected(), 1024, cancellation));
            }, executor)) {
                var buildA = service.acquire(SOURCE);
                assertTrue(started.await(2, TimeUnit.SECONDS));
                var buildB = service.acquire(SOURCE);
                buildA.cancel(); release.countDown();
                var object = buildB.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertTrue(buildA.completion().toCompletableFuture().isCancelled());
                assertEquals(1, transfers.get());
                try (var paths = Files.walk(directory.resolve("objects"))) { assertEquals(1, paths.filter(Files::isRegularFile).count()); }
                var reopened = service.acquire(SOURCE).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(object, reopened); assertEquals(1, transfers.get());
                try (var lease = store.openRead(object.object().digest(), CancellationToken.none())) { assertArrayEquals(DATA, lease.stream().readAllBytes()); }
            } finally { release.countDown(); }
        }
    }

    @Test void failedSourceVerificationNeverMakesAnOrphanPhysicalObjectAReusableSourceAlias() throws Exception {
        var attempts = new AtomicInteger();
        try (var store = new FileObjectStore(directory); var executor = Executors.newSingleThreadExecutor();
             var service = new CoalescingAcquisitionService(store, (identity, cancellation) -> {
                 var object = store.publish(new ByteArrayInputStream(DATA), expected(), 1024, cancellation);
                 if (attempts.incrementAndGet() == 1) {
                     throw new AcquisitionException(AcquisitionException.Code.SOURCE_FAILURE, "Synthetic protocol verifier rejected its source");
                 }
                 return verified(identity, object);
             }, executor)) {
            var first = service.acquire(SOURCE);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> first.completion().toCompletableFuture().get(3, TimeUnit.SECONDS));
            var second = service.acquire(SOURCE).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(2, attempts.get());
            assertTrue(store.verify(second.object().digest(), CancellationToken.none()).isPresent());
            try (var paths = Files.walk(directory.resolve("objects"))) { assertEquals(1, paths.filter(Files::isRegularFile).count()); }
        }
    }

    private static VerifiedSourceAlias verified(ResolvedSourceIdentity source, io.vibeminer.domain.StoredObject object) {
        return new VerifiedSourceAlias(source, object, new SourceVerificationEvidence(source.verificationSemantics(), "https://synthetic.example.invalid/object", java.time.Instant.EPOCH));
    }

    private static ExpectedObjectChecksum expected() {
        try { return new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(DATA)), DATA.length); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
