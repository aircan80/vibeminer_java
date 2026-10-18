package io.vibeminer.infrastructure.buildretention;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.catalog.CatalogException;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDownloadAllPresenceTest {
  @TempDir Path dir;
  static final CancellationToken NONE = CancellationToken.none();

  @Test
  void missingRequiredModeIndexFailsWithoutFallingBackToHistoricalScan() throws Exception {
    var path = dir.resolve("unindexed.sqlite");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        var q = c.createStatement()) {
      q.execute("CREATE TABLE build_retention_intents(mode TEXT)");
      q.execute("INSERT INTO build_retention_intents VALUES('DOWNLOAD_ALL')");
    }
    var failure =
        assertThrows(
            CatalogException.class, () -> new SqliteDownloadAllPresence(path).hasDownloadAll(NONE));
    assertEquals(CatalogException.Code.CORRUPT_METADATA, failure.code());
  }

  @Test
  void absentCatalogIsNotCreatedAndOldCatalogIsNotMigrated() throws Exception {
    var path = dir.resolve("absent/catalog.sqlite");
    assertFalse(new SqliteDownloadAllPresence(path).hasDownloadAll(NONE));
    assertFalse(Files.exists(path.getParent()));
    path = dir.resolve("old.sqlite");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        var q = c.createStatement()) {
      q.execute("PRAGMA user_version=14");
      q.execute("CREATE TABLE old_marker(value INTEGER)");
    }
    byte[] before = Files.readAllBytes(path);
    assertFalse(new SqliteDownloadAllPresence(path).hasDownloadAll(NONE));
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void onlyLiteralStoredDownloadAllIsAHintWithoutWritingCatalog() throws Exception {
    var path = dir.resolve("catalog.sqlite");
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        var q = c.createStatement()) {
      q.execute("CREATE TABLE build_retention_intents(mode TEXT)");
      q.execute(
          "CREATE INDEX build_retention_active_group ON build_retention_intents(mode) WHERE"
              + " mode='DOWNLOAD_ALL'");
      q.execute(
          "INSERT INTO build_retention_intents VALUES('ON_DEMAND'),('download_all'),('OTHER')");
    }
    byte[] before = Files.readAllBytes(path);
    assertFalse(new SqliteDownloadAllPresence(path).hasDownloadAll(NONE));
    assertArrayEquals(before, Files.readAllBytes(path));
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        var q = c.createStatement()) {
      q.execute("INSERT INTO build_retention_intents VALUES('DOWNLOAD_ALL')");
    }
    before = Files.readAllBytes(path);
    assertTrue(new SqliteDownloadAllPresence(path).hasDownloadAll(NONE));
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void corruptCatalogAndCancelledCheckDoNotBecomePositiveHints() throws Exception {
    var path = dir.resolve("bad.sqlite");
    Files.writeString(path, "not a SQLite database");
    assertThrows(
        CatalogException.class, () -> new SqliteDownloadAllPresence(path).hasDownloadAll(NONE));
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            new SqliteDownloadAllPresence(dir.resolve("absent.sqlite")).hasDownloadAll(() -> true));
  }
}
