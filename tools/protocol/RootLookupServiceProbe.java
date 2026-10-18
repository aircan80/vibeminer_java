package io.vibeminer.infrastructure.lookup;

import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit development acceptance diagnostic; never invoked by normal builds. */
public final class RootLookupServiceProbe {
    static final long MIB = 1L << 20;
    static final String BUILD = "88d5e8ef05ec5a8446cdec93d142d7c6";
    static final String CDN = "f065eade5276a5ac05606e60a4c5a1f7";
    static final String ROOT = "1051943fc8cb91a3c88066dc946afc3e";
    static final LookupBudgets LIMITS =
            new LookupBudgets(
                    MIB,
                    8 * MIB,
                    64,
                    32 * MIB,
                    8 << 20,
                    64 << 10,
                    32,
                    52 * MIB,
                    128 * MIB,
                    1 << 20,
                    4096,
                    4 << 20,
                    4 << 20,
                    256 * MIB,
                    32,
                    4096,
                    20_000_000);

    static final class Budget {
        final long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        long reserved;
        int requests;

        synchronized void reserve(long bytes) {
            check();
            if (bytes < 1 || bytes > 64 * MIB - reserved || requests >= 300)
                throw new IllegalStateException("Global transfer budget exhausted");
            reserved += bytes;
            requests++;
        }

        synchronized void settle(long reservation, long actual) {
            if (actual < 0 || actual > reservation) throw new AssertionError("Invalid settlement");
            reserved -= reservation - actual;
        }

        void check() {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException("Five-minute deadline");
        }

        <T> T await(CompletionStage<T> future) throws Exception {
            check();
            var task = future.toCompletableFuture();
            try {
                return task.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                task.cancel(true);
                throw failure;
            }
        }
    }

    static final class Runtime implements AutoCloseable {
        final FileObjectStore store;
        final SqliteBuildObjectCatalog catalog;
        final OfficialFileLookupGateway gateway;
        final ExecutorService jobs = Executors.newFixedThreadPool(4);
        final ExecutorService acquisitions = Executors.newFixedThreadPool(4);
        final DefaultFileLookupService service;

        Runtime(Path directory, LookupTransport transport) {
            store = new FileObjectStore(directory.resolve("store"));
            catalog =
                    new SqliteBuildObjectCatalog(
                            new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
            gateway = new OfficialFileLookupGateway(store, transport, Clock.systemUTC());
            service = new DefaultFileLookupService(gateway, catalog, store, jobs, acquisitions);
        }

        public void close() {
            service.close();
            jobs.shutdownNow();
            acquisitions.shutdownNow();
            try {
                if (!jobs.awaitTermination(10, TimeUnit.SECONDS)
                        || !acquisitions.awaitTermination(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Workers did not stop");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } finally {
                gateway.close();
                store.close();
            }
        }
    }

    static LookupTransport forbidden() {
        return new LookupTransport() {
            public CompletableFuture<AcquiredBytes> configuration(CdnRequest r) {
                throw new AssertionError("Offline configuration request");
            }

            public CompletableFuture<AcquiredRange> range(CdnRangeRequest r) {
                throw new AssertionError("Offline range request");
            }
        };
    }

    static LookupTransport supervised(HttpClient client, Budget budget) {
        var configs = new PinnedCdnTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
        var ranges = new PinnedCdnRangeTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
        return supervised(
                new LookupTransport() {
                    public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
                        return configs.fetch(request);
                    }

                    public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
                        return ranges.fetch(request);
                    }
                },
                budget);
    }

    static LookupTransport supervised(LookupTransport delegate, Budget budget) {
        return new LookupTransport() {
            public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
                budget.reserve(request.maximumBytes());
                var wire = delegate.configuration(request);
                var result =
                        wire.thenApply(
                                value -> {
                                    budget.settle(request.maximumBytes(), value.bytes().length);
                                    budget.check();
                                    return value;
                                });
                result.whenComplete(
                        (v, f) -> {
                            if (result.isCancelled()) wire.cancel(true);
                        });
                return result;
            }

            public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
                budget.reserve(request.count());
                var wire = delegate.range(request);
                var result =
                        wire.thenApply(
                                value -> {
                                    budget.check();
                                    return value;
                                });
                result.whenComplete(
                        (v, f) -> {
                            if (result.isCancelled()) wire.cancel(true);
                        });
                return result;
            }
        };
    }

    static SourceDocument metadata(HttpClient client, Budget budget, String name) throws Exception {
        String url = "https://us.version.battle.net/v2/products/wow/" + name;
        budget.reserve(MIB + 1);
        var request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept-Encoding", "identity")
                        .GET()
                        .build();
        var response =
                budget.await(client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()));
        var encodings = response.headers().allValues("Content-Encoding");
        if (!response.uri().equals(request.uri())
                || response.statusCode() != 200
                || encodings.size() > 1
                || (!encodings.isEmpty() && !encodings.getFirst().equalsIgnoreCase("identity"))) {
            response.body().close();
            throw new IllegalStateException(
                    "Invalid official metadata status/route/representation");
        }
        byte[] bytes;
        try (var stream = response.body();
                var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            var body = reader.submit(() -> stream.readNBytes((int) MIB + 1));
            try {
                bytes =
                        body.get(
                                Math.min(
                                        Duration.ofSeconds(15).toNanos(),
                                        budget.deadline - System.nanoTime()),
                                TimeUnit.NANOSECONDS);
            } catch (Exception failure) {
                stream.close();
                body.cancel(true);
                throw failure;
            }
        }
        if (response.statusCode() != 200
                || bytes.length > MIB
                || !response.headers()
                        .firstValue("Content-Encoding")
                        .orElse("identity")
                        .equalsIgnoreCase("identity"))
            throw new IllegalStateException("Invalid official metadata response");
        budget.settle(MIB + 1, bytes.length);
        budget.check();
        return new SourceDocument(url, Instant.now(), bytes);
    }

    static BuildSnapshot official(HttpClient client, Budget budget) throws Exception {
        var versions = metadata(client, budget, "versions");
        var cdns = metadata(client, budget, "cdns");
        // The gateway independently reparses the exact original documents and pinned row.
        return new BuildSnapshot(
                "wow",
                "wow",
                "Published",
                "us",
                "12.1.5.70448",
                BUILD,
                CDN,
                versions.capturedAt(),
                false,
                new BuildEvidence(
                        "70448", "53020d32e1a25648c8e1eafd5771935f", List.of(versions, cdns)));
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void run(
            Path directory,
            BuildSnapshot build,
            LookupTransport source,
            String selectedKey,
            Set<Long> ids,
            boolean live,
            Budget budget)
            throws Exception {
        VerifiedSourceAlias retained;
        try (var runtime = new Runtime(directory, source)) {
            var plan = budget.await(runtime.service.planRoot(build, LIMITS));
            System.out.println(
                    "PLAN exactSnapshot="
                            + build.snapshotId()
                            + " ckey="
                            + plan.contentKey()
                            + " decoded="
                            + plan.decodedBytes()
                            + " candidates="
                            + plan.candidates());
            if (!live) {
                int before = budget.requests;
                var forged =
                        new RootPlan(
                                plan.sessionId(),
                                plan.build(),
                                plan.configuration(),
                                "0".repeat(32),
                                plan.decodedBytes(),
                                plan.candidates(),
                                plan.budgets());
                try {
                    budget.await(
                            runtime.service.retainRoot(
                                    new RootSelectionPlan(forged, plan.candidates().getFirst())));
                    throw new AssertionError("Forged plan accepted");
                } catch (ExecutionException expected) {
                    require(
                            expected.getCause() instanceof FileLookupException failure
                                    && failure.code() == FileLookupException.Code.INVALID_PLAN,
                            "Wrong forged-plan failure");
                }
                require(budget.requests == before, "Forged plan performed transfer");
            }
            var selected =
                    plan.candidates().stream()
                            .filter(c -> c.encodingKey().equals(selectedKey))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Known selected candidate missing"));
            require(selected.encodedBytes() > 0, "Selected size must be authenticated");
            if (live)
                require(
                        selected.encodedBytes() == 50_651_441 && plan.decodedBytes() == 67_292_495,
                        "Pinned root size changed");
            require(
                    3 * plan.decodedBytes() + LIMITS.maximumMetadataDecodedBytes() <= 256 * MIB,
                    "Aggregate three root passes plus metadata exceeds decode budget");
            System.out.println(
                    "SELECT explicit ekey=" + selectedKey + " encoded=" + selected.encodedBytes());
            retained =
                    budget.await(runtime.service.retainRoot(new RootSelectionPlan(plan, selected)));
            require(
                    runtime.catalog.listReferences(build.snapshotId()).size() == 2,
                    "Expected config/root membership only");
        }
        try (var runtime = new Runtime(directory, forbidden())) {
            var reopened = budget.await(runtime.service.lookupOffline(build, ids, LIMITS));
            require(
                    reopened.root().equals(retained),
                    "Fresh offline instances changed root receipt");
            if (!live) {
                require(
                        reopened.variants().size() == 2
                                && reopened.absentIds().equals(Set.of(999L)),
                        "Synthetic variant/absence scope changed");
                require(
                        reopened.variants().stream()
                                        .anyMatch(
                                                v ->
                                                        v.nameHash()
                                                                .equals(
                                                                        Optional.of(
                                                                                "0000000000000000")))
                                && reopened.variants().stream()
                                        .anyMatch(v -> v.nameHash().isEmpty()),
                        "Synthetic present-zero and unnamed distinction lost");
            }
            require(!reopened.variants().isEmpty(), "No variants");
            if (live) {
                require(reopened.variants().size() == 12, "Expected all twelve variants");
                require(
                        reopened.variants().stream().filter(v -> v.fileDataId() == 841626).count()
                                == 1,
                        "Expected one Item membership");
                require(
                        reopened.variants().stream().filter(v -> v.fileDataId() == 1572924).count()
                                == 11,
                        "Expected eleven sparse memberships");
            }
            try (var paths = Files.walk(directory.resolve("store/objects"))) {
                require(
                        paths.filter(Files::isRegularFile).count() == 2,
                        "Expected exactly two physical objects");
            }
            System.out.println(
                    "VERIFIED root=" + reopened.root() + " records=" + reopened.scannedRecords());
            reopened.variants().forEach(v -> System.out.println("VARIANT " + v));
            System.out.println("PASS fresh offline instances; zero HTTP; physicalObjects=2");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("--self-test") || args[0].equals("--live")))
            throw new IllegalArgumentException("Use --self-test or explicitly authorized --live");
        var budget = new Budget();
        Path directory = Files.createTempDirectory("vibeminer-root-service-probe-");
        try {
            if (args[0].equals("--self-test")) {
                var boundary = new Budget();
                boundary.reserve(64 * MIB);
                try {
                    boundary.reserve(1);
                    throw new AssertionError("Wire cap ignored");
                } catch (IllegalStateException expected) {
                }
                var requests = new Budget();
                for (int i = 0; i < 300; i++) requests.reserve(1);
                try {
                    requests.reserve(1);
                    throw new AssertionError("Request cap ignored");
                } catch (IllegalStateException expected) {
                }
                var fixture = new RootLookupProbeFixture();
                run(
                        directory,
                        fixture.build("us.cdn.blizzard.com", "synthetic"),
                        supervised(fixture, budget),
                        fixture.rootEKey,
                        Set.of(42L, 999L),
                        false,
                        budget);
                var corrupt = new RootLookupProbeFixture(true, false);
                try (var runtime =
                        new Runtime(directory.resolve("negative"), supervised(corrupt, budget))) {
                    var build = corrupt.build("us.cdn.blizzard.com", "wrong-ckey");
                    var plan = budget.await(runtime.service.planRoot(build, LIMITS));
                    try {
                        budget.await(
                                runtime.service.retainRoot(
                                        new RootSelectionPlan(plan, plan.candidates().getFirst())));
                        throw new AssertionError("Wrong CKey retained");
                    } catch (ExecutionException expected) {
                        require(
                                expected.getCause() instanceof FileLookupException failure
                                        && failure.code()
                                                == FileLookupException.Code.INTEGRITY_FAILURE,
                                "Wrong CKey failure type");
                    }
                    require(
                            runtime.catalog.listReferences(build.snapshotId()).size() == 1,
                            "Failed root gained membership");
                    try (var paths = Files.walk(directory.resolve("negative/store/objects"))) {
                        require(
                                paths.filter(Files::isRegularFile).count() == 1,
                                "Failed root published object");
                    }
                }
            } else {
                try (var client =
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build()) {
                    run(
                            directory,
                            official(client, budget),
                            supervised(client, budget),
                            ROOT,
                            Set.of(841626L, 1572924L),
                            true,
                            budget);
                }
            }
            System.out.println(
                    "TRANSFER conservativeBytes="
                            + budget.reserved
                            + " requests="
                            + budget.requests);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
            System.out.println("Temporary payloads and catalog removed");
        }
    }
}
