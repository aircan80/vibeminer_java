package io.vibeminer.application.acquisition;

import io.vibeminer.application.*;
import io.vibeminer.domain.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CoalescingAcquisitionServiceTest {
    private static final ResolvedSourceIdentity SOURCE = new ResolvedSourceIdentity("wow:encoded-object", "full-immutable-key", "test:whole-stream-sha256:v1");

    @Test void concurrentWaitersShareOneAcquisitionAndCancelIndependently() {
        var executor = new ManualExecutor(); var store = new FakeStore(); var calls = new AtomicInteger();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> {
            calls.incrementAndGet(); cancellation.throwIfCancellationRequested(); return store.add("shared");
        }, executor)) {
            var first = service.acquire(SOURCE); var second = service.acquire(SOURCE);
            assertEquals(1, executor.tasks.size());
            first.cancel(); executor.runNext();
            assertTrue(first.completion().toCompletableFuture().isCancelled());
            assertEquals(store.add("shared"), second.completion().toCompletableFuture().join());
            assertEquals(1, calls.get()); assertEquals(1, store.objects.size());
        }
    }

    @Test void lastCancellationBeforeExecutionSkipsTransferAndAllowsLaterRetry() {
        var executor = new ManualExecutor(); var store = new FakeStore(); var calls = new AtomicInteger();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> { calls.incrementAndGet(); return store.add("data"); }, executor)) {
            var first = service.acquire(SOURCE); var second = service.acquire(SOURCE);
            first.cancel(); second.completion().toCompletableFuture().cancel(true);
            executor.runNext(); assertEquals(0, calls.get());
            var retry = service.acquire(SOURCE); executor.runNext();
            assertEquals(store.add("data"), retry.completion().toCompletableFuture().join());
            assertEquals(1, calls.get());
        }
    }

    @Test void onlyTheFinalCancellationInterruptsRunningUpstream() throws Exception {
        var started = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        var store = new FakeStore();
        try (var executor = Executors.newSingleThreadExecutor(); var service = new CoalescingAcquisitionService(store, (source, cancellation) -> {
            started.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError("unreachable"); }
            catch (InterruptedException interrupted) {
                assertTrue(cancellation.isCancellationRequested()); stopped.countDown();
                throw new java.util.concurrent.CancellationException();
            }
        }, executor)) {
            var first = service.acquire(SOURCE);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var second = service.acquire(SOURCE); first.cancel();
            assertEquals(1, stopped.getCount());
            second.cancel(); assertTrue(stopped.await(2, TimeUnit.SECONDS));
        }
    }

    @Test void verifiedLocalReuseDoesNotReacquireButMissingLocalContentDoes() {
        var executor = new ManualExecutor(); var store = new FakeStore(); var calls = new AtomicInteger();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> { calls.incrementAndGet(); return store.add("data"); }, executor)) {
            var initial = service.acquire(SOURCE); executor.runNext(); var object = initial.completion().toCompletableFuture().join();
            var cached = service.acquire(SOURCE); executor.runNext(); assertEquals(object, cached.completion().toCompletableFuture().join());
            assertEquals(1, calls.get());
            store.objects.clear();
            var absent = service.acquire(SOURCE); executor.runNext(); absent.completion().toCompletableFuture().join();
            assertEquals(2, calls.get());
        }
    }

    @Test void failedVerifierRetiresEntryAndNeverCreatesSuccessfulAlias() {
        var executor = new ManualExecutor(); var store = new FakeStore(); var calls = new AtomicInteger();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> {
            if (calls.incrementAndGet() == 1) throw new AcquisitionException(AcquisitionException.Code.SOURCE_FAILURE, "Synthetic verifier rejected source");
            return store.add("data");
        }, executor)) {
            var first = service.acquire(SOURCE); executor.runNext(); assertFailed(first, AcquisitionException.Code.SOURCE_FAILURE);
            var retry = service.acquire(SOURCE); executor.runNext(); retry.completion().toCompletableFuture().join();
            assertEquals(2, calls.get());
        }
    }

    @Test void namespacesAndVerifierSemanticsArePartOfSharingIdentity() {
        var executor = new ManualExecutor(); var store = new FakeStore(); var calls = new AtomicInteger();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> { calls.incrementAndGet(); return store.add("data"); }, executor)) {
            var first = service.acquire(SOURCE);
            var differentVerifier = service.acquire(new ResolvedSourceIdentity(SOURCE.namespace(), SOURCE.immutableSourceKey(), "test:blte-header-md5:v1"));
            var differentProduct = service.acquire(new ResolvedSourceIdentity("wowf:encoded-object", SOURCE.immutableSourceKey(), SOURCE.verificationSemantics()));
            executor.runNext(); executor.runNext(); executor.runNext();
            first.completion().toCompletableFuture().join(); differentVerifier.completion().toCompletableFuture().join(); differentProduct.completion().toCompletableFuture().join();
            assertEquals(3, calls.get());
        }
    }

    @Test void acquirerCannotReturnAnObjectMissingFromVerifiedLocalStore() {
        var executor = new ManualExecutor(); var store = new FakeStore();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> new StoredObject(new ObjectDigest("0".repeat(64)), 3), executor)) {
            var request = service.acquire(SOURCE); executor.runNext(); assertFailed(request, AcquisitionException.Code.INVALID_VERIFIED_OBJECT);
        }
    }

    @Test void returnedLengthMustAgreeWithTheVerifiedPhysicalObject() {
        var executor = new ManualExecutor(); var store = new FakeStore();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> {
            var object = store.add("data");
            return new StoredObject(object.digest(), object.length() + 1);
        }, executor)) {
            var request = service.acquire(SOURCE); executor.runNext(); assertFailed(request, AcquisitionException.Code.INVALID_VERIFIED_OBJECT);
        }
    }

    @Test void capacityIncludesCancelledTasksUntilTheirQueuedWorkActuallyRetires() {
        var executor = new ManualExecutor(); var store = new FakeStore();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> store.add("data"), executor, 1, 1, 2)) {
            var first = service.acquire(SOURCE);
            assertFailed(service.acquire(SOURCE), AcquisitionException.Code.CAPACITY_EXCEEDED);
            first.cancel();
            assertFailed(service.acquire(SOURCE), AcquisitionException.Code.CAPACITY_EXCEEDED);
            executor.runNext();
            var retry = service.acquire(SOURCE); executor.runNext(); assertNotNull(retry.completion().toCompletableFuture().join());
        }
    }

    @Test void closeCancelsSubscribersAndLeavesInjectedStoreAndExecutorOwnedByCaller() {
        var executor = new ManualExecutor(); var store = new FakeStore();
        var service = new CoalescingAcquisitionService(store, (source, cancellation) -> store.add("data"), executor);
        var request = service.acquire(SOURCE); service.close(); service.close();
        assertTrue(request.completion().toCompletableFuture().isCancelled());
        assertFailed(service.acquire(SOURCE), AcquisitionException.Code.CLOSED);
        executor.runNext(); assertFalse(store.closed);
    }

    @Test void rejectedExecutionRetiresCapacityAndPermitsASecondAttempt() {
        var store = new FakeStore(); var submissions = new AtomicInteger();
        Executor executor = command -> {
            if (submissions.incrementAndGet() == 1) throw new java.util.concurrent.RejectedExecutionException("synthetic queue full");
            command.run();
        };
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> store.add("data"), executor, 1, 2, 2)) {
            assertFailed(service.acquire(SOURCE), AcquisitionException.Code.CAPACITY_EXCEEDED);
            assertNotNull(service.acquire(SOURCE).completion().toCompletableFuture().join());
        }
    }

    @Test void successfulSourceAliasesRemainBoundedAndEvictedMappingsMustVerifyAgain() {
        var executor = new ManualExecutor(); var store = new FakeStore(); var calls = new AtomicInteger();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> { calls.incrementAndGet(); return store.add(source.immutableSourceKey()); }, executor, 2, 2, 1)) {
            var other = new ResolvedSourceIdentity(SOURCE.namespace(), "different-full-key", SOURCE.verificationSemantics());
            for (var source : java.util.List.of(SOURCE, other, SOURCE)) {
                var request = service.acquire(source); executor.runNext(); request.completion().toCompletableFuture().join();
            }
            assertEquals(3, calls.get());
        }
    }

    @Test void corruptLocalAliasFailsClosedWithoutCallingTheSourceAgain() {
        var executor = new ManualExecutor(); var store = new FakeStore(); var calls = new AtomicInteger();
        try (var service = new CoalescingAcquisitionService(store, (source, cancellation) -> { calls.incrementAndGet(); return store.add("data"); }, executor)) {
            var first = service.acquire(SOURCE); executor.runNext(); first.completion().toCompletableFuture().join();
            store.corrupt = true;
            var cached = service.acquire(SOURCE); executor.runNext();
            var failure = assertThrows(CompletionException.class, () -> cached.completion().toCompletableFuture().join());
            assertInstanceOf(ObjectStoreException.class, failure.getCause());
            assertEquals(ObjectStoreException.Code.INTEGRITY_FAILURE, ((ObjectStoreException) failure.getCause()).code());
            assertEquals(1, calls.get());
        }
    }

    @Test void identitiesRequireExplicitBoundedSingleLineVerificationSemantics() {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedSourceIdentity("wow", "key", ""));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedSourceIdentity("wow", "key\nother", "verifier"));
        assertThrows(IllegalArgumentException.class, () -> new ResolvedSourceIdentity("wow", "x".repeat(4097), "verifier"));
    }

    private static void assertFailed(AcquisitionRequest request, AcquisitionException.Code code) {
        var failure = assertThrows(CompletionException.class, () -> request.completion().toCompletableFuture().join());
        assertInstanceOf(AcquisitionException.class, failure.getCause());
        assertEquals(code, ((AcquisitionException) failure.getCause()).code());
    }
    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runNext() { tasks.remove().run(); }
    }
    private static final class FakeStore implements ObjectStore {
        private final Map<ObjectDigest, StoredObject> objects = new HashMap<>();
        private boolean closed;
        private boolean corrupt;
        StoredObject add(String value) {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            try {
                var object = new StoredObject(new ObjectDigest(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))), bytes.length);
                objects.put(object.digest(), object); return object;
            } catch (Exception impossible) { throw new AssertionError(impossible); }
        }
        @Override public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken cancellation) { cancellation.throwIfCancellationRequested(); if (corrupt) throw new ObjectStoreException(ObjectStoreException.Code.INTEGRITY_FAILURE, "synthetic corrupt local bytes"); return Optional.ofNullable(objects.get(digest)); }
        @Override public StoredObject publish(InputStream input, ExpectedObjectChecksum expected, long max, CancellationToken cancellation) { throw new UnsupportedOperationException(); }
        @Override public ObjectReadLease openRead(ObjectDigest digest, CancellationToken cancellation) { throw new UnsupportedOperationException(); }
        @Override public void close() { closed = true; }
    }
}
