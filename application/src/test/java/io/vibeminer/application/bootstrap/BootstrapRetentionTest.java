package io.vibeminer.application.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

class BootstrapRetentionTest {
    static final ResolvedSourceIdentity CONFIG =
            new ResolvedSourceIdentity("test.config", "config", "raw.v1");
    static final ResolvedSourceIdentity INSTALL =
            new ResolvedSourceIdentity("test.install", "install", "blte.v1");

    static VerifiedSourceAlias receipt(ResolvedSourceIdentity source, int digest, long length) {
        return new VerifiedSourceAlias(
                source,
                new StoredObject(new ObjectDigest(String.format("%064x", digest)), length),
                new SourceVerificationEvidence(
                        source.verificationSemantics(),
                        "https://us.cdn.blizzard.com/test",
                        Instant.EPOCH));
    }

    static final VerifiedSourceAlias CFG = receipt(CONFIG, 1, 3), IN = receipt(INSTALL, 2, 20);

    static BuildSnapshot build(String version) {
        return new BuildSnapshot(
                "wow",
                "Synthetic",
                "Published",
                "us",
                version,
                "a".repeat(32),
                "b".repeat(32),
                Instant.EPOCH,
                true);
    }

    static InstallPlan plan(BuildSnapshot build) {
        return new InstallPlan(
                build,
                new SourceDocument(
                        "https://us.cdn.blizzard.com/config", Instant.EPOCH, new byte[] {1, 2, 3}),
                "c".repeat(32),
                "d".repeat(32),
                30,
                20);
    }

    static final class Pins implements RetentionRepository {
        final List<RetentionScope> scopes = new CopyOnWriteArrayList<>();
        Runnable beforePin = () -> {};

        public RetentionScope pin(RetentionScope scope, CancellationToken token) {
            token.throwIfCancellationRequested();
            beforePin.run();
            scopes.add(scope);
            return scope;
        }

        public void release(String id, CancellationToken token) {
            fail("No automatic release");
        }

        public RetentionView readView(String id, RetentionLimits limits, CancellationToken token) {
            throw new AssertionError();
        }
    }

    static final class Catalog implements BuildObjectCatalog {
        final Map<ResolvedSourceIdentity, VerifiedSourceAlias> aliases = new ConcurrentHashMap<>();
        final Map<String, BuildObjectReference> references = new ConcurrentHashMap<>();
        final Set<String> registered = ConcurrentHashMap.newKeySet();

        public void registerSnapshot(BuildSnapshot build) {
            registered.add(build.snapshotId());
        }

        public Optional<StoredObject> findVerifiedAlias(
                ResolvedSourceIdentity source, CancellationToken token) {
            return findVerifiedSourceAlias(source, token).map(VerifiedSourceAlias::object);
        }

        public Optional<VerifiedSourceAlias> findVerifiedSourceAlias(
                ResolvedSourceIdentity source, CancellationToken token) {
            return Optional.ofNullable(aliases.get(source));
        }

        public BuildObjectReference attachVerifiedObject(
                String snapshot,
                String role,
                ResolvedSourceIdentity source,
                StoredObject object,
                SourceVerificationEvidence evidence,
                CancellationToken token) {
            token.throwIfCancellationRequested();
            assertTrue(registered.contains(snapshot));
            var reference = new BuildObjectReference(snapshot, role, source, object, evidence);
            references.put(snapshot + role, reference);
            aliases.put(source, new VerifiedSourceAlias(source, object, evidence));
            return reference;
        }

        public List<BuildObjectReference> listReferences(String snapshot) {
            return references.values().stream()
                    .filter(r -> r.snapshotId().equals(snapshot))
                    .toList();
        }

        public Optional<BuildObjectReference> findVerifiedObject(
                String snapshot, String role, CancellationToken token) {
            return Optional.ofNullable(references.get(snapshot + role));
        }
    }

    static class Gateway implements BootstrapArtifactGateway {
        final Pins pins;
        final Catalog catalog;
        int acquisitions;
        boolean unavailable;

        Gateway(Pins pins, Catalog catalog) {
            this.pins = pins;
            this.catalog = catalog;
        }

        public InstallPlan inspectConfiguration(BuildSnapshot build, CancellationToken token) {
            return plan(build);
        }

        public void validatePlan(InstallPlan plan, CancellationToken token) {}

        public VerifiedSourceAlias retainConfiguration(InstallPlan plan, CancellationToken token) {
            return CFG;
        }

        public InstallPlan restoreConfiguration(
                BuildSnapshot build, VerifiedSourceAlias cfg, CancellationToken token) {
            assertEquals(CFG, cfg);
            return plan(build);
        }

        public ResolvedSourceIdentity configurationIdentity(BuildSnapshot build) {
            return CONFIG;
        }

        public ResolvedSourceIdentity installIdentity(InstallPlan plan) {
            return INSTALL;
        }

        public VerifiedSourceAlias acquireInstall(InstallPlan plan, CancellationToken token) {
            assertTrue(catalog.registered.contains(plan.build().snapshotId()));
            assertTrue(
                    pins.scopes.stream()
                            .anyMatch(s -> s.snapshotId().equals(plan.build().snapshotId())));
            acquisitions++;
            if (unavailable)
                throw new BootstrapException(BootstrapException.Reason.UNAVAILABLE, "Missing");
            return IN;
        }

        public InstallInspection inspectRetained(
                InstallPlan plan, VerifiedSourceAlias object, CancellationToken token) {
            return new InstallInspection(
                    plan, object.object(), object.evidence(), List.of(), List.of());
        }
    }

    static final ObjectStore STORE =
            new ObjectStore() {
                public StoredObject publish(
                        InputStream in,
                        ExpectedObjectChecksum expected,
                        long maximum,
                        CancellationToken token) {
                    throw new AssertionError();
                }

                public StoredObject publishVerified(
                        InputStream in,
                        long length,
                        long maximum,
                        CompletionVerification gate,
                        CancellationToken token) {
                    throw new AssertionError();
                }

                public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) {
                    return Optional.of(
                            digest.equals(IN.object().digest()) ? IN.object() : CFG.object());
                }

                public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
                    throw new AssertionError();
                }

                public void close() {
                    throw new AssertionError();
                }
            };

    static BootstrapException failure(CompletionStage<?> future) {
        return assertInstanceOf(
                BootstrapException.class,
                assertThrows(CompletionException.class, () -> future.toCompletableFuture().join())
                        .getCause());
    }

    @Test
    void explicitDownloadPinsExactDependencyClosureBeforeAcquisitionButInspectAndReopenDoNot() {
        var pins = new Pins();
        var catalog = new Catalog();
        var gateway = new Gateway(pins, catalog);
        var build = build("one");
        try (var service =
                new DefaultBootstrapService(
                        gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
            var plan = service.inspectConfiguration(build).toCompletableFuture().join();
            assertTrue(pins.scopes.isEmpty());
            service.download(plan).toCompletableFuture().join();
            assertEquals(1, pins.scopes.size());
            var scope = pins.scopes.getFirst();
            assertEquals(ScopeKind.INSTALL_METADATA, scope.kind());
            assertEquals(build.snapshotId(), scope.snapshotId());
            assertEquals(
                    List.of(
                            new ScopeRequirement(
                                    "BUILD_CONFIG",
                                    Optional.of(CONFIG),
                                    OptionalLong.of(3),
                                    OptionalLong.of(3)),
                            new ScopeRequirement(
                                    "INSTALL_MANIFEST",
                                    Optional.of(INSTALL),
                                    OptionalLong.of(20),
                                    OptionalLong.of(30))),
                    scope.requirements());
            service.reopen(build).toCompletableFuture().join();
            assertEquals(1, pins.scopes.size());
        }
    }

    @Test
    void forgedPlanAndConflictingMembershipFailBeforePin() {
        var pins = new Pins();
        var catalog = new Catalog();
        var gateway = new Gateway(pins, catalog);
        try (var service =
                new DefaultBootstrapService(
                        gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
            var plan = service.inspectConfiguration(build("one")).toCompletableFuture().join();
            var forged =
                    new InstallPlan(
                            plan.build(),
                            plan.configuration(),
                            "e".repeat(32),
                            plan.encodingKey(),
                            30,
                            20);
            assertEquals(
                    BootstrapException.Reason.INTEGRITY,
                    failure(service.download(forged)).reason());
            var other = receipt(new ResolvedSourceIdentity("other", "key", "v1"), 3, 20);
            catalog.attachVerifiedObject(
                    plan.build().snapshotId(),
                    "INSTALL_MANIFEST",
                    other.source(),
                    other.object(),
                    other.evidence(),
                    () -> false);
            assertEquals(
                    BootstrapException.Reason.INTEGRITY, failure(service.download(plan)).reason());
            assertTrue(pins.scopes.isEmpty());
            assertEquals(0, gateway.acquisitions);
        }
    }

    @Test
    void unavailableDownloadKeepsPinnedPartialIntentAndTwoBuildsShareSameBytes() {
        var pins = new Pins();
        var catalog = new Catalog();
        var gateway = new Gateway(pins, catalog);
        try (var service =
                new DefaultBootstrapService(
                        gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
            var first = service.inspectConfiguration(build("one")).toCompletableFuture().join();
            gateway.unavailable = true;
            assertEquals(
                    BootstrapException.Reason.UNAVAILABLE,
                    failure(service.download(first)).reason());
            assertEquals(1, pins.scopes.size());
            assertEquals(1, catalog.listReferences(first.build().snapshotId()).size());
            gateway.unavailable = false;
            service.download(first).toCompletableFuture().join();
            var second = service.inspectConfiguration(build("two")).toCompletableFuture().join();
            service.download(second).toCompletableFuture().join();
            assertNotEquals(pins.scopes.getFirst().scopeId(), pins.scopes.getLast().scopeId());
            assertEquals(
                    pins.scopes.getFirst().requirements(), pins.scopes.getLast().requirements());
            assertEquals(
                    2,
                    gateway.acquisitions); // failed first transfer, then one shared retained object
        }
    }

    @Test
    void cancellationAfterPinLeavesPartialIntentBeforeAnyQueuedTransferRuns() throws Exception {
        var pins = new Pins();
        var catalog = new Catalog();
        var gateway = new Gateway(pins, catalog);
        var queued = new CountDownLatch(1);
        Executor acquisitions = task -> queued.countDown();
        try (var jobs = Executors.newSingleThreadExecutor();
                var service =
                        new DefaultBootstrapService(
                                gateway, catalog, STORE, jobs, acquisitions, pins)) {
            var plan =
                    service.inspectConfiguration(build("one"))
                            .toCompletableFuture()
                            .get(3, TimeUnit.SECONDS);
            var download = service.download(plan).toCompletableFuture();
            assertTrue(queued.await(3, TimeUnit.SECONDS));
            assertTrue(download.cancel(true));
            assertEquals(1, pins.scopes.size());
            assertEquals(0, gateway.acquisitions);
            assertEquals(1, catalog.listReferences(plan.build().snapshotId()).size());
        }
    }

    @Test
    void retentionFailuresKeepTypedMeaningAndNeverAcquire() {
        var pins = new Pins();
        var catalog = new Catalog();
        var gateway = new Gateway(pins, catalog);
        try (var service =
                new DefaultBootstrapService(
                        gateway, catalog, STORE, Runnable::run, Runnable::run, pins)) {
            var plan = service.inspectConfiguration(build("one")).toCompletableFuture().join();
            var mappings =
                    Map.of(
                            RetentionException.Code.INTEGRITY_FAILURE,
                            BootstrapException.Reason.INTEGRITY,
                            RetentionException.Code.STORAGE_UNAVAILABLE,
                            BootstrapException.Reason.STORAGE,
                            RetentionException.Code.CLOSED,
                            BootstrapException.Reason.CLOSED,
                            RetentionException.Code.CAPACITY_EXCEEDED,
                            BootstrapException.Reason.CAPACITY);
            mappings.forEach(
                    (code, expected) -> {
                        pins.beforePin =
                                () -> {
                                    throw new RetentionException(code, "Pin failed");
                                };
                        assertEquals(expected, failure(service.download(plan)).reason());
                    });
            assertTrue(pins.scopes.isEmpty());
            assertEquals(0, gateway.acquisitions);
        }
    }
}
