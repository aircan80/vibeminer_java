package io.vibeminer.infrastructure.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryHistorySchemaTest {
  @TempDir Path directory;

  @Test
  void additiveMigrationPreservesSharedBodiesAndNeverBackfillsObservations() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    byte[] original = {1, 2, 3, 4};
    try (var c = catalog.connect();
        var q = c.prepareStatement("INSERT INTO source_blobs VALUES(?,?)")) {
      q.setString(1, "a".repeat(64));
      q.setBytes(2, original);
      q.executeUpdate();
    }
    try (var c = catalog.connect();
        var q = c.createStatement()) {
      q.execute("DROP TABLE IF EXISTS discovery_history_builds");
      q.execute("DROP TABLE IF EXISTS discovery_history_documents");
      q.execute("DROP TABLE IF EXISTS discovery_history_events");
      q.execute("PRAGMA user_version=12");
    }
    try (var c = catalog.connect();
        var q = c.createStatement()) {
      try (var r = q.executeQuery("PRAGMA user_version")) {
        assertEquals(13, r.getInt(1));
      }
      try (var r = q.executeQuery("SELECT bytes FROM source_blobs")) {
        assertArrayEquals(original, r.getBytes(1));
      }
      try (var r = q.executeQuery("SELECT count(*) FROM discovery_history_events")) {
        assertEquals(0, r.getLong(1));
      }
      try (var r = q.executeQuery("SELECT count(*) FROM discovery_history_documents")) {
        assertEquals(0, r.getLong(1));
      }
      try (var r = q.executeQuery("SELECT count(*) FROM discovery_history_builds")) {
        assertEquals(0, r.getLong(1));
      }
    }
  }
}
