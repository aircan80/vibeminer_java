package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.application.DefaultWorkspaceService;
import io.vibeminer.application.WorkspaceService;
import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.bootstrap.DefaultBootstrapService;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisition.AcquiredBytes;
import io.vibeminer.infrastructure.acquisition.AcquisitionException;
import io.vibeminer.infrastructure.acquisition.CdnRequest;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Tool-only transport seam in the adapter package; no production visibility is widened. */
public final class InstallFixtureBackend implements AutoCloseable {
    public enum TransferMode { SUCCESS, UNAVAILABLE, HOLD }
    public static final String FIRST_NAME = "Interface/Research/Field-Notes-about-the-Moonlit-Surveyor-and-other-unreleased-collectibles.txt";
    public static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private final byte[] decoded;
    private final byte[] encoded;
    private final byte[] firstConfiguration;
    private final byte[] secondConfiguration;
    private final Path contentRoot;
    private final FileObjectStore store;
    private final SqliteBuildObjectCatalog catalog;
    private final ExecutorService operations = Executors.newFixedThreadPool(4);
    private final ExecutorService transfers = Executors.newFixedThreadPool(2);
    private final ExecutorService workspaceOperations = Executors.newSingleThreadExecutor();
    private final BootstrapService bootstrap;
    private final WorkspaceService workspaces;
    private final List<BuildSnapshot> builds;
    private final boolean offline;
    public final AtomicInteger transportCalls = new AtomicInteger();
    public final AtomicInteger configurationCalls = new AtomicInteger();
    public final AtomicInteger contentCalls = new AtomicInteger();
    private volatile TransferMode mode = TransferMode.SUCCESS;
    private volatile CompletableFuture<AcquiredBytes> lastWire;

    public InstallFixtureBackend(Path root, boolean offline) throws Exception {
        this.offline = offline;
        decoded = manifest();
        encoded = encode(decoded);
        firstConfiguration = configuration(decoded, encoded, "A");
        secondConfiguration = configuration(decoded, encoded, "B");
        builds = List.of(build(firstConfiguration, "DEMO-INSTALL-A"), build(secondConfiguration, "DEMO-INSTALL-B"));
        contentRoot = root.resolve("content");
        store = new FileObjectStore(contentRoot);
        var database = new SqliteCatalog(root.resolve("catalog.sqlite"));
        var repository = new SqliteWorkspaceRepository(database);
        if (!offline) {
            repository.save(Workspace.create(builds.getFirst(), "enUS", "Synthetic install investigation A", NOW));
            repository.save(Workspace.create(builds.getLast(), "deDE", "Synthetic install investigation B", NOW));
        }
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        catalog = new SqliteBuildObjectCatalog(database, store);
        workspaces = new DefaultWorkspaceService(repository, workspaceOperations, clock);
        var gateway = new OfficialBootstrapArtifactGateway(store, this::fetch, clock);
        bootstrap = new DefaultBootstrapService(gateway, catalog, store, operations, transfers);
    }

    public List<BuildSnapshot> builds() {return builds;}
    public WorkspaceService workspaces() {return workspaces;}
    public BootstrapService bootstrap() {return bootstrap;}
    public int encodedBytes() {return encoded.length;}
    public int decodedBytes() {return decoded.length;}
    public void mode(TransferMode value) {mode = value;}
    public boolean wireCancelled() {return lastWire != null && lastWire.isCancelled();}
    public int references(int build) {return catalog.listReferences(builds.get(build).snapshotId()).size();}
    public void corruptRetainedList() throws Exception {
        byte[] changed = encoded.clone();
        changed[changed.length - 1] ^= 1;
        String digest = hash("SHA-256", encoded);
        Files.write(contentRoot.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2)), changed);
    }

    private CompletableFuture<AcquiredBytes> fetch(CdnRequest request) {
        transportCalls.incrementAndGet();
        if (offline) throw new AssertionError("Offline UI invoked transport: " + request.kind());
        if (request.kind() == CdnRequest.Kind.CONFIG) {
            configurationCalls.incrementAndGet();
            byte[] bytes = request.key().equals(builds.getFirst().buildConfig()) ? firstConfiguration
                    : request.key().equals(builds.getLast().buildConfig()) ? secondConfiguration : null;
            if (bytes == null) throw new AssertionError("Unexpected configuration identity");
            return CompletableFuture.completedFuture(acquired(request, bytes, true));
        }
        if (request.kind() != CdnRequest.Kind.LOOSE_DATA || !request.key().equals(hash("MD5", encoded))) {
            throw new AssertionError("Unexpected content scope; encoding tables and game files are forbidden");
        }
        contentCalls.incrementAndGet();
        if (mode == TransferMode.UNAVAILABLE) return CompletableFuture.failedFuture(
                new AcquisitionException(AcquisitionException.Reason.UNAVAILABLE, "Synthetic unavailable list"));
        if (mode == TransferMode.HOLD) {
            lastWire = new CompletableFuture<>();
            return lastWire;
        }
        lastWire = null;
        return CompletableFuture.completedFuture(acquired(request, encoded, false));
    }

    private static AcquiredBytes acquired(CdnRequest request, byte[] bytes, boolean config) {
        return new AcquiredBytes(request, NOW, bytes, hash("SHA-256", bytes), config);
    }
    private static BuildSnapshot build(byte[] config, String version) {
        String key = hash("MD5", config), cdn = "a".repeat(32);
        String versions = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|" + key + "|" + cdn + "|" + version + "\n";
        String routes = "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\nus|tpr/wow|us.cdn.blizzard.com|https://us.cdn.blizzard.com/|tpr/configs/data\n";
        return new BuildSnapshot("wow", "Synthetic WoW edition", "Fixture", "us", version, key, cdn, NOW, true,
                new BuildEvidence("", "", List.of(
                        new SourceDocument("https://us.version.battle.net/v2/products/wow/versions", NOW, text(versions)),
                        new SourceDocument("https://us.version.battle.net/v2/products/wow/cdns", NOW, text(routes)))));
    }
    private static byte[] configuration(byte[] decoded, byte[] encoded, String label) {
        return text("install = " + hash("MD5", decoded) + " " + hash("MD5", encoded)
                + "\ninstall-size = " + decoded.length + " " + encoded.length + "\n# synthetic build " + label + "\n");
    }
    private static byte[] manifest() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeBytes("IN"); output.writeByte(1); output.writeByte(16); output.writeShort(3); output.writeInt(3);
        tag(output, "Windows", 1, 0xa0);
        tag(output, "macOS", 1, 0x40);
        tag(output, "enUS", 2, 0xc0);
        entry(output, FIRST_NAME, 0x11, 42);
        entry(output, "Sound/Research/Field-Notes.ogg", 0x22, 2048);
        entry(output, "Interface/Research/Crafting-Reagent-Notes.txt", 0x33, 512);
        return bytes.toByteArray();
    }
    private static void tag(DataOutputStream output, String name, int type, int bits) throws Exception {
        output.write(text(name)); output.writeByte(0); output.writeShort(type); output.writeByte(bits);
    }
    private static void entry(DataOutputStream output, String name, int key, int size) throws Exception {
        output.write(text(name)); output.writeByte(0);
        byte[] digest = new byte[16]; Arrays.fill(digest, (byte) key); output.write(digest); output.writeInt(size);
    }
    private static byte[] encode(byte[] decoded) throws Exception {
        var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
        output.writeBytes("BLTE"); output.writeInt(0); output.writeByte('N'); output.write(decoded);
        return bytes.toByteArray();
    }
    private static byte[] text(String value) {return value.getBytes(StandardCharsets.UTF_8);}
    private static String hash(String algorithm, byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));}
        catch (Exception failure) {throw new IllegalStateException(failure);}
    }
    @Override public void close() throws Exception {
        bootstrap.close();
        if (lastWire != null) lastWire.cancel(true);
        operations.shutdownNow(); transfers.shutdownNow(); workspaceOperations.shutdownNow();
        if (!operations.awaitTermination(10, TimeUnit.SECONDS) || !transfers.awaitTermination(10, TimeUnit.SECONDS)
                || !workspaceOperations.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture workers did not retire");
        store.close();
    }
}
