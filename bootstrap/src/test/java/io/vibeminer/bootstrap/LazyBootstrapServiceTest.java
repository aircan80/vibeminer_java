package io.vibeminer.bootstrap;

import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.domain.BuildSnapshot;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

class LazyBootstrapServiceTest {
    private static final BuildSnapshot BUILD = FixtureDiscovery.builds().getFirst();
    @RepeatedTest(20) void metadataOnlyConstructionDoesNotInitializeContentAndCancellationReachesActualJob() throws Exception {
        verifyCancellationHandshake(true);
    }
    @RepeatedTest(20) void cancellationAfterDelegateRegistrationReachesActualJob() throws Exception {
        verifyCancellationHandshake(false);
    }
    private void verifyCancellationHandshake(boolean cancelBeforeRegistration) throws Exception {
        var factoryCalls = new AtomicInteger(); var closed = new AtomicInteger();
        var releaseFactory = new CountDownLatch(1);
        var actionEntered = new CountDownLatch(1); var releaseDelegate = new CountDownLatch(1);
        var job = new ObservedJob();
        var service = new FakeService(job, actionEntered) {
            @Override public CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot build) {
                actionEntered.countDown();
                await(releaseDelegate, "delegate return was not released");
                return job;
            }
        };
        var caller = Thread.currentThread(); var worker = new AtomicReference<Thread>();
        var lazy = new LazyBootstrapService(() -> {
            factoryCalls.incrementAndGet(); worker.set(Thread.currentThread());
            await(releaseFactory, "factory was not released");
            return resource(service, closed);
        });
        try {
            assertEquals(0, factoryCalls.get());
            // Keep initialization pending until dispatch has returned its cancellable wrapper.
            var future = lazy.inspectConfiguration(BUILD).toCompletableFuture();
            releaseFactory.countDown();
            assertTrue(actionEntered.await(2, TimeUnit.SECONDS));
            assertNotSame(caller, worker.get());
            if (cancelBeforeRegistration) {
                assertTrue(future.cancel(true));
                // The action cannot return its delegate until explicitly released below.
                assertFalse(job.isCancelled());
                releaseDelegate.countDown();
            } else {
                releaseDelegate.countDown();
                // dispatch installs its delegate reference before subscribing to completion.
                assertTrue(job.registered.await(2, TimeUnit.SECONDS));
                assertTrue(future.cancel(true));
            }
            assertTrue(job.cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(job.isCancelled());
            assertEquals(1, factoryCalls.get());
        } finally {
            releaseFactory.countDown(); releaseDelegate.countDown();
            lazy.close(); lazy.close();
        }
        assertEquals(1, closed.get());
    }
    private static void await(CountDownLatch latch, String message) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError(message); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(message, interrupted);
        }
    }
    private static final class ObservedJob extends CompletableFuture<InstallPlan> {
        final CountDownLatch registered = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        @Override public CompletableFuture<InstallPlan> whenComplete(BiConsumer<? super InstallPlan, ? super Throwable> action) {
            var completion = super.whenComplete(action);
            registered.countDown();
            return completion;
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = super.cancel(mayInterruptIfRunning);
            if (isCancelled()) cancelled.countDown();
            return result;
        }
    }
    @Test void concurrentActionsShareOneInitializationAndCloseCancelsWaiters() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var factoryCalls = new AtomicInteger(); var closed = new AtomicInteger();
        var service = new FakeService(new CompletableFuture<>(), new CountDownLatch(2));
        var lazy = new LazyBootstrapService(() -> {
            factoryCalls.incrementAndGet(); entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("factory not released"); }
            catch (InterruptedException interrupted) { throw new RuntimeException(interrupted); }
            return resource(service, closed);
        });
        var first = lazy.inspectConfiguration(BUILD).toCompletableFuture();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var second = lazy.inspectConfiguration(BUILD).toCompletableFuture(); release.countDown();
        assertTrue(service.called.await(2, TimeUnit.SECONDS)); assertEquals(1, factoryCalls.get());
        lazy.close(); assertTrue(first.isCancelled()); assertTrue(second.isCancelled()); assertEquals(1, closed.get());
    }
    @Test void closeDuringInitializationClosesLateResourcesAndNeverStartsAction() throws Exception {
        var entered = new CountDownLatch(1); var closed = new AtomicInteger(); var calls = new AtomicInteger();
        var service = new FakeService(new CompletableFuture<>(), new CountDownLatch(1));
        var lazy = new LazyBootstrapService(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            return resource(service, closed);
        });
        var pending = lazy.inspectConfiguration(BUILD).toCompletableFuture(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        lazy.close(); assertTrue(pending.isCancelled()); assertEquals(1, closed.get()); assertEquals(1, service.called.getCount());
    }
    @Test void failedInitializationCanBeRetriedAndClosedFacadeCannotReopenStore() {
        var attempts = new AtomicInteger(); var closed = new AtomicInteger();
        var service = new FakeService(CompletableFuture.completedFuture(null), new CountDownLatch(0));
        var lazy = new LazyBootstrapService(() -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("synthetic store in use");
            return resource(service, closed);
        });
        var failed = assertThrows(CompletionException.class, () -> lazy.inspectConfiguration(BUILD).toCompletableFuture().join());
        assertEquals(BootstrapException.Reason.STORAGE, assertInstanceOf(BootstrapException.class, failed.getCause()).reason());
        assertNull(lazy.inspectConfiguration(BUILD).toCompletableFuture().join()); assertEquals(2, attempts.get());
        lazy.close();
        assertThrows(CompletionException.class, () -> lazy.inspectConfiguration(BUILD).toCompletableFuture().join());
        assertEquals(2, attempts.get());
    }
    private static LazyBootstrapService.Resources resource(BootstrapService service, AtomicInteger closed) {
        return new LazyBootstrapService.Resources() {
            public BootstrapService service() { return service; }
            public void close() { service.close(); closed.incrementAndGet(); }
        };
    }
    private static class FakeService implements BootstrapService {
        final CompletableFuture<InstallPlan> job; final CountDownLatch called;
        FakeService(CompletableFuture<InstallPlan> job, CountDownLatch called) { this.job=job; this.called=called; }
        public CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot build) { called.countDown(); return job; }
        public CompletionStage<InstallInspection> download(InstallPlan plan) { return new CompletableFuture<>(); }
        public CompletionStage<InstallInspection> reopen(BuildSnapshot build) { return new CompletableFuture<>(); }
        public void close() { job.cancel(true); }
    }
}
