package io.vibeminer.application.bootstrap;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.domain.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultBootstrapServiceTest {
    static BuildSnapshot build() { return new BuildSnapshot("wow", "Synthetic", "Published", "us", "1.0", "a".repeat(32), "b".repeat(32), Instant.EPOCH, true); }
    static final class Queue implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable work) { tasks.add(work); }
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
    static final BootstrapArtifactGateway NO_GATEWAY = new BootstrapArtifactGateway() {
        public InstallPlan inspectConfiguration(BuildSnapshot build, CancellationToken token) { throw new AssertionError("Unexpected source action"); }
        public void validatePlan(InstallPlan plan, CancellationToken token) { throw new AssertionError(); }
        public VerifiedSourceAlias retainConfiguration(InstallPlan plan, CancellationToken token) { throw new AssertionError(); }
        public InstallPlan restoreConfiguration(BuildSnapshot build, VerifiedSourceAlias object, CancellationToken token) { throw new AssertionError(); }
        public ResolvedSourceIdentity configurationIdentity(BuildSnapshot build) { throw new AssertionError(); }
        public ResolvedSourceIdentity installIdentity(InstallPlan plan) { throw new AssertionError(); }
        public VerifiedSourceAlias acquireInstall(InstallPlan plan, CancellationToken token) { throw new AssertionError(); }
        public InstallInspection inspectRetained(InstallPlan plan, VerifiedSourceAlias object, CancellationToken token) { throw new AssertionError(); }
    };
    static final BuildObjectCatalog NO_CATALOG = new BuildObjectCatalog() {
        public void registerSnapshot(BuildSnapshot build) { throw new AssertionError(); }
        public Optional<StoredObject> findVerifiedAlias(ResolvedSourceIdentity source, CancellationToken token) { throw new AssertionError(); }
        public Optional<VerifiedSourceAlias> findVerifiedSourceAlias(ResolvedSourceIdentity source, CancellationToken token) { throw new AssertionError(); }
        public BuildObjectReference attachVerifiedObject(String snapshot, String role, ResolvedSourceIdentity source, StoredObject object, SourceVerificationEvidence evidence, CancellationToken token) { throw new AssertionError(); }
        public List<BuildObjectReference> listReferences(String snapshot) { throw new AssertionError(); }
        public Optional<BuildObjectReference> findVerifiedObject(String snapshot, String role, CancellationToken token) { throw new AssertionError(); }
    };
    static final ObjectStore NO_STORE = new ObjectStore() {
        public StoredObject publish(InputStream stream, ExpectedObjectChecksum expected, long maximum, CancellationToken token) { throw new AssertionError(); }
        public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) { throw new AssertionError(); }
        public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) { throw new AssertionError(); }
        public void close() { throw new AssertionError("Service does not own storage"); }
    };
    @Test void queuedCancellationAndCloseNeverInvokeOutboundPorts() {
        var queue = new Queue();
        try (var service = new DefaultBootstrapService(NO_GATEWAY, NO_CATALOG, NO_STORE, queue, Runnable::run)) {
            var request = service.inspectConfiguration(build()).toCompletableFuture();
            assertTrue(request.cancel(true)); queue.drain(); assertTrue(request.isCancelled());
            var pending = service.inspectConfiguration(build()).toCompletableFuture();
            service.close(); queue.drain(); assertTrue(pending.isCancelled());
            var closed = assertThrows(CompletionException.class, () -> service.reopen(build()).toCompletableFuture().join());
            assertEquals(BootstrapException.Reason.CLOSED, ((BootstrapException)closed.getCause()).reason());
        }
    }
    @Test void queueCapacityRemainsBoundedUntilCancelledTasksActuallyRetire() {
        var queue = new Queue();
        try (var service = new DefaultBootstrapService(NO_GATEWAY, NO_CATALOG, NO_STORE, queue, Runnable::run)) {
            var pending = new ArrayList<CompletableFuture<InstallPlan>>();
            for (int i = 0; i < 4; i++) pending.add(service.inspectConfiguration(build()).toCompletableFuture());
            pending.forEach(future -> future.cancel(true));
            var full = assertThrows(CompletionException.class, () -> service.inspectConfiguration(build()).toCompletableFuture().join());
            assertEquals(BootstrapException.Reason.CAPACITY, ((BootstrapException)full.getCause()).reason());
            assertEquals(4, queue.tasks.size()); queue.drain();
            var next = service.inspectConfiguration(build()).toCompletableFuture(); assertFalse(next.isDone()); next.cancel(true); queue.drain();
        }
    }
    @Test void rejectedExecutorIsAnExplicitCapacityOutcome() {
        Executor rejected = work -> { throw new RejectedExecutionException(); };
        try (var service = new DefaultBootstrapService(NO_GATEWAY, NO_CATALOG, NO_STORE, rejected, rejected)) {
            var failure = assertThrows(CompletionException.class, () -> service.inspectConfiguration(build()).toCompletableFuture().join());
            assertEquals(BootstrapException.Reason.CAPACITY, ((BootstrapException)failure.getCause()).reason());
        }
    }
}
