package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentSchemaIntegrationTest {
  @TempDir Path directory;

  private static final List<String> DERIVED_TABLES =
      List.of(
          "build_retention_sources",
          "build_retention_bindings",
          "en_lookup_candidates",
          "en_lookup_content",
          "en_lookup_encoding",
          "en_lookup_artifacts",
          "build_retention_jobs",
          "build_retention_intents",
          "build_inventory_references",
          "build_inventory_sources",
          "build_inventory_members",
          "build_inventory_generations");

  @Test
  void upgradesAnExistingMetadataCatalogWithoutReplacingItsEvidence() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    byte[] evidence =
        "authored pre-inventory observation".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence));
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      // Remove only the newer derived families to reproduce the published metadata schema.
      for (String table : DERIVED_TABLES) s.execute("DROP TABLE IF EXISTS " + table);
      s.execute("PRAGMA user_version=15");
      try (var insert = c.prepareStatement("INSERT INTO source_blobs VALUES(?,?)")) {
        insert.setString(1, digest);
        insert.setBytes(2, evidence);
        insert.executeUpdate();
      }
    }
    try (var c = new SqliteCatalog(directory.resolve("catalog.sqlite")).connect()) {
      assertEquals(SqliteCatalog.SCHEMA_VERSION, scalar(c, "PRAGMA user_version"));
      for (String table : DERIVED_TABLES) {
        try (var query =
            c.prepareStatement(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?")) {
          query.setString(1, table);
          try (var r = query.executeQuery()) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1), table);
          }
        }
      }
      try (var query = c.prepareStatement("SELECT bytes FROM source_blobs WHERE digest=?")) {
        query.setString(1, digest);
        try (var r = query.executeQuery()) {
          assertTrue(r.next());
          assertArrayEquals(evidence, r.getBytes(1));
        }
      }
    }
  }

  @Test
  void existingRuntimeStartupEntryRecoversDerivedStagingAndPreservesPublishedRows()
      throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    byte[] retainedProof = {1, 2, 3}; // Opaque authored history; this test grants no readiness.
    try (var c = catalog.connect();
        var s = c.createStatement()) {
      try (var insert =
          c.prepareStatement("INSERT INTO en_lookup_artifacts VALUES(?,?,?,?,?,?,?,?)")) {
        for (String state : List.of("STAGING", "READY")) {
          insert.setString(1, state);
          insert.setString(2, "generation");
          insert.setString(3, "retired-owner");
          insert.setString(4, state);
          insert.setBytes(5, retainedProof);
          insert.setInt(6, 0);
          insert.setInt(7, 0);
          insert.setInt(8, 0);
          insert.executeUpdate();
        }
      }
      for (String state : List.of("STAGING", "READY")) {
        try (var insert =
            c.prepareStatement(
                "INSERT INTO"
                    + " build_inventory_generations(id,snapshot_id,admission_id,en_artifact,en_generation,scope,status,member_count)"
                    + " VALUES(?,?,?,?,?,?,?,?)")) {
          for (int i = 1; i <= 6; i++) insert.setString(i, i == 1 ? state : "authored-context");
          insert.setString(7, state);
          insert.setInt(8, 0);
          insert.executeUpdate();
        }
      }
    }
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      store.contentSession().beforeProducerOperation();
      assertThrows(
          IllegalStateException.class,
          () -> SqliteContentStartupRecovery.recoverAtStartup(catalog, store.contentSession()));
    }
    try (var c = catalog.connect()) {
      assertEquals(0, scalar(c, "SELECT count(*) FROM en_lookup_artifacts WHERE status='STAGING'"));
      assertEquals(
          0, scalar(c, "SELECT count(*) FROM build_inventory_generations WHERE status='STAGING'"));
      assertEquals(
          1, scalar(c, "SELECT count(*) FROM build_inventory_generations WHERE status='READY'"));
      try (var s = c.createStatement();
          var r =
              s.executeQuery("SELECT source_proof FROM en_lookup_artifacts WHERE status='READY'")) {
        assertTrue(r.next());
        assertArrayEquals(retainedProof, r.getBytes(1));
      }
    }
  }

  private static long scalar(Connection c, String sql) throws Exception {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      assertTrue(r.next());
      return r.getLong(1);
    }
  }
}
