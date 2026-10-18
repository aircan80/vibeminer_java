package io.vibeminer.infrastructure.workspace;

import io.vibeminer.application.WorkspaceException;
import io.vibeminer.application.WorkspaceRepository;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.application.catalog.CatalogException;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static io.vibeminer.application.WorkspaceException.Code.*;

/** App-owned metadata catalog. Each write is a single SQLite transaction; restore has no network port. */
public final class SqliteWorkspaceRepository implements WorkspaceRepository {
    private final SqliteCatalog catalog;
    public SqliteWorkspaceRepository(Path database) { this(new SqliteCatalog(database)); }
    public SqliteWorkspaceRepository(SqliteCatalog catalog) { this.catalog = java.util.Objects.requireNonNull(catalog); }

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
                catalog.ensureSnapshot(connection, build);
                try (var statement = connection.prepareStatement("INSERT INTO workspaces VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name")) {
                    statement.setString(1, value.id()); statement.setString(2, build.snapshotId()); statement.setString(3, value.locale());
                    statement.setString(4, value.name()); statement.setString(5, value.savedAt().toString()); statement.executeUpdate();
                }
                var saved = read(connection, value.id()).orElseThrow();
                connection.commit();
                return saved;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
                if (failure instanceof CatalogException catalogFailure) {
                    throw new WorkspaceException(catalogFailure.code() == CatalogException.Code.CORRUPT_METADATA ? CORRUPT_METADATA : STORAGE_UNAVAILABLE,
                            "Saved metadata failed integrity checks", catalogFailure);
                }
                throw failure;
            }
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
        try (var query = connection.prepareStatement("SELECT id,locale,name,saved_at,snapshot_id FROM workspaces WHERE id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                try {
                    var build = catalog.readSnapshot(connection, rows.getString("snapshot_id"))
                            .orElseThrow(() -> new WorkspaceException(CORRUPT_METADATA, "Saved workspace references missing metadata"));
                    return Optional.of(new Workspace(rows.getString("id"), build, rows.getString("locale"), rows.getString("name"), Instant.parse(rows.getString("saved_at"))));
                } catch (RuntimeException invalid) {
                    if (invalid instanceof WorkspaceException typed) throw typed;
                    throw new WorkspaceException(CORRUPT_METADATA, "Saved metadata failed integrity checks", invalid);
                }
            }
        }
    }

    private Connection connect() throws SQLException {
        try { return catalog.connect(); }
        catch (CatalogException failure) { throw new WorkspaceException(STORAGE_UNAVAILABLE, "VibeMiner metadata storage is unavailable", failure); }
    }
    private WorkspaceException unavailable(SQLException failure) {
        return new WorkspaceException(STORAGE_UNAVAILABLE, "VibeMiner metadata storage is unavailable", failure);
    }
}
