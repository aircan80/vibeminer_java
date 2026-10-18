package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class FileLookupIntegrationTest {
    @TempDir Path directory;

    static final class Runtime implements AutoCloseable {
        final FileObjectStore store;
        final SqliteBuildObjectCatalog catalog;
        final OfficialFileLookupGateway gateway;
        final List<Thread> jobThreads = new CopyOnWriteArrayList<>();
        final ExecutorService
                jobs =
                        Executors.newFixedThreadPool(
                                4,
                                task -> {
                                    var thread = new Thread(task);
                                    jobThreads.add(thread);
                                    return thread;
                                }),
                acquisitions = Executors.newFixedThreadPool(4);
        final DefaultFileLookupService service;

        Runtime(Path root, RootLookupFixture fixture) {
            store = new FileObjectStore(root.resolve("store"));
            catalog =
                    new SqliteBuildObjectCatalog(
                            new SqliteCatalog(root.resolve("catalog.sqlite")), store);
            gateway =
                    new OfficialFileLookupGateway(
                            store, fixture, Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC));
            service = new DefaultFileLookupService(gateway, catalog, store, jobs, acquisitions);
        }

        @Override
        public void close() {
            service.close();
            acquisitions.close();
            jobs.close();
            gateway.close();
            store.close();
        }
    }

    static <T> T await(CompletionStage<T> work) throws Exception {
        return work.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    static RootSelectionPlan select(RootPlan plan) {
        return new RootSelectionPlan(plan, plan.candidates().getFirst());
    }

    static long objectCount(Path root) throws Exception {
        try (var paths = Files.walk(root.resolve("store/objects"))) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    static void assertCode(
            FileLookupException.Code code, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(ExecutionException.class, operation);
        assertInstanceOf(FileLookupException.class, failure.getCause());
        assertEquals(code, ((FileLookupException) failure.getCause()).code());
    }

    static LookupBudgets budgets(int requests, int maximumChunkDecoded, long decodedWork) {
        var b = LookupBudgets.defaults();
        return new LookupBudgets(
                b.maximumConfigurationBytes(),
                b.maximumMetadataWireBytes(),
                requests,
                b.maximumMetadataDecodedBytes(),
                b.maximumIndexBytes(),
                b.maximumPageBytes(),
                b.maximumCandidates(),
                b.maximumRootEncodedBytes(),
                b.maximumRootDecodedBytes(),
                b.maximumHeaderBytes(),
                b.maximumChunks(),
                b.maximumEncodedChunkBytes(),
                maximumChunkDecoded,
                decodedWork,
                b.maximumSelectedIds(),
                b.maximumMatches(),
                b.maximumRootRecords());
    }

    @Test
    void realCatalogAndCasRestartUseOnlyRetainedRootWithNoEnSessionOrHttp() throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        VerifiedSourceAlias root;
        try (var runtime = new Runtime(directory, fixture)) {
            var plan = await(runtime.service.planRoot(build, LookupBudgets.defaults()));
            root = await(runtime.service.retainRoot(select(plan)));
            assertEquals(2, objectCount(directory));
            assertEquals(2, runtime.catalog.listReferences(build.snapshotId()).size());
        }
        fixture.rejectNetwork = true;
        try (var runtime = new Runtime(directory, fixture)) {
            var result =
                    await(
                            runtime.service.lookupOffline(
                                    build, Set.of(42L, 999L), LookupBudgets.defaults()));
            assertEquals(root, result.root());
            assertEquals(2, result.variants().size());
            assertEquals(Set.of(999L), result.absentIds());
            assertEquals(5, result.scannedRecords());
            assertEquals(2, objectCount(directory));
        }
    }

    @Test
    void staleChangedMixedBuildAndForgedConfigurationPlansFailBeforeRootTransfer()
            throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory, fixture)) {
            var plan = await(runtime.service.planRoot(build, LookupBudgets.defaults()));
            int before = fixture.requests.size();
            var forgedRoot =
                    new RootPlan(
                            plan.sessionId(),
                            plan.build(),
                            plan.configuration(),
                            "a".repeat(32),
                            plan.decodedBytes(),
                            plan.candidates(),
                            plan.budgets());
            var changedBuild =
                    new RootPlan(
                            plan.sessionId(),
                            fixture.build("kr.cdn.blizzard.com", "2.0"),
                            plan.configuration(),
                            plan.contentKey(),
                            plan.decodedBytes(),
                            plan.candidates(),
                            plan.budgets());
            var stale =
                    new RootPlan(
                            "unknown-session",
                            build,
                            plan.configuration(),
                            plan.contentKey(),
                            plan.decodedBytes(),
                            plan.candidates(),
                            plan.budgets());
            var fakeEvidence =
                    new SourceVerificationEvidence(
                            plan.configuration().evidence().verifierSemantics(),
                            "https://evil.example/config",
                            RootLookupFixture.NOW);
            var fakeConfig =
                    new VerifiedSourceAlias(
                            plan.configuration().source(),
                            plan.configuration().object(),
                            fakeEvidence);
            var forgedConfig =
                    new RootPlan(
                            plan.sessionId(),
                            build,
                            fakeConfig,
                            plan.contentKey(),
                            plan.decodedBytes(),
                            plan.candidates(),
                            plan.budgets());
            for (var bad : List.of(forgedRoot, changedBuild, stale, forgedConfig))
                assertCode(
                        FileLookupException.Code.INVALID_PLAN,
                        () -> await(runtime.service.retainRoot(select(bad))));
            assertEquals(before, fixture.requests.size());
            assertEquals(1, objectCount(directory));
        }
    }

    @Test
    void wrongCkeyAndCorruptChunksNeverPublishRootButBadRootFramingNeverGetsAMembership()
            throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var fixture = new RootLookupFixture(mode == 0, mode == 2);
            fixture.corruptRootChunk = mode == 1;
            Path root = directory.resolve("case" + mode);
            var build = fixture.build("us.cdn.blizzard.com", "1.0");
            try (var runtime = new Runtime(root, fixture)) {
                var plan = await(runtime.service.planRoot(build, LookupBudgets.defaults()));
                assertCode(
                        FileLookupException.Code.INTEGRITY_FAILURE,
                        () -> await(runtime.service.retainRoot(select(plan))));
                assertTrue(
                        runtime.catalog
                                .findVerifiedObject(
                                        build.snapshotId(),
                                        DefaultFileLookupService.ROOT_ROLE,
                                        CancellationToken.none())
                                .isEmpty());
                assertEquals(mode == 2 ? 2 : 1, objectCount(root));
                try (var stages = Files.list(root.resolve("store/work"))) {
                    assertEquals(0, stages.count());
                }
            }
        }
    }

    @Test
    void metadataWrongRangeReceiptAndLimitsRefuseBeforeAnUnboundedFallback() throws Exception {
        var fixture = new RootLookupFixture();
        fixture.wrongRangeIdentity = true;
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory.resolve("wrong"), fixture)) {
            assertCode(
                    FileLookupException.Code.INTEGRITY_FAILURE,
                    () -> await(runtime.service.planRoot(build, LookupBudgets.defaults())));
            assertTrue(
                    fixture.requests.stream()
                            .noneMatch(request -> request.encodedKey().equals(fixture.rootEKey)));
        }
        var limitedFixture = new RootLookupFixture();
        var limitedBuild = limitedFixture.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory.resolve("limited"), limitedFixture)) {
            assertCode(
                    FileLookupException.Code.BUDGET_EXCEEDED,
                    () ->
                            await(
                                    runtime.service.planRoot(
                                            limitedBuild, budgets(1, 4 << 20, 512L << 20))));
            assertEquals(0, limitedFixture.requests.size());
        }
    }

    @Test
    void rootTwoPassWorkAndLateLargeChunkBoundsAreCheckedBeforeBodyTransfers() throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory, fixture)) {
            var tinyWork =
                    await(
                            runtime.service.planRoot(
                                    build, budgets(64, 4 << 20, fixture.root.length)));
            assertCode(
                    FileLookupException.Code.BUDGET_EXCEEDED,
                    () -> await(runtime.service.retainRoot(select(tinyWork))));
            assertTrue(
                    fixture.requests.stream()
                            .noneMatch(request -> request.encodedKey().equals(fixture.rootEKey)));
            var tinyChunk = await(runtime.service.planRoot(build, budgets(64, 40, 512L << 20)));
            assertCode(
                    FileLookupException.Code.BUDGET_EXCEEDED,
                    () -> await(runtime.service.retainRoot(select(tinyChunk))));
            var rootRanges =
                    fixture.requests.stream()
                            .filter(request -> request.encodedKey().equals(fixture.rootEKey))
                            .toList();
            assertEquals(2, rootRanges.size());
            assertTrue(rootRanges.stream().allMatch(request -> request.start() < 60));
            assertEquals(1, objectCount(directory));
        }
    }

    @Test
    void offlineWrongReceiptAndPhysicalCorruptionNeverReturnVerifiedAbsence() throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory, fixture)) {
            var plan = await(runtime.service.planRoot(build, LookupBudgets.defaults()));
            var root = await(runtime.service.retainRoot(select(plan)));
            fixture.rejectNetwork = true;
            var forged =
                    new VerifiedSourceAlias(
                            root.source(),
                            root.object(),
                            new SourceVerificationEvidence(
                                    root.evidence().verifierSemantics(),
                                    "https://evil.example/fake",
                                    root.evidence().verifiedAt()));
            var direct =
                    assertThrows(
                            FileLookupException.class,
                            () ->
                                    runtime.gateway.lookupRetained(
                                            build,
                                            plan.configuration(),
                                            forged,
                                            Set.of(999L),
                                            LookupBudgets.defaults(),
                                            CancellationToken.none()));
            assertEquals(FileLookupException.Code.INTEGRITY_FAILURE, direct.code());
            String digest = root.object().digest().value();
            var physical =
                    directory
                            .resolve("store/objects")
                            .resolve(digest.substring(0, 2))
                            .resolve(digest.substring(2));
            Files.writeString(physical, "corrupt");
            assertCode(
                    FileLookupException.Code.INTEGRITY_FAILURE,
                    () ->
                            await(
                                    runtime.service.lookupOffline(
                                            build, Set.of(999L), LookupBudgets.defaults())));
        }
    }

    @Test
    void distinctBuildsShareExactlyOneRootTransferAndCancellationKeepsActualOriginalProof()
            throws Exception {
        var fixture = new RootLookupFixture();
        var a = fixture.build("us.cdn.blizzard.com", "1.0");
        var b = fixture.build("kr.cdn.blizzard.com", "2.0");
        try (var runtime = new Runtime(directory, fixture)) {
            var planA = await(runtime.service.planRoot(a, LookupBudgets.defaults()));
            var planB = await(runtime.service.planRoot(b, LookupBudgets.defaults()));
            assertEquals(1, fixture.configurationRequests.get());
            fixture.blockedRoot = new CompletableFuture<>();
            var first = runtime.service.retainRoot(select(planA)).toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fixture.blockedRequest == null && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
            assertNotNull(fixture.blockedRequest);
            var second = runtime.service.retainRoot(select(planB)).toCompletableFuture();
            while (System.nanoTime() < deadline) {
                long waiters =
                        runtime.jobThreads.stream()
                                .filter(
                                        thread ->
                                                Arrays.stream(thread.getStackTrace())
                                                        .anyMatch(
                                                                frame ->
                                                                        frame.getClassName()
                                                                                        .equals(
                                                                                                DefaultFileLookupService
                                                                                                        .class
                                                                                                        .getName())
                                                                                && frame.getMethodName()
                                                                                        .equals(
                                                                                                "await")))
                                .count();
                if (waiters == 2) break;
                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
            }
            assertEquals(
                    2,
                    runtime.jobThreads.stream()
                            .filter(
                                    thread ->
                                            Arrays.stream(thread.getStackTrace())
                                                    .anyMatch(
                                                            frame ->
                                                                    frame.getClassName()
                                                                                    .equals(
                                                                                            DefaultFileLookupService
                                                                                                    .class
                                                                                                    .getName())
                                                                            && frame.getMethodName()
                                                                                    .equals(
                                                                                            "await")))
                            .count());
            assertTrue(first.cancel(true));
            assertFalse(fixture.blockedRoot.isCancelled());
            var request = fixture.blockedRequest;
            byte[] bytes =
                    Arrays.copyOfRange(
                            fixture.encodedRoot,
                            (int) request.start(),
                            (int) (request.start() + request.count()));
            fixture.blockedRoot.complete(
                    new io.vibeminer.infrastructure.acquisition.AcquiredRange(
                            request,
                            request.uri(),
                            RootLookupFixture.NOW,
                            bytes,
                            RootLookupFixture.hash("SHA-256", bytes)));
            var result = await(second);
            assertTrue(result.evidence().sourceUrl().startsWith("https://us.cdn.blizzard.com/"));
            assertTrue(
                    runtime.catalog
                            .findVerifiedObject(
                                    a.snapshotId(),
                                    DefaultFileLookupService.ROOT_ROLE,
                                    CancellationToken.none())
                            .isEmpty());
            assertEquals(
                    result,
                    runtime.catalog
                            .findVerifiedSourceAlias(result.source(), CancellationToken.none())
                            .orElseThrow());
            long transferred;
            synchronized (fixture.requests) {
                transferred =
                        fixture.requests.stream()
                                .filter(range -> range.encodedKey().equals(fixture.rootEKey))
                                .mapToLong(range -> range.count())
                                .sum();
            }
            assertEquals(fixture.encodedRoot.length, transferred);
            assertEquals(2, objectCount(directory));
            fixture.rejectNetwork = true;
            assertEquals(
                    result,
                    await(runtime.service.lookupOffline(b, Set.of(42L), LookupBudgets.defaults()))
                            .root());
        }
    }

    @Test
    void lastCancelledConsumerAbortsWireAndCleansTheOnlyRootStage() throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory, fixture)) {
            var plan = await(runtime.service.planRoot(build, LookupBudgets.defaults()));
            fixture.blockedRoot = new CompletableFuture<>();
            var work = runtime.service.retainRoot(select(plan)).toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fixture.blockedRequest == null && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
            assertNotNull(fixture.blockedRequest);
            assertTrue(work.cancel(true));
            while (!fixture.blockedRoot.isCancelled() && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
            assertTrue(fixture.blockedRoot.isCancelled());
            assertTrue(
                    runtime.catalog
                            .findVerifiedObject(
                                    build.snapshotId(),
                                    DefaultFileLookupService.ROOT_ROLE,
                                    CancellationToken.none())
                            .isEmpty());
        }
        assertEquals(1, objectCount(directory));
        try (var stages = Files.list(directory.resolve("store/work"))) {
            assertEquals(0, stages.count());
        }
    }

    @Test
    void selectedEncodingPageTamperAndCombinedMetadataWireBudgetFailWithoutRootFallback()
            throws Exception {
        var tampered = new RootLookupFixture();
        tampered.corruptEncodingPage = true;
        var build = tampered.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory.resolve("tampered"), tampered)) {
            assertCode(
                    FileLookupException.Code.INTEGRITY_FAILURE,
                    () -> await(runtime.service.planRoot(build, LookupBudgets.defaults())));
            assertTrue(
                    tampered.requests.stream()
                            .noneMatch(request -> request.encodedKey().equals(tampered.rootEKey)));
        }
        var limited = new RootLookupFixture();
        var b = LookupBudgets.defaults();
        var budgets =
                new LookupBudgets(
                        512,
                        512,
                        b.maximumMetadataRequests(),
                        b.maximumMetadataDecodedBytes(),
                        b.maximumIndexBytes(),
                        b.maximumPageBytes(),
                        b.maximumCandidates(),
                        b.maximumRootEncodedBytes(),
                        b.maximumRootDecodedBytes(),
                        b.maximumHeaderBytes(),
                        b.maximumChunks(),
                        b.maximumEncodedChunkBytes(),
                        b.maximumDecodedChunkBytes(),
                        b.maximumDecodedWorkBytes(),
                        b.maximumSelectedIds(),
                        b.maximumMatches(),
                        b.maximumRootRecords());
        var limitedBuild = limited.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory.resolve("wire"), limited)) {
            assertCode(
                    FileLookupException.Code.BUDGET_EXCEEDED,
                    () -> await(runtime.service.planRoot(limitedBuild, budgets)));
            long received =
                    limited.config.length
                            + limited.requests.stream().mapToLong(request -> request.count()).sum();
            assertTrue(received <= 512);
            assertTrue(
                    limited.requests.stream()
                            .noneMatch(request -> request.encodedKey().equals(limited.rootEKey)));
        }
    }

    @Test
    void offlineMissingRootAndRequestedMatchLimitCannotProduceAbsenceClaims() throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var runtime = new Runtime(directory, fixture)) {
            var plan = await(runtime.service.planRoot(build, LookupBudgets.defaults()));
            var root = await(runtime.service.retainRoot(select(plan)));
            fixture.rejectNetwork = true;
            var b = LookupBudgets.defaults();
            var limited =
                    new LookupBudgets(
                            b.maximumConfigurationBytes(),
                            b.maximumMetadataWireBytes(),
                            b.maximumMetadataRequests(),
                            b.maximumMetadataDecodedBytes(),
                            b.maximumIndexBytes(),
                            b.maximumPageBytes(),
                            b.maximumCandidates(),
                            b.maximumRootEncodedBytes(),
                            b.maximumRootDecodedBytes(),
                            b.maximumHeaderBytes(),
                            b.maximumChunks(),
                            b.maximumEncodedChunkBytes(),
                            b.maximumDecodedChunkBytes(),
                            b.maximumDecodedWorkBytes(),
                            b.maximumSelectedIds(),
                            1,
                            b.maximumRootRecords());
            assertCode(
                    FileLookupException.Code.BUDGET_EXCEEDED,
                    () -> await(runtime.service.lookupOffline(build, Set.of(42L, 999L), limited)));
            String digest = root.object().digest().value();
            Files.delete(
                    directory
                            .resolve("store/objects")
                            .resolve(digest.substring(0, 2))
                            .resolve(digest.substring(2)));
            assertCode(
                    FileLookupException.Code.MISSING_LOCAL,
                    () ->
                            await(
                                    runtime.service.lookupOffline(
                                            build, Set.of(999L), LookupBudgets.defaults())));
            assertEquals(2, runtime.catalog.listReferences(build.snapshotId()).size());
        }
    }
}
