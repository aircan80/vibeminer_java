package io.vibeminer.infrastructure.persistence;

import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.domain.*;
import org.sqlite.SQLiteConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import static io.vibeminer.application.catalog.CatalogException.Code.*;

/** Single schema/migration owner and connection factory for app-owned metadata and object references. */
public final class SqliteCatalog {
    public static final int SCHEMA_VERSION = 20;
    private final Path database;

    public SqliteCatalog(Path database) { this.database = database.toAbsolutePath().normalize(); }

    public Connection connect() throws SQLException {
        try { if (database.getParent() != null) Files.createDirectories(database.getParent()); }
        catch (IOException failure) { throw new CatalogException(STORAGE_UNAVAILABLE, "Cannot open VibeMiner catalog storage", failure); }
        var configuration = new SQLiteConfig();
        configuration.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        var connection = configuration.createConnection("jdbc:sqlite:" + database);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=FULL");
            }
            int version = version(connection);
            if (version > SCHEMA_VERSION) throw new CatalogException(STORAGE_UNAVAILABLE, "This catalog requires a newer VibeMiner version");
            if (version < SCHEMA_VERSION) migrate(connection);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
            throw failure;
        }
    }

    private void migrate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            // Acquire SQLite's write reservation before rereading the version. Separate factories
            // cannot both observe v1 and race the same ALTER TABLE.
            statement.execute("BEGIN IMMEDIATE");
            try {
                int version = version(connection);
                if (version > SCHEMA_VERSION) throw new CatalogException(STORAGE_UNAVAILABLE, "This catalog requires a newer VibeMiner version");
                statement.execute("CREATE TABLE IF NOT EXISTS snapshots(id TEXT PRIMARY KEY,product_id TEXT NOT NULL,edition_label TEXT NOT NULL,channel TEXT NOT NULL,region TEXT NOT NULL,version_label TEXT NOT NULL,build_config TEXT NOT NULL,cdn_config TEXT NOT NULL,observed_at TEXT NOT NULL,fixture INTEGER NOT NULL,upstream_build_id TEXT NOT NULL,product_config TEXT NOT NULL,source_count INTEGER NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS source_blobs(digest TEXT PRIMARY KEY,bytes BLOB NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS snapshot_sources(snapshot_id TEXT NOT NULL REFERENCES snapshots(id),position INTEGER NOT NULL,digest TEXT NOT NULL REFERENCES source_blobs(digest),url TEXT NOT NULL,captured_at TEXT NOT NULL,PRIMARY KEY(snapshot_id,position))");
                statement.execute("CREATE TABLE IF NOT EXISTS workspaces(id TEXT PRIMARY KEY,snapshot_id TEXT NOT NULL REFERENCES snapshots(id),locale TEXT NOT NULL,name TEXT NOT NULL,saved_at TEXT NOT NULL)");
                if (version == 1) {
                    statement.execute("ALTER TABLE snapshots ADD COLUMN source_count INTEGER NOT NULL DEFAULT 0");
                    statement.execute("UPDATE snapshots SET source_count=(SELECT count(*) FROM snapshot_sources r WHERE r.snapshot_id=snapshots.id)");
                }
                statement.execute("CREATE TABLE IF NOT EXISTS catalog_objects(digest TEXT PRIMARY KEY,length INTEGER NOT NULL CHECK(typeof(length)='integer' AND length>=0))");
                statement.execute("CREATE TABLE IF NOT EXISTS source_aliases(namespace TEXT NOT NULL,source_key TEXT NOT NULL,verifier_semantics TEXT NOT NULL,object_digest TEXT NOT NULL REFERENCES catalog_objects(digest),source_url TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(namespace,source_key,verifier_semantics))");
                statement.execute("CREATE TABLE IF NOT EXISTS build_object_memberships(snapshot_id TEXT NOT NULL REFERENCES snapshots(id),role TEXT NOT NULL,namespace TEXT NOT NULL,source_key TEXT NOT NULL,verifier_semantics TEXT NOT NULL,source_url TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(snapshot_id,role),FOREIGN KEY(namespace,source_key,verifier_semantics) REFERENCES source_aliases(namespace,source_key,verifier_semantics))");
                io.vibeminer.infrastructure.retention.RetentionSql.migrate(connection, version);
                io.vibeminer.infrastructure.selectedfile.SelectedFileSchema.migrate(connection);
                try { if(version<7) io.vibeminer.infrastructure.content.CanonicalContentSchema.migrate(connection); }
                catch (IllegalArgumentException invalid) { throw new CatalogException(CORRUPT_METADATA,"Legacy content reconciliation contains invalid facts",invalid); }
                io.vibeminer.infrastructure.item.ItemProjectionSchema.migrate(connection);
                io.vibeminer.infrastructure.content.MetadataAssociationSchema.migrate(connection);
                io.vibeminer.infrastructure.content.MetadataRepairAuditSchema.migrate(connection);
                io.vibeminer.infrastructure.item.SparseNameSchema.migrate(connection);
                io.vibeminer.infrastructure.discoveryhistory.DiscoveryHistorySchema.migrate(connection);
                io.vibeminer.infrastructure.discoveryhistory.BuildLibrarySchema.migrate(connection);
                io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionSql.migrate(connection);
                io.vibeminer.infrastructure.encoding.EncodingLookupSchema.migrate(connection);
                io.vibeminer.infrastructure.buildretention.BuildRetentionSchema.install(connection);
                io.vibeminer.infrastructure.inventory.InventorySchema.migrate(connection);
                io.vibeminer.infrastructure.inventory.BuildRetentionBindingSchema.migrate(connection);
                io.vibeminer.infrastructure.buildretention.BuildRetentionSourceSchema.install(connection);
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException failure) {
                try { statement.execute("ROLLBACK"); } catch (SQLException suppressed) { failure.addSuppressed(suppressed); }
                throw failure;
            }
        }
    }

    private static int version(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    /** Caller owns the surrounding transaction; the first observation remains immutable. */
    public BuildSnapshot ensureSnapshot(Connection connection, BuildSnapshot build) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalStateException("Snapshot registration requires a transaction");
        insertSnapshot(connection, build, false);
        return readSnapshot(connection, build.snapshotId()).orElseThrow(() -> new CatalogException(CORRUPT_METADATA, "Registered snapshot could not be read"));
    }

  /**
   * Register identity and first source references without hydrating canonical source bodies. Caller
   * must validate/register every incoming document blob in this SAME transaction first. This checks
   * identity/reference shape, not source availability or content readiness.
   */
  public void ensureSnapshotIdentity(Connection connection, BuildSnapshot build)
      throws SQLException {
    if (connection.getAutoCommit())
      throw new IllegalStateException("Snapshot registration requires a transaction");
    insertSnapshot(connection, build, true);
    requireSnapshotIdentity(connection, build);
  }

  /** Validate canonical identity columns without substituting first-observation documents. */
  public void requireSnapshotIdentity(Connection connection, BuildSnapshot expected)
      throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM snapshots WHERE id=?")) {
      query.setString(1, expected.snapshotId());
      try (var row = query.executeQuery()) {
        if (!row.next())
          throw new CatalogException(CORRUPT_METADATA, "History snapshot reference is missing");
        try {
          var identity =
              new BuildSnapshot(
                  row.getString("product_id"),
                  row.getString("edition_label"),
                  row.getString("channel"),
                  row.getString("region"),
                  row.getString("version_label"),
                  row.getString("build_config"),
                  row.getString("cdn_config"),
                  Instant.parse(row.getString("observed_at")),
                  row.getInt("fixture") != 0,
                  new BuildEvidence(
                      row.getString("upstream_build_id"),
                      row.getString("product_config"),
                      java.util.List.of()));
          if (!identity.snapshotId().equals(expected.snapshotId()))
            throw new IllegalArgumentException("Snapshot identity fields disagree");
          int expectedCount = row.getInt("source_count"), count = 0;
          if (expectedCount < 0 || expectedCount > 8)
            throw new IllegalArgumentException("Invalid canonical source count");
          try (var sources =
              connection.prepareStatement(
                  "SELECT s.position,length(b.bytes) FROM snapshot_sources s LEFT JOIN source_blobs"
                      + " b ON b.digest=s.digest WHERE s.snapshot_id=? ORDER BY s.position")) {
            sources.setString(1, expected.snapshotId());
            try (var refs = sources.executeQuery()) {
              while (refs.next()) {
                if (refs.getInt(1) != count++
                    || refs.getObject(2) == null
                    || refs.getLong(2) > 1048576)
                  throw new IllegalArgumentException("Canonical source membership is incomplete");
              }
            }
          }
          if (count != expectedCount)
            throw new IllegalArgumentException("Canonical source count differs");
        } catch (RuntimeException invalid) {
          throw new CatalogException(
              CORRUPT_METADATA, "Saved snapshot identity failed integrity checks", invalid);
        }
      }
    }
  }

  private void insertSnapshot(
      Connection connection, BuildSnapshot build, boolean bodiesAlreadyRegistered)
      throws SQLException {
    int inserted;
    try (var statement =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO snapshots VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      statement.setString(1, build.snapshotId());
      statement.setString(2, build.productId());
      statement.setString(3, build.editionLabel());
      statement.setString(4, build.channel());
      statement.setString(5, build.region());
      statement.setString(6, build.versionLabel());
      statement.setString(7, build.buildConfig());
      statement.setString(8, build.cdnConfig());
      statement.setString(9, build.observedAt().toString());
      statement.setInt(10, build.fixture() ? 1 : 0);
      statement.setString(11, build.evidence().upstreamBuildId());
      statement.setString(12, build.evidence().productConfig());
      statement.setInt(13, build.evidence().documents().size());
      inserted = statement.executeUpdate();
    }
    if (inserted == 1) {
      int position = 0;
      for (var source : build.evidence().documents()) {
        if (!bodiesAlreadyRegistered) {
          try (var statement =
              connection.prepareStatement("INSERT OR IGNORE INTO source_blobs VALUES(?,?)")) {
            statement.setString(1, source.sha256());
            statement.setBytes(2, source.bytes());
            statement.executeUpdate();
          }
        }
        try (var statement =
            connection.prepareStatement("INSERT INTO snapshot_sources VALUES(?,?,?,?,?)")) {
          statement.setString(1, build.snapshotId());
          statement.setInt(2, position++);
          statement.setString(3, source.sha256());
          statement.setString(4, source.sourceUrl());
          statement.setString(5, source.capturedAt().toString());
          statement.executeUpdate();
        }
      }
    }
  }

    public Optional<BuildSnapshot> readSnapshot(Connection connection, String snapshotId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM snapshots WHERE id=?")) {
            query.setString(1, snapshotId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                try {
                    int sourceCount = rows.getInt("source_count");
                    if (sourceCount < 0 || sourceCount > 8) throw new IllegalArgumentException("Invalid source evidence count");
                    var documents = new ArrayList<SourceDocument>();
                    try (var sources = connection.prepareStatement("SELECT r.position,r.url,r.captured_at,r.digest,length(b.bytes),b.bytes FROM snapshot_sources r LEFT JOIN source_blobs b ON b.digest=r.digest WHERE r.snapshot_id=? ORDER BY r.position")) {
                        sources.setString(1, snapshotId);
                        try (var sourceRows = sources.executeQuery()) {
                            while (sourceRows.next()) {
                                if (sourceRows.getInt(1) != documents.size() || sourceRows.getObject(5) == null || sourceRows.getLong(5) > 1_048_576) {
                                    throw new IllegalArgumentException("Missing or invalid captured source evidence");
                                }
                                documents.add(new SourceDocument(sourceRows.getString(2), Instant.parse(sourceRows.getString(3)), sourceRows.getString(4), sourceRows.getBytes(6)));
                            }
                        }
                    }
                    if (documents.size() != sourceCount) throw new IllegalArgumentException("Source evidence membership is incomplete");
                    var build = new BuildSnapshot(rows.getString("product_id"), rows.getString("edition_label"), rows.getString("channel"),
                            rows.getString("region"), rows.getString("version_label"), rows.getString("build_config"), rows.getString("cdn_config"),
                            Instant.parse(rows.getString("observed_at")), rows.getInt("fixture") != 0,
                            new BuildEvidence(rows.getString("upstream_build_id"), rows.getString("product_config"), documents));
                    if (!build.snapshotId().equals(snapshotId)) throw new IllegalArgumentException("Snapshot identity does not match saved fields");
                    return Optional.of(build);
                } catch (RuntimeException invalid) {
                    if (invalid instanceof CatalogException typed) throw typed;
                    throw new CatalogException(CORRUPT_METADATA, "Saved snapshot failed integrity checks", invalid);
                }
            }
        }
    }
}
