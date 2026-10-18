package io.vibeminer.infrastructure.workspace;

import io.vibeminer.domain.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteWorkspaceRepositoryTest {
    @TempDir Path directory;
    private BuildSnapshot build(String product, String config, String region) {
        var document = new SourceDocument("https://us.version.battle.net/v2/products/" + product + "/versions",
            Instant.EPOCH, ("synthetic source:" + config).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new BuildSnapshot(product, product + " fixture edition", "Published", region, "DEMO-SAME",
            config.repeat(32), "f".repeat(32), Instant.EPOCH, true,
            new BuildEvidence("12345", "a".repeat(32), List.of(document)));
    }
    private long sourceCount(Path database) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM source_blobs")) {
            return rows.getLong(1);
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
    }
    @Test void restartPreservesProductsBuildsRegionsAndLocalesWithoutAnyDiscoveryDependency() {
        var database = directory.resolve("workspaces.sqlite");
        var first = new SqliteWorkspaceRepository(database);
        var a = Workspace.create(build("wow", "1", "us"), "enUS", "Retail one", Instant.EPOCH);
        var b = Workspace.create(build("wow", "2", "us"), "enUS", "Retail two", Instant.EPOCH);
        var c = Workspace.create(build("wowf", "1", "us"), "enUS", "Unverified edition", Instant.EPOCH);
        var d = Workspace.create(a.build(), "deDE", "German view", Instant.EPOCH);
        var e = Workspace.create(build("wow", "1", "eu"), "enUS", "EU view", Instant.EPOCH);
        for (var value : List.of(a,b,c,d,e)) first.save(value);
        var reopened = new SqliteWorkspaceRepository(database);
        assertEquals(5, reopened.list().size());
        assertEquals(a, reopened.find(a.id()).orElseThrow());
        assertEquals(5, reopened.list().stream().map(Workspace::id).distinct().count());
        assertEquals(a.build().evidence(), reopened.find(a.id()).orElseThrow().build().evidence());
    }
    @Test void renamingAndNewLatestSelectionCannotMutateSavedSnapshot() {
        var repository = new SqliteWorkspaceRepository(directory.resolve("workspaces.sqlite"));
        var original = Workspace.create(build("wow", "1", "us"), "enUS", "Baseline", Instant.EPOCH);
        repository.save(original);
        repository.save(Workspace.create(build("wow", "2", "us"), "enUS", "New latest", Instant.now()));
        var renamed = repository.rename(original.id(), "Old evidence");
        assertEquals(original.id(), renamed.id());
        assertEquals(original.build(), renamed.build());
        assertEquals("Old evidence", new SqliteWorkspaceRepository(directory.resolve("workspaces.sqlite"))
            .find(original.id()).orElseThrow().name());
        repository.remove(original.id());
        assertEquals(1, repository.list().size());
        assertEquals("2".repeat(32), repository.list().getFirst().build().buildConfig());
    }
    @Test void sharedSourceBytesHaveOnePhysicalDatabaseRowAndDeletionPreservesAnotherReference() {
        var repository = new SqliteWorkspaceRepository(directory.resolve("workspaces.sqlite"));
        var source = build("wow", "1", "us");
        var a = Workspace.create(source, "enUS", "A", Instant.EPOCH);
        var b = Workspace.create(source, "deDE", "B", Instant.EPOCH);
        repository.save(a); repository.save(b);
        assertEquals(1, sourceCount(directory.resolve("workspaces.sqlite")));
        repository.remove(a.id());
        assertEquals(source.evidence(), repository.find(b.id()).orElseThrow().build().evidence());
        assertEquals(1, sourceCount(directory.resolve("workspaces.sqlite")));
    }
    @Test void resavingSameIdentityPreservesOriginalSourceObservation() {
        var repository = new SqliteWorkspaceRepository(directory.resolve("workspaces.sqlite"));
        var original = Workspace.create(build("wow", "1", "us"), "enUS", "Original", Instant.EPOCH);
        repository.save(original);
        var old = original.build();
        var recaptured = new BuildSnapshot(old.productId(), old.editionLabel(), old.channel(), old.region(), old.versionLabel(),
            old.buildConfig(), old.cdnConfig(), Instant.now(), true, new BuildEvidence(old.evidence().upstreamBuildId(),
                old.evidence().productConfig(), List.of(new SourceDocument("https://example.test/later", Instant.now(), new byte[]{9}))));
        var resaved = repository.save(Workspace.create(recaptured, "enUS", "Renamed", Instant.now()));
        assertEquals("Renamed", resaved.name());
        assertEquals(original.build(), resaved.build());
        assertEquals(original.savedAt(), resaved.savedAt());
        assertEquals(1, sourceCount(directory.resolve("workspaces.sqlite")));
    }
    @Test void corruptedCapturedBytesFailClosedInsteadOfShowingUnverifiedEvidence() throws Exception {
        var database = directory.resolve("workspaces.sqlite");
        var repository = new SqliteWorkspaceRepository(database);
        var workspace = Workspace.create(build("wow", "1", "us"), "enUS", "Evidence", Instant.EPOCH);
        repository.save(workspace);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) { statement.executeUpdate("UPDATE source_blobs SET bytes=X'00'"); }
        var failure = assertThrows(io.vibeminer.application.WorkspaceException.class, () -> repository.find(workspace.id()));
        assertEquals(io.vibeminer.application.WorkspaceException.Code.CORRUPT_METADATA, failure.code());
    }

    @Test void missingCapturedBlobCannotSilentlyEraseEvidence() throws Exception {
        var database = directory.resolve("workspaces.sqlite");
        var repository = new SqliteWorkspaceRepository(database);
        var workspace = Workspace.create(build("wow", "1", "us"), "enUS", "Evidence", Instant.EPOCH);
        repository.save(workspace);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM source_blobs"); }
        var failure = assertThrows(io.vibeminer.application.WorkspaceException.class, () -> repository.find(workspace.id()));
        assertEquals(io.vibeminer.application.WorkspaceException.Code.CORRUPT_METADATA, failure.code());
    }
    @Test void missingEvidenceMembershipCannotSilentlyReduceSavedProvenance() throws Exception {
        var database = directory.resolve("workspaces.sqlite");
        var repository = new SqliteWorkspaceRepository(database);
        var workspace = Workspace.create(build("wow", "1", "us"), "enUS", "Evidence", Instant.EPOCH);
        repository.save(workspace);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM snapshot_sources"); }
        var failure = assertThrows(io.vibeminer.application.WorkspaceException.class, () -> repository.find(workspace.id()));
        assertEquals(io.vibeminer.application.WorkspaceException.Code.CORRUPT_METADATA, failure.code());
    }

    @Test void priorMetadataSchemaMigratesTransactionallyAndNewerSchemasFailClosed() throws Exception {
        var database = directory.resolve("workspaces.sqlite");
        var repository = new SqliteWorkspaceRepository(database);
        var workspace = Workspace.create(build("wow", "1", "us"), "enUS", "Evidence", Instant.EPOCH);
        repository.save(workspace);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE snapshots DROP COLUMN source_count"); statement.execute("PRAGMA user_version=1");
        }
        assertEquals(workspace, new SqliteWorkspaceRepository(database).find(workspace.id()).orElseThrow());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=99"); }
        assertThrows(io.vibeminer.application.WorkspaceException.class, () -> new SqliteWorkspaceRepository(database).list());
    }

    @Test void orphanWorkspaceIsCorruptionRatherThanMissingSelection() throws Exception {
        var database = directory.resolve("workspaces.sqlite");
        var repository = new SqliteWorkspaceRepository(database);
        repository.list();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workspaces VALUES('orphan','missing','enUS','Lost snapshot','1970-01-01T00:00:00Z')");
        }
        var failure = assertThrows(io.vibeminer.application.WorkspaceException.class, () -> repository.find("orphan"));
        assertEquals(io.vibeminer.application.WorkspaceException.Code.CORRUPT_METADATA, failure.code());
        assertThrows(io.vibeminer.application.WorkspaceException.class, repository::list);
    }

}
