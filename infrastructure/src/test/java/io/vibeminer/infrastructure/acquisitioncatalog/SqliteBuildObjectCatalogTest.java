package io.vibeminer.infrastructure.acquisitioncatalog;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.catalog.*;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SqliteBuildObjectCatalogTest {
    @TempDir Path directory;
    private static final CancellationToken NONE = CancellationToken.none();
    private static final ResolvedSourceIdentity SOURCE = new ResolvedSourceIdentity("wow:encoded", "full-source-key", "synthetic:whole-sha256:v1");
    private static final SourceVerificationEvidence EVIDENCE = new SourceVerificationEvidence(SOURCE.verificationSemantics(), "https://cdn.example.invalid/object", Instant.EPOCH);

    @Test void crossHostReuseAfterRestartPreservesCanonicalActualSourceEvidence() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        var originalProof = new SourceVerificationEvidence(SOURCE.verificationSemantics(), "https://us.cdn.blizzard.com/test/object", Instant.EPOCH);
        StoredObject object;
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var first = build("wow", "1"); catalog.registerSnapshot(first); object = publish(store, "shared bytes");
            catalog.attachVerifiedObject(first.snapshotId(), "encoding", SOURCE, object, originalProof, NONE);
        }
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var reopened = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var second = build("wow", "2"); reopened.registerSnapshot(second);
            var actualAlias = reopened.findVerifiedSourceAlias(SOURCE, NONE).orElseThrow();
            assertEquals(object, actualAlias.object()); assertEquals(originalProof, actualAlias.evidence());
            var membership = reopened.attachVerifiedObject(second.snapshotId(), "encoding", SOURCE, actualAlias.object(), actualAlias.evidence(), NONE);
            assertEquals(originalProof, membership.evidence());
            assertNotEquals("https://kr.cdn.blizzard.com/test/object", membership.evidence().sourceUrl());
        }
    }

    @Test void restartSharesOneAliasAndOnePhysicalObjectAcrossExactBuildMemberships() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        var first = build("wow", "1"); var second = build("wow", "2"); var otherProduct = build("wowf", "1");
        StoredObject object;
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            object = publish(store, "shared bytes");
            for (var build : List.of(first, second, otherProduct)) {
                catalog.registerSnapshot(build);
                catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE);
            }
            assertEquals(1, count(database, "source_aliases"));
            assertEquals(3, count(database, "build_object_memberships"));
            try (var files = Files.walk(directory.resolve("store/objects"))) { assertEquals(1, files.filter(Files::isRegularFile).count()); }
        }
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var reopened = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            assertEquals(object, reopened.findVerifiedAlias(SOURCE, NONE).orElseThrow());
            assertEquals(first.snapshotId(), reopened.findVerifiedObject(first.snapshotId(), "encoding", NONE).orElseThrow().snapshotId());
            assertEquals(1, reopened.listReferences(second.snapshotId()).size());
            assertEquals(1, reopened.listReferences(otherProduct.snapshotId()).size());
        }
    }

    @Test void aliasConflictAndRoleConflictRollBackNewMappingsWithoutOverwritingEvidence() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var build = build("wow", "1"); catalog.registerSnapshot(build);
            var original = publish(store, "original"); var other = publish(store, "different");
            var saved = catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, original, EVIDENCE, NONE);
            assertFailure(CatalogException.Code.IMMUTABLE_CONFLICT,
                    () -> catalog.attachVerifiedObject(build.snapshotId(), "root", SOURCE, other, EVIDENCE, NONE));
            var newSource = new ResolvedSourceIdentity(SOURCE.namespace(), "other-source-key", SOURCE.verificationSemantics());
            assertFailure(CatalogException.Code.IMMUTABLE_CONFLICT,
                    () -> catalog.attachVerifiedObject(build.snapshotId(), "encoding", newSource, other, EVIDENCE, NONE));
            assertTrue(catalog.findVerifiedAlias(newSource, NONE).isEmpty());
            assertEquals(1, count(database, "source_aliases")); assertEquals(1, count(database, "catalog_objects"));
            assertEquals(saved, catalog.findVerifiedObject(build.snapshotId(), "encoding", NONE).orElseThrow());
        }
    }

    @Test void missingOrCorruptPhysicalBytesAreNeverReadyAndNoAliasesAppearBeforePublication() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var build = build("wow", "1"); catalog.registerSnapshot(build);
            var missing = new StoredObject(new ObjectDigest("a".repeat(64)), 5);
            assertFailure(CatalogException.Code.MISSING_OBJECT,
                    () -> catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, missing, EVIDENCE, NONE));
            assertEquals(0, count(database, "source_aliases"));
            var object = publish(store, "good bytes"); catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE);
            var path = physicalPath(object); Files.delete(path);
            assertTrue(catalog.findVerifiedAlias(SOURCE, NONE).isEmpty());
            assertTrue(catalog.findVerifiedObject(build.snapshotId(), "encoding", NONE).isEmpty());
            assertEquals(1, catalog.listReferences(build.snapshotId()).size());
            publish(store, "good bytes"); Files.writeString(path, "bad");
            assertFailure(CatalogException.Code.INTEGRITY_FAILURE, () -> catalog.findVerifiedAlias(SOURCE, NONE));
            assertFailure(CatalogException.Code.INTEGRITY_FAILURE, () -> catalog.findVerifiedObject(build.snapshotId(), "encoding", NONE));
        }
    }

    @Test void verifierSemanticsMustMatchEvidenceAndProductNamespacesRemainDistinct() throws Exception {
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
            var build = build("wow", "1"); catalog.registerSnapshot(build);
            var object = publish(store, "good bytes");
            var encodedHeader = new ResolvedSourceIdentity("wow:encoded", SOURCE.immutableSourceKey(), "blte:header-and-chunks:v1");
            assertFailure(CatalogException.Code.INVALID_EVIDENCE,
                    () -> catalog.attachVerifiedObject(build.snapshotId(), "encoding", encodedHeader, object, EVIDENCE, NONE));
            catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE);
            assertTrue(catalog.findVerifiedAlias(encodedHeader, NONE).isEmpty());
            assertTrue(catalog.findVerifiedAlias(new ResolvedSourceIdentity("wowf:encoded", SOURCE.immutableSourceKey(), SOURCE.verificationSemantics()), NONE).isEmpty());
        }
    }

    @Test void unregisteredBuildAndCancelledAttachmentCannotLeaveAnAlias() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var build = build("wow", "1"); var object = publish(store, "good bytes");
            assertFailure(CatalogException.Code.UNKNOWN_SNAPSHOT,
                    () -> catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE));
            catalog.registerSnapshot(build);
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, () -> true));
            assertEquals(0, count(database, "source_aliases"));
            assertTrue(catalog.listReferences(build.snapshotId()).isEmpty());
        }
    }

    @Test void workspaceAndAcquisitionUseOneCatalogAndWorkspaceRemovalPreservesBuildBytes() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var shared = new SqliteCatalog(database);
            var workspaces = new SqliteWorkspaceRepository(shared);
            var objects = new SqliteBuildObjectCatalog(shared, store);
            var build = build("wow", "1"); var workspace = Workspace.create(build, "enUS", "Selected build", Instant.EPOCH);
            workspaces.save(workspace); var object = publish(store, "shared");
            objects.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE);
            workspaces.remove(workspace.id());
            assertEquals(object, objects.findVerifiedObject(build.snapshotId(), "encoding", NONE).orElseThrow().object());
            assertTrue(store.verify(object.digest(), NONE).isPresent());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var query = connection.createStatement(); var rows = query.executeQuery("PRAGMA user_version")) {
                assertEquals(3, rows.getInt(1));
            }
        }
    }

    @Test void missingAliasRowsAndTamperedObjectMetadataFailClosed() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var build = build("wow", "1"); catalog.registerSnapshot(build);
            var object = publish(store, "original"); catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE);
            execute(database, "UPDATE catalog_objects SET length=999");
            assertFailure(CatalogException.Code.INTEGRITY_FAILURE, () -> catalog.findVerifiedAlias(SOURCE, NONE));
            execute(database, "DELETE FROM source_aliases");
            assertFailure(CatalogException.Code.CORRUPT_METADATA, () -> catalog.listReferences(build.snapshotId()));
        }
    }

    @Test void corruptAliasProvenanceCannotBeHiddenBehindValidMembershipEvidence() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var build = build("wow", "1"); catalog.registerSnapshot(build);
            var object = publish(store, "original"); catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE);
            execute(database, "UPDATE source_aliases SET source_url='http://invalid.example/source'");
            assertFailure(CatalogException.Code.CORRUPT_METADATA, () -> catalog.findVerifiedAlias(SOURCE, NONE));
            assertFailure(CatalogException.Code.CORRUPT_METADATA, () -> catalog.findVerifiedObject(build.snapshotId(), "encoding", NONE));
        }
    }

    @Test void missingParentSnapshotCannotLeaveApparentlyReadyExactBuildMembership() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var build = build("wow", "1"); catalog.registerSnapshot(build);
            var object = publish(store, "original"); catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE);
            execute(database, "DELETE FROM snapshots");
            assertFailure(CatalogException.Code.CORRUPT_METADATA, () -> catalog.listReferences(build.snapshotId()));
            assertFailure(CatalogException.Code.CORRUPT_METADATA, () -> catalog.findVerifiedObject(build.snapshotId(), "encoding", NONE));
        }
    }

    @Test void sqlFailureAfterAliasInsertRollsBackAllReferencesAndPreservesPhysicalOrphan() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        try (var store = new FileObjectStore(directory.resolve("store"))) {
            var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(database), store);
            var build = build("wow", "1"); catalog.registerSnapshot(build); var object = publish(store, "original");
            execute(database, "CREATE TRIGGER reject_membership BEFORE INSERT ON build_object_memberships BEGIN SELECT RAISE(ABORT,'synthetic transaction failure'); END");
            assertFailure(CatalogException.Code.STORAGE_UNAVAILABLE,
                    () -> catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE));
            assertEquals(0, count(database, "source_aliases")); assertEquals(0, count(database, "catalog_objects"));
            assertTrue(catalog.listReferences(build.snapshotId()).isEmpty());
            assertTrue(store.verify(object.digest(), NONE).isPresent());
            execute(database, "DROP TRIGGER reject_membership");
            assertNotNull(catalog.attachVerifiedObject(build.snapshotId(), "encoding", SOURCE, object, EVIDENCE, NONE));
        }
    }

    @Test void schemaTwoUpgradesAndFailedSchemaOneMigrationRollsBackBeforeRetry() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        var workspace = Workspace.create(build("wow", "1"), "enUS", "Keep me", Instant.EPOCH);
        new SqliteWorkspaceRepository(database).save(workspace);
        execute(database, "DROP TABLE build_object_memberships"); execute(database, "DROP TABLE source_aliases"); execute(database, "DROP TABLE catalog_objects");
        execute(database, "PRAGMA user_version=2");
        assertEquals(workspace, new SqliteWorkspaceRepository(database).find(workspace.id()).orElseThrow());
        assertEquals(0, count(database, "source_aliases"));
        execute(database, "ALTER TABLE snapshots DROP COLUMN source_count"); execute(database, "PRAGMA user_version=1");
        execute(database, "CREATE TRIGGER reject_migration BEFORE UPDATE ON snapshots BEGIN SELECT RAISE(ABORT,'synthetic migration failure'); END");
        assertThrows(io.vibeminer.application.WorkspaceException.class, () -> new SqliteWorkspaceRepository(database).list());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var query = connection.createStatement()) {
            try (var version = query.executeQuery("PRAGMA user_version")) { assertEquals(1, version.getInt(1)); }
            try (var columns = query.executeQuery("PRAGMA table_info(snapshots)")) {
                while (columns.next()) assertNotEquals("source_count", columns.getString("name"));
            }
        }
        execute(database, "DROP TRIGGER reject_migration");
        assertEquals(workspace, new SqliteWorkspaceRepository(database).find(workspace.id()).orElseThrow());
    }

    @Test void concurrentFactoriesMigrateOldCatalogOnceAndPreserveMetadata() throws Exception {
        var database = directory.resolve("catalog.sqlite");
        var workspace = Workspace.create(build("wow", "1"), "enUS", "Keep me", Instant.EPOCH);
        new SqliteWorkspaceRepository(database).save(workspace);
        execute(database, "ALTER TABLE snapshots DROP COLUMN source_count"); execute(database, "PRAGMA user_version=1");
        try (var executor = Executors.newFixedThreadPool(3)) {
            var jobs = java.util.stream.IntStream.range(0, 3).mapToObj(i -> executor.submit(() -> new SqliteWorkspaceRepository(new SqliteCatalog(database)).find(workspace.id()).orElseThrow())).toList();
            for (var job : jobs) assertEquals(workspace, job.get(5, TimeUnit.SECONDS));
        }
    }

    private static BuildSnapshot build(String product, String digit) {
        var document = new SourceDocument("https://us.version.battle.net/v2/products/" + product + "/versions", Instant.EPOCH, ("fixture-" + product + digit).getBytes(StandardCharsets.UTF_8));
        return new BuildSnapshot(product, "Synthetic edition", "Published", "us", "DEMO", digit.repeat(32), "f".repeat(32), Instant.EPOCH, true,
                new BuildEvidence("100", "e".repeat(32), List.of(document)));
    }
    private StoredObject publish(FileObjectStore store, String value) throws Exception {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return store.publish(new ByteArrayInputStream(bytes), new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, digest, bytes.length), 1024, NONE);
    }
    private Path physicalPath(StoredObject object) { return directory.resolve("store/objects").resolve(object.digest().value().substring(0, 2)).resolve(object.digest().value().substring(2)); }
    private static long count(Path database, String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var query = connection.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM " + table)) { return rows.getLong(1); }
    }
    private static void execute(Path database, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static void assertFailure(CatalogException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(CatalogException.class, action).code());
    }
}
