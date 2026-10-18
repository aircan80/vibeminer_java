package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapWorkflowIntegrationTest {
    @TempDir Path directory;
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String CDN_KEY = "a".repeat(32);
    static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String hash(String algorithm, byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    static byte[] manifest() throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeBytes("IN"); out.writeByte(1); out.writeByte(16); out.writeShort(1); out.writeInt(1);
        out.write(text("Windows")); out.writeByte(0); out.writeShort(1); out.writeByte(0x80);
        out.write(text("Interface/readme.txt")); out.writeByte(0); out.write(new byte[16]); out.writeInt(42);
        return bytes.toByteArray();
    }
    static byte[] encode(byte[] decoded) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeBytes("BLTE"); out.writeInt(0); out.writeByte('N'); out.write(decoded); return bytes.toByteArray();
    }
    static BuildSnapshot build(byte[] config) {
        String key = hash("MD5", config);
        String versions = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|" + key + "|" + CDN_KEY + "|1.0\n";
        String cdns = "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\nus|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/configs/data\n";
        return new BuildSnapshot("wow", "Synthetic", "Published", "us", "1.0", key, CDN_KEY, NOW, false,
                new BuildEvidence("", "", List.of(new SourceDocument("https://us.version.battle.net/v2/products/wow/versions", NOW, text(versions)),
                new SourceDocument("https://us.version.battle.net/v2/products/wow/cdns", NOW, text(cdns)))));
    }
    static BuildSnapshot withHostAndVersion(BuildSnapshot original, String host, String version) {
        var documents = original.evidence().documents().stream().map(document -> new SourceDocument(document.sourceUrl(), document.capturedAt(),
                text(new String(document.bytes(), StandardCharsets.UTF_8).replace("us.cdn.blizzard.com", host).replace("|1.0\n", "|" + version + "\n")))).toList();
        return new BuildSnapshot(original.productId(), original.editionLabel(), original.channel(), original.region(), version, original.buildConfig(), original.cdnConfig(), original.observedAt(), original.fixture(),
                new BuildEvidence("", "", documents));
    }
    static byte[] config(byte[] decoded, byte[] encoded) {
        return text("install = " + hash("MD5", decoded) + " " + hash("MD5", encoded) + "\ninstall-size = " + decoded.length + " " + encoded.length + "\n");
    }
    static DefaultBootstrapService service(FileObjectStore store, BuildObjectCatalog catalog, BootstrapTransfer transport, Executor executor, Clock clock) {
        return new DefaultBootstrapService(new OfficialBootstrapArtifactGateway(store, transport, clock), catalog, store, executor,
                command -> Thread.ofVirtual().name("install-test-acquisition").start(command));
    }
    static final class MemoryCatalog implements BuildObjectCatalog {
        final Map<String, BuildObjectReference> references = new HashMap<>(); final Map<ResolvedSourceIdentity, StoredObject> aliases = new HashMap<>();
        final FileObjectStore store;
        MemoryCatalog(FileObjectStore store) { this.store = store; }
        public void registerSnapshot(BuildSnapshot build) {}
        public synchronized Optional<VerifiedSourceAlias> findVerifiedSourceAlias(ResolvedSourceIdentity identity, CancellationToken token) {
            return references.values().stream().filter(ref -> ref.source().equals(identity) && store.verify(ref.object().digest(), token).isPresent())
                    .map(ref -> new VerifiedSourceAlias(ref.source(), ref.object(), ref.evidence())).findFirst();
        }
        public synchronized Optional<StoredObject> findVerifiedAlias(ResolvedSourceIdentity identity, CancellationToken token) {
            var object = aliases.get(identity); return object == null ? Optional.empty() : store.verify(object.digest(), token);
        }
        public synchronized BuildObjectReference attachVerifiedObject(String snapshot, String role, ResolvedSourceIdentity source, StoredObject object, SourceVerificationEvidence evidence, CancellationToken token) {
            token.throwIfCancellationRequested(); var ref = new BuildObjectReference(snapshot, role, source, object, evidence);
            references.put(snapshot + role, ref); aliases.put(source, object); return ref;
        }
        public synchronized List<BuildObjectReference> listReferences(String snapshot) { return references.values().stream().filter(ref -> ref.snapshotId().equals(snapshot)).toList(); }
        public synchronized Optional<BuildObjectReference> findVerifiedObject(String snapshot, String role, CancellationToken token) {
            var ref = references.get(snapshot + role); return ref == null || store.verify(ref.object().digest(), token).isEmpty() ? Optional.empty() : Optional.of(ref);
        }
    }
    @Test void explicitPlanThenDownloadAndOfflineReopen() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
        var build = build(config); var requests = new ArrayList<CdnRequest>();
        BootstrapTransfer transport = request -> {
            requests.add(request); byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
            return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), request.kind() == CdnRequest.Kind.CONFIG));
        };
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            try (var service = service(store, catalog, transport, executor, Clock.fixed(NOW, ZoneOffset.UTC))) {
                assertTrue(requests.isEmpty());
                var plan = service.inspectConfiguration(build).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(1, requests.size()); assertEquals(CdnRequest.Kind.CONFIG, requests.getFirst().kind());
                assertEquals(encoded.length, plan.encodedBytes()); assertEquals(decoded.length, plan.decodedBytes());
                var inspection = service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(2, requests.size()); assertEquals("Interface/readme.txt", inspection.entries().getFirst().name());
                assertEquals("Windows", inspection.entries().getFirst().tags().getFirst().name());
            }
            try (var offline = service(store, catalog, request -> { throw new AssertionError("Offline attempted network"); }, executor, Clock.systemUTC())) {
                var inspection = offline.reopen(build).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(1, inspection.entries().size()); assertEquals(encoded.length, inspection.object().length());
            }
        }
    }
    @Test void corruptEncodedObjectNeverPublishesMembership() throws Exception {
        byte[] decoded = manifest(), valid = encode(decoded), config = config(decoded, valid), corrupt = valid.clone(); corrupt[corrupt.length - 1] ^= 1;
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            BootstrapTransfer transport = request -> {
                byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : corrupt;
                return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), request.kind() == CdnRequest.Kind.CONFIG));
            };
            try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                var plan = service.inspectConfiguration(build(config)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var failure = assertThrows(ExecutionException.class, () -> service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(BootstrapException.Reason.INTEGRITY, ((BootstrapException) failure.getCause()).reason());
                assertTrue(catalog.findVerifiedObject(plan.build().snapshotId(), "INSTALL_MANIFEST", CancellationToken.none()).isEmpty());
            }
        }
    }
    @Test void oversizedInstallAndMissingCapturedSourceFailBeforeContentDownload() throws Exception {
        byte[] config = text("install = " + "1".repeat(32) + " " + "2".repeat(32) + "\ninstall-size = 188000000 100\n");
        AtomicInteger requests = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            BootstrapTransfer transport = request -> { requests.incrementAndGet(); return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, config, hash("SHA-256", config), true)); };
            try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                var build = build(config);
                var failure = assertThrows(ExecutionException.class, () -> service.inspectConfiguration(build).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(BootstrapException.Reason.LIMIT, ((BootstrapException)failure.getCause()).reason()); assertEquals(1, requests.get());
                var missing = new BuildSnapshot("wow", "Unknown", "Published", "us", "1.0", build.buildConfig(), CDN_KEY, NOW, false);
                assertThrows(ExecutionException.class, () -> service.inspectConfiguration(missing).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(1, requests.get());
            }
        }
    }
    @Test void restartWithRealSqliteAndCasReopensWithoutNetwork() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
        var build = build(config); var database = directory.resolve("catalog.sqlite"); var objectRoot = directory.resolve("objects");
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
            BootstrapTransfer transport = request -> {
                byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
                return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), request.kind() == CdnRequest.Kind.CONFIG));
            };
            try (var service = service(store, catalog, transport, executor, Clock.fixed(NOW, ZoneOffset.UTC))) {
                var plan = service.inspectConfiguration(build).toCompletableFuture().get(5, TimeUnit.SECONDS);
                service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
            try (var service = service(store, catalog, request -> { throw new AssertionError("Offline HTTP"); }, executor, Clock.systemUTC())) {
                var inspection = service.reopen(build).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals("Interface/readme.txt", inspection.entries().getFirst().name());
                var repeated = service.download(inspection.plan()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(inspection.object(), repeated.object());
                assertEquals(2, catalog.listReferences(build.snapshotId()).size());
            }
        }
    }
    @Test void cancellingActiveDownloadCancelsWireAndLeavesNoManifestMembership() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
        var wire = new CompletableFuture<AcquiredBytes>(); var fetching = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            BootstrapTransfer transport = request -> {
                if (request.kind() == CdnRequest.Kind.CONFIG) return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, config, hash("SHA-256", config), true));
                fetching.countDown(); return wire;
            };
            try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                var plan = service.inspectConfiguration(build(config)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var download = service.download(plan).toCompletableFuture(); assertTrue(fetching.await(5, TimeUnit.SECONDS));
                assertTrue(download.cancel(true));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!wire.isCancelled() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertTrue(wire.isCancelled());
                assertTrue(catalog.findVerifiedObject(plan.build().snapshotId(), "INSTALL_MANIFEST", CancellationToken.none()).isEmpty());
            }
        }
    }
    @Test void manufacturedPlanAndMissingOfflineCoverageFailExplicitly() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
        AtomicInteger requests = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            BootstrapTransfer transport = request -> { requests.incrementAndGet(); return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, config, hash("SHA-256", config), true)); };
            try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                var build = build(config);
                var missing = assertThrows(ExecutionException.class, () -> service.reopen(build).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(BootstrapException.Reason.MISSING_LOCAL, ((BootstrapException)missing.getCause()).reason()); assertEquals(0, requests.get());
                var plan = service.inspectConfiguration(build).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var forged = new InstallPlan(build, plan.configuration(), "9".repeat(32), plan.encodingKey(), plan.decodedBytes(), plan.encodedBytes());
                var failure = assertThrows(ExecutionException.class, () -> service.download(forged).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(BootstrapException.Reason.INTEGRITY, ((BootstrapException)failure.getCause()).reason()); assertEquals(1, requests.get());
                var evilDocument = new SourceDocument("https://evil.example/fake", plan.configuration().capturedAt(), plan.configuration().bytes());
                var evilPlan = new InstallPlan(build, evilDocument, plan.contentKey(), plan.encodingKey(), plan.decodedBytes(), plan.encodedBytes());
                var evilFailure = assertThrows(ExecutionException.class, () -> service.download(evilPlan).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(BootstrapException.Reason.INTEGRITY, ((BootstrapException)evilFailure.getCause()).reason()); assertEquals(1, requests.get());
                assertTrue(catalog.findVerifiedObject(build.snapshotId(), "INSTALL_MANIFEST", CancellationToken.none()).isEmpty());
            }
        }
    }

    @Test void twoBuildsShareTransferAndOneConsumerMayCancelIndependently() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), configA = config(decoded, encoded);
        byte[] configB = text(new String(configA, StandardCharsets.UTF_8) + "# another exact build\n");
        var buildA = build(configA); var buildB = withHostAndVersion(build(configB), "kr.cdn.blizzard.com", "2.0");
        var wire = new CompletableFuture<AcquiredBytes>(); var requestRef = new java.util.concurrent.atomic.AtomicReference<CdnRequest>();
        AtomicInteger contentRequests = new AtomicInteger();
        var threads = new java.util.concurrent.CopyOnWriteArrayList<Thread>();
        try (var executor = Executors.newFixedThreadPool(2, runnable -> { var thread = new Thread(runnable); threads.add(thread); return thread; });
                var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            BootstrapTransfer transport = request -> {
                if (request.kind() == CdnRequest.Kind.CONFIG) {
                    byte[] bytes = request.key().equals(buildA.buildConfig()) ? configA : configB;
                    return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), true));
                }
                contentRequests.incrementAndGet(); requestRef.set(request); return wire;
            };
            try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                var planA = service.inspectConfiguration(buildA).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var planB = service.inspectConfiguration(buildB).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var first = service.download(planA).toCompletableFuture();
                long firstDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (contentRequests.get() == 0 && System.nanoTime() < firstDeadline) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                assertEquals(1, contentRequests.get());
                var second = service.download(planB).toCompletableFuture();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    long waiters = threads.stream().filter(thread -> Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().equals(DefaultBootstrapService.class.getName()) && frame.getMethodName().equals("await"))).count();
                    if (waiters == 2) break;
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertEquals(2, threads.stream().filter(thread -> Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().equals(DefaultBootstrapService.class.getName()) && frame.getMethodName().equals("await"))).count());
                assertEquals(1, contentRequests.get()); assertTrue(first.cancel(true));
                wire.complete(new AcquiredBytes(requestRef.get(), NOW, encoded, hash("SHA-256", encoded), false));
                var result = second.get(5, TimeUnit.SECONDS);
                assertEquals(1, result.entries().size()); assertTrue(first.isCancelled());
                assertTrue(result.verification().sourceUrl().startsWith("https://us.cdn.blizzard.com/"));
                assertEquals("kr.cdn.blizzard.com", PinnedBuildSource.resolve(result.plan().build()).host());
                assertEquals(1, contentRequests.get());
                assertTrue(catalog.findVerifiedObject(buildA.snapshotId(), "INSTALL_MANIFEST", CancellationToken.none()).isEmpty());
                assertTrue(catalog.findVerifiedObject(buildB.snapshotId(), "INSTALL_MANIFEST", CancellationToken.none()).isPresent());
            }
        }
    }

    @Test void crossHostDurableReusePreservesActualProofAndSkipsConfigurationAndContentHttp() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
        var original = build(config); var other = withHostAndVersion(original, "kr.cdn.blizzard.com", "2.0");
        var database = directory.resolve("catalog.sqlite"); var objectRoot = directory.resolve("objects");
        SourceVerificationEvidence originalProof;
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
            BootstrapTransfer transport = request -> {
                byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
                return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), request.kind() == CdnRequest.Kind.CONFIG));
            };
            try (var service = service(store, catalog, transport, executor, Clock.fixed(NOW, ZoneOffset.UTC))) {
                var plan = service.inspectConfiguration(original).toCompletableFuture().get(5, TimeUnit.SECONDS);
                originalProof = service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS).verification();
            }
        }
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
            try (var service = service(store, catalog, request -> { throw new AssertionError("Identical retained bytes requested again"); }, executor, Clock.systemUTC())) {
                var plan = service.inspectConfiguration(other).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertTrue(plan.configuration().sourceUrl().startsWith("https://us.cdn.blizzard.com/"));
                var result = service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(originalProof, result.verification());
                assertEquals(originalProof, service.reopen(other).toCompletableFuture().get(5, TimeUnit.SECONDS).verification());
                var a = catalog.findVerifiedObject(original.snapshotId(), "INSTALL_MANIFEST", CancellationToken.none()).orElseThrow();
                var b = catalog.findVerifiedObject(other.snapshotId(), "INSTALL_MANIFEST", CancellationToken.none()).orElseThrow();
                assertEquals(a.object(), b.object()); assertEquals(a.evidence(), b.evidence());
            }
        }
    }

    @Test void smallInstallPlanDoesNotScheduleUnrelatedLargeEncodingTable() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded);
        byte[] config = text(new String(config(decoded, encoded), StandardCharsets.UTF_8) + "encoding = " + "3".repeat(32) + " " + "4".repeat(32) + "\nencoding-size = 188000000 188000100\n");
        var requests = new ArrayList<CdnRequest>();
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            BootstrapTransfer transport = request -> {
                requests.add(request); byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
                return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), request.kind() == CdnRequest.Kind.CONFIG));
            };
            try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                var plan = service.inspectConfiguration(build(config)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(1, requests.size());
                service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(2, requests.size()); assertEquals(hash("MD5", encoded), requests.getLast().key());
            }
        }
    }
    @Test void ckeyOnlyInstallIsUnsupportedRatherThanImplicitlyDownloadingEncodingTable() throws Exception {
        byte[] config = text("install = " + "1".repeat(32) + "\ninstall-size = 100 100\nencoding = " + "3".repeat(32) + " " + "4".repeat(32) + "\nencoding-size = 188000000 188000100\n");
        AtomicInteger requests = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(directory)) {
            var catalog = new MemoryCatalog(store);
            BootstrapTransfer transport = request -> { requests.incrementAndGet(); return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, config, hash("SHA-256", config), true)); };
            try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                var failure = assertThrows(ExecutionException.class, () -> service.inspectConfiguration(build(config)).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(BootstrapException.Reason.UNSUPPORTED, ((BootstrapException)failure.getCause()).reason());
                assertEquals(1, requests.get()); assertTrue(catalog.listReferences(build(config).snapshotId()).isEmpty());
            }
        }
    }

    @Test void offlineMissingAndCorruptPhysicalContentRemainDistinctFromReady() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded); var build = build(config);
        for (boolean corrupt : List.of(false, true)) {
            var root = directory.resolve(corrupt ? "corrupt" : "missing"); var database = root.resolve("catalog.sqlite"); var objectRoot = root.resolve("store");
            StoredObject object;
            try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
                var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
                BootstrapTransfer transport = request -> {
                    byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
                    return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), request.kind() == CdnRequest.Kind.CONFIG));
                };
                try (var service = service(store, catalog, transport, executor, Clock.systemUTC())) {
                    var plan = service.inspectConfiguration(build).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    object = service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS).object();
                }
            }
            String digest = object.digest().value(); var physical = objectRoot.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
            if (corrupt) { byte[] damaged = encoded.clone(); damaged[damaged.length - 1] ^= 1; java.nio.file.Files.write(physical, damaged); }
            else java.nio.file.Files.delete(physical);
            try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
                var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
                try (var service = service(store, catalog, request -> { throw new AssertionError("Offline repair must not start HTTP"); }, executor, Clock.systemUTC())) {
                    var failure = assertThrows(ExecutionException.class, () -> service.reopen(build).toCompletableFuture().get(5, TimeUnit.SECONDS));
                    assertEquals(corrupt ? BootstrapException.Reason.INTEGRITY : BootstrapException.Reason.MISSING_LOCAL, ((BootstrapException)failure.getCause()).reason());
                    assertEquals(2, catalog.listReferences(build.snapshotId()).size());
                }
            }
        }
    }

    @Test void restoredCrossHostConfigurationCanAuthorizeTheFirstInstallDownload() throws Exception {
        byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
        var a = build(config); var b = withHostAndVersion(a, "kr.cdn.blizzard.com", "2.0");
        var database = directory.resolve("catalog.sqlite"); var objectRoot = directory.resolve("store");
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
            BootstrapTransfer configOnly = request -> {
                assertEquals(CdnRequest.Kind.CONFIG, request.kind()); assertEquals("us.cdn.blizzard.com", request.source().host());
                return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, config, hash("SHA-256", config), true));
            };
            try (var service = service(store, catalog, configOnly, executor, Clock.fixed(NOW, ZoneOffset.UTC))) {
                service.inspectConfiguration(a).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(1, catalog.listReferences(a.snapshotId()).size());
            }
        }
        AtomicInteger downloads = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4); var store = new FileObjectStore(objectRoot)) {
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(new io.vibeminer.infrastructure.persistence.SqliteCatalog(database), store);
            BootstrapTransfer installOnly = request -> {
                assertEquals(CdnRequest.Kind.LOOSE_DATA, request.kind()); assertEquals("kr.cdn.blizzard.com", request.source().host()); downloads.incrementAndGet();
                return CompletableFuture.completedFuture(new AcquiredBytes(request, NOW, encoded, hash("SHA-256", encoded), false));
            };
            try (var service = service(store, catalog, installOnly, executor, Clock.fixed(NOW, ZoneOffset.UTC))) {
                var plan = service.inspectConfiguration(b).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertTrue(plan.configuration().sourceUrl().startsWith("https://us.cdn.blizzard.com/")); assertEquals(0, downloads.get());
                var result = service.download(plan).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertTrue(result.verification().sourceUrl().startsWith("https://kr.cdn.blizzard.com/")); assertEquals(1, downloads.get());
                assertEquals(result.verification(), service.reopen(b).toCompletableFuture().get(5, TimeUnit.SECONDS).verification());
            }
        }
    }

}
