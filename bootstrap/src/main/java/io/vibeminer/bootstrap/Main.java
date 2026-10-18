package io.vibeminer.bootstrap;

import io.vibeminer.application.DiscoveryService;
import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.application.DefaultWorkspaceService;
import io.vibeminer.infrastructure.workspace.AppDataDirectory;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.discovery.OfficialDiscoveryService;
import io.vibeminer.infrastructure.discovery.OfficialProductDiscoveryService;
import io.vibeminer.desktop.AtlasApplication;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Composition root; launching never discovers game installations or starts network requests. */
public final class Main {
    private Main() { }
    public static void main(String[] args) {
        if (List.of(args).contains("--self-test")) {
            var builds = FixtureDiscovery.builds();
            if (builds.size() != 2 || builds.getFirst().snapshotId().equals(builds.getLast().snapshotId())) {
                throw new IllegalStateException("Identity self-test failed");
            }
            verifyMetadataRuntime(builds.getFirst());
            System.out.println("VibeMiner self-test OK (bundled runtime; SQLite metadata; synthetic identity; no network)");
            return;
        }
        var official = new OfficialDiscoveryService();
        DiscoveryService discovery = new DiscoveryService() {
            @Override public List<BuildSnapshot> fixtureBuilds() { return FixtureDiscovery.builds(); }
            @Override public CompletionStage<List<BuildSnapshot>> refresh(String productId, String region) {
                return official.refresh(productId, region);
            }
        };
        var workspaceExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
                var thread = new Thread(runnable, "vibeminer-workspaces"); thread.setDaemon(true); return thread;
            });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            workspaceExecutor.shutdown();
            try { if (!workspaceExecutor.awaitTermination(5, TimeUnit.SECONDS)) workspaceExecutor.shutdownNow(); }
            catch (InterruptedException interrupted) { workspaceExecutor.shutdownNow(); Thread.currentThread().interrupt(); }
        }, "vibeminer-shutdown"));
        var workspaceService = new DefaultWorkspaceService(new SqliteWorkspaceRepository(
            AppDataDirectory.resolve().resolve("catalog.sqlite")), workspaceExecutor, Clock.systemUTC());
        AtlasApplication.configure(discovery, workspaceService, new OfficialProductDiscoveryService(), List.of(args).contains("--fixtures"));
        javafx.application.Application.launch(AtlasApplication.class, args);
    }
    private static void verifyMetadataRuntime(BuildSnapshot build) {
        java.nio.file.Path temporary = null;
        try {
            temporary = java.nio.file.Files.createTempDirectory("vibeminer-package-selftest-");
            var database = temporary.resolve("catalog.sqlite");
            var repository = new SqliteWorkspaceRepository(database);
            var workspace = io.vibeminer.domain.Workspace.create(build, "enUS", "Package fixture", java.time.Instant.EPOCH);
            repository.save(workspace);
            if (!new SqliteWorkspaceRepository(database).find(workspace.id()).orElseThrow().equals(workspace)) {
                throw new IllegalStateException("Bundled SQLite metadata self-test failed");
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot create package self-test storage", failure);
        } finally {
            if (temporary != null) {
                try (var files = java.nio.file.Files.walk(temporary)) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
                } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot clean package self-test storage", failure); }
            }
        }
    }

}
