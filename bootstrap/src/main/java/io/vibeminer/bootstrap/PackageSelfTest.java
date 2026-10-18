package io.vibeminer.bootstrap;

import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;

/** Explicit package verification uses disposable synthetic storage, never user archives or network. */
final class PackageSelfTest {
    private PackageSelfTest() { }
    static void run() {
        var builds = FixtureDiscovery.builds();
        if (builds.size() != 2 || builds.getFirst().snapshotId().equals(builds.getLast().snapshotId())) {
            throw new IllegalStateException("Identity self-test failed");
        }
        verifyMetadataRuntime(builds.getFirst());
        System.out.println("VibeMiner self-test OK (bundled runtime; SQLite/shared content; synthetic identity; no network)");
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
            verifyContentRuntime(temporary, build);
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

    private static void verifyContentRuntime(java.nio.file.Path directory, BuildSnapshot build) {
        var bytes = "VibeMiner synthetic package object".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String checksum;
        try { checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var identity = new io.vibeminer.domain.ResolvedSourceIdentity("package.synthetic", "shared-fixture", "whole-sha256-v1");
        var evidence = new io.vibeminer.domain.SourceVerificationEvidence(identity.verificationSemantics(),
            "https://example.invalid/vibeminer-synthetic-package-fixture", java.time.Instant.EPOCH);
        io.vibeminer.domain.StoredObject retained;
        try (var store = new io.vibeminer.infrastructure.storage.FileObjectStore(directory.resolve("content"))) {
            retained = store.publish(new java.io.ByteArrayInputStream(bytes), new io.vibeminer.domain.ExpectedObjectChecksum(
                io.vibeminer.domain.ChecksumAlgorithm.SHA256, checksum, bytes.length), bytes.length,
                io.vibeminer.application.CancellationToken.none());
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(
                new io.vibeminer.infrastructure.persistence.SqliteCatalog(directory.resolve("catalog.sqlite")), store);
            catalog.registerSnapshot(build);
            catalog.attachVerifiedObject(build.snapshotId(), "PACKAGE_SELF_TEST", identity, retained, evidence,
                io.vibeminer.application.CancellationToken.none());
        }
        try (var store = new io.vibeminer.infrastructure.storage.FileObjectStore(directory.resolve("content"))) {
            var catalog = new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(
                new io.vibeminer.infrastructure.persistence.SqliteCatalog(directory.resolve("catalog.sqlite")), store);
            var restored = catalog.findVerifiedSourceAlias(identity, io.vibeminer.application.CancellationToken.none()).orElseThrow();
            if (!restored.object().equals(retained) || !restored.evidence().equals(evidence)) {
                throw new IllegalStateException("Bundled shared-content restart self-test failed");
            }
        }
    }

}
