package io.vibeminer.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionSchemaMigrationTest {
  @TempDir Path directory;

  @Test
  void upgradesExistingInventoryWithoutReplacingItsSourceEvidence() throws Exception {
    var path = directory.resolve("catalog.sqlite");
    version18(path);
    try (var c = new SqliteCatalog(path).connect()) {
      assertEquals(SqliteCatalog.SCHEMA_VERSION, scalar(c, "PRAGMA user_version"));
      assertEquals(1, table(c, "build_retention_bindings"));
      assertEquals(1, table(c, "build_retention_sources"));
      assertArrayEquals(new byte[] {1, 2, 3}, evidence(c));
      try (var s = c.createStatement();
          var r = s.executeQuery("PRAGMA foreign_key_check")) {
        assertFalse(r.next());
      }
    }
  }

  @Test
  void lateSchemaFailureRollsBackBindingsAndVersionWithEvidenceIntact() throws Exception {
    var path = directory.resolve("catalog.sqlite");
    version18(path);
    try (var c = raw(path);
        var s = c.createStatement()) {
      s.execute("CREATE VIEW build_retention_sources AS SELECT 1 AS authored_conflict");
    }
    assertThrows(SQLException.class, () -> new SqliteCatalog(path).connect());
    try (var c = raw(path)) {
      assertEquals(18, scalar(c, "PRAGMA user_version"));
      assertEquals(0, table(c, "build_retention_bindings"));
      assertEquals(0, table(c, "build_retention_sources"));
      assertArrayEquals(new byte[] {1, 2, 3}, evidence(c));
    }
  }

  private static void version18(Path path) throws Exception {
    try (var c = new SqliteCatalog(path).connect();
        var s = c.createStatement()) {
      // Retain the actual inventory/ledger families; remove only the additive issuance tables.
      s.execute("DROP TABLE IF EXISTS build_retention_sources");
      s.execute("DROP TABLE IF EXISTS build_retention_bindings");
      s.execute("PRAGMA user_version=18");
      s.execute("INSERT INTO source_blobs(digest,bytes) VALUES('authored-evidence',x'010203')");
    }
  }

  private static Connection raw(Path path) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + path);
  }

  private static long table(Connection c, String name) throws SQLException {
    try (var q =
        c.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?")) {
      q.setString(1, name);
      try (var r = q.executeQuery()) {
        assertTrue(r.next());
        return r.getLong(1);
      }
    }
  }

  private static byte[] evidence(Connection c) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery("SELECT bytes FROM source_blobs WHERE digest='authored-evidence'")) {
      assertTrue(r.next());
      return r.getBytes(1);
    }
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      return r.next() ? r.getLong(1) : 0;
    }
  }
}
