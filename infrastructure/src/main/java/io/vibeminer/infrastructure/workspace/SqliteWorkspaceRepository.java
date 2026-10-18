package io.vibeminer.infrastructure.workspace;

import io.vibeminer.application.WorkspaceException;
import io.vibeminer.application.WorkspaceRepository;
import io.vibeminer.domain.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static io.vibeminer.application.WorkspaceException.Code.*;

/** App-owned metadata catalog. Each write is a single SQLite transaction; restore has no network port. */
public final class SqliteWorkspaceRepository implements WorkspaceRepository {
    private final Path database;
    private boolean initialized;
    public SqliteWorkspaceRepository(Path database) { this.database = database.toAbsolutePath().normalize(); }

    @Override public synchronized List<Workspace> list() {
        try (var connection = connect(); var query = connection.prepareStatement("SELECT id FROM workspaces ORDER BY saved_at DESC,id")) {
            var values = new ArrayList<Workspace>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) values.add(read(connection, rows.getString(1)).orElseThrow());
            }
            return List.copyOf(values);
        } catch (SQLException failure) { throw unavailable(failure); }
    }
    @Override public synchronized Optional<Workspace> find(String id) {
        try (var connection = connect()) { return read(connection, id); }
        catch (SQLException failure) { throw unavailable(failure); }
    }
    @Override public synchronized Workspace save(Workspace value) {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (read(connection, value.id()).isEmpty()) {
                    try (var count = connection.createStatement(); var rows = count.executeQuery("SELECT count(*) FROM workspaces")) {
                        if (rows.next() && rows.getInt(1) >= 2000) throw new WorkspaceException(LIMIT_REACHED, "Saved workspace limit reached");
                    }
                }
                var build = value.build();
                int inserted;
                try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO snapshots VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    statement.setString(1, build.snapshotId()); statement.setString(2, build.productId());
                    statement.setString(3, build.editionLabel()); statement.setString(4, build.channel());
                    statement.setString(5, build.region()); statement.setString(6, build.versionLabel());
                    statement.setString(7, build.buildConfig()); statement.setString(8, build.cdnConfig());
                    statement.setString(9, build.observedAt().toString()); statement.setInt(10, build.fixture() ? 1 : 0);
                    statement.setString(11, build.evidence().upstreamBuildId()); statement.setString(12, build.evidence().productConfig());
                    statement.setInt(13, build.evidence().documents().size());
                    inserted = statement.executeUpdate();
                }
                if (inserted == 1) {
                    int position = 0;
                    for (var source : build.evidence().documents()) {
                        try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO source_blobs VALUES(?,?)")) {
                            statement.setString(1, source.sha256()); statement.setBytes(2, source.bytes()); statement.executeUpdate();
                        }
                        try (var statement = connection.prepareStatement("INSERT INTO snapshot_sources VALUES(?,?,?,?,?)")) {
                            statement.setString(1, build.snapshotId()); statement.setInt(2, position++);
                            statement.setString(3, source.sha256()); statement.setString(4, source.sourceUrl());
                            statement.setString(5, source.capturedAt().toString()); statement.executeUpdate();
                        }
                    }
                }
                try (var statement = connection.prepareStatement("INSERT INTO workspaces VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name")) {
                    statement.setString(1, value.id()); statement.setString(2, build.snapshotId()); statement.setString(3, value.locale());
                    statement.setString(4, value.name()); statement.setString(5, value.savedAt().toString()); statement.executeUpdate();
                }
                var saved = read(connection, value.id()).orElseThrow();
                connection.commit();
                return saved;
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        } catch (SQLException failure) { throw unavailable(failure); }
    }
    @Override public synchronized Workspace rename(String id, String name) {
        name = Workspace.validateName(name);
        try (var connection = connect()) {
            try (var statement = connection.prepareStatement("UPDATE workspaces SET name=? WHERE id=?")) {
                statement.setString(1, name); statement.setString(2, id);
                if (statement.executeUpdate() == 0) throw new WorkspaceException(NOT_FOUND, "Saved workspace no longer exists");
            }
            return read(connection, id).orElseThrow();
        } catch (SQLException failure) { throw unavailable(failure); }
    }
    @Override public synchronized void remove(String id) {
        try (var connection = connect(); var statement = connection.prepareStatement("DELETE FROM workspaces WHERE id=?")) {
            statement.setString(1, id); statement.executeUpdate();
        } catch (SQLException failure) { throw unavailable(failure); }
        // Snapshot/source history remains; future reference-safe archive retention owns cleanup.
    }

    private Optional<Workspace> read(Connection connection, String id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT w.id,w.locale,w.name,w.saved_at,w.snapshot_id,s.id AS pinned_id,s.* FROM workspaces w LEFT JOIN snapshots s ON s.id=w.snapshot_id WHERE w.id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (rows.getString("pinned_id") == null) throw new WorkspaceException(CORRUPT_METADATA, "Saved workspace references missing metadata");
                var documents = new ArrayList<SourceDocument>();
                try (var sources = connection.prepareStatement("SELECT r.url,r.captured_at,r.digest,b.bytes FROM snapshot_sources r LEFT JOIN source_blobs b ON b.digest=r.digest WHERE r.snapshot_id=? ORDER BY r.position")) {
                    sources.setString(1, rows.getString("snapshot_id"));
                    try (var sourceRows = sources.executeQuery()) {
                        while (sourceRows.next()) {
                            var bytes = sourceRows.getBytes(4);
                            if (bytes == null) throw new IllegalArgumentException("Missing captured source blob");
                            documents.add(new SourceDocument(sourceRows.getString(1), Instant.parse(sourceRows.getString(2)), sourceRows.getString(3), bytes));
                        }
                    }
                }
                if (documents.size() != rows.getInt("source_count")) throw new IllegalArgumentException("Source evidence membership is incomplete");
                var build = new BuildSnapshot(rows.getString("product_id"), rows.getString("edition_label"), rows.getString("channel"),
                    rows.getString("region"), rows.getString("version_label"), rows.getString("build_config"), rows.getString("cdn_config"),
                    Instant.parse(rows.getString("observed_at")), rows.getInt("fixture") != 0,
                    new BuildEvidence(rows.getString("upstream_build_id"), rows.getString("product_config"), documents));
                if (!build.snapshotId().equals(rows.getString("snapshot_id"))) throw new IllegalArgumentException("Snapshot key mismatch");
                return Optional.of(new Workspace(rows.getString(1), build, rows.getString(2), rows.getString(3), Instant.parse(rows.getString(4))));
            } catch (IllegalArgumentException failure) {
                throw new WorkspaceException(CORRUPT_METADATA, "Saved metadata failed integrity checks", failure);
            }
        }
    }

    private Connection connect() throws SQLException {
        if (!initialized) {
            try { if (database.getParent() != null) Files.createDirectories(database.getParent()); }
            catch (IOException failure) { throw new WorkspaceException(STORAGE_UNAVAILABLE, "Cannot open VibeMiner data storage", failure); }
        }
        var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000"); statement.execute("PRAGMA synchronous=FULL");
                if (!initialized) {
                    int schemaVersion;
                    try (var version = statement.executeQuery("PRAGMA user_version")) {
                        schemaVersion = version.next() ? version.getInt(1) : 0;
                        if (schemaVersion > 2) throw new WorkspaceException(STORAGE_UNAVAILABLE, "This catalog requires a newer VibeMiner version");
                    }
                    connection.setAutoCommit(false);
                    statement.execute("CREATE TABLE IF NOT EXISTS snapshots(id TEXT PRIMARY KEY,product_id TEXT NOT NULL,edition_label TEXT NOT NULL,channel TEXT NOT NULL,region TEXT NOT NULL,version_label TEXT NOT NULL,build_config TEXT NOT NULL,cdn_config TEXT NOT NULL,observed_at TEXT NOT NULL,fixture INTEGER NOT NULL,upstream_build_id TEXT NOT NULL,product_config TEXT NOT NULL,source_count INTEGER NOT NULL)");
                    statement.execute("CREATE TABLE IF NOT EXISTS source_blobs(digest TEXT PRIMARY KEY,bytes BLOB NOT NULL)");
                    statement.execute("CREATE TABLE IF NOT EXISTS snapshot_sources(snapshot_id TEXT NOT NULL REFERENCES snapshots(id),position INTEGER NOT NULL,digest TEXT NOT NULL REFERENCES source_blobs(digest),url TEXT NOT NULL,captured_at TEXT NOT NULL,PRIMARY KEY(snapshot_id,position))");
                    statement.execute("CREATE TABLE IF NOT EXISTS workspaces(id TEXT PRIMARY KEY,snapshot_id TEXT NOT NULL REFERENCES snapshots(id),locale TEXT NOT NULL,name TEXT NOT NULL,saved_at TEXT NOT NULL)");
                    if (schemaVersion == 1) {
                        statement.execute("ALTER TABLE snapshots ADD COLUMN source_count INTEGER NOT NULL DEFAULT 0");
                        statement.execute("UPDATE snapshots SET source_count=(SELECT count(*) FROM snapshot_sources r WHERE r.snapshot_id=snapshots.id)");
                    }
                    statement.execute("PRAGMA user_version=2"); connection.commit(); connection.setAutoCommit(true); initialized = true;
                }
            }
            return connection;
        } catch (SQLException | RuntimeException failure) { connection.close(); throw failure; }
    }
    private WorkspaceException unavailable(SQLException failure) {
        return new WorkspaceException(STORAGE_UNAVAILABLE, "VibeMiner metadata storage is unavailable", failure);
    }
}
