package io.vibeminer.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schema20 is the exact schema19 fixture plus its sole additive index, checked against binary20.
 */
class BuildReadinessSchema21MigrationTest {
  @TempDir Path directory;

  AuthorizedRetentionSchema20Test fixture() {
    var fixture = new AuthorizedRetentionSchema20Test();
    fixture.directory = directory;
    return fixture;
  }

  void createActual20(AuthorizedRetentionSchema20Test f) throws Exception {
    f.createActual19();
    try (var c = f.raw();
        var s = c.createStatement()) {
      s.execute(
          "CREATE INDEX build_retention_authorized_target ON"
              + " build_retention_intents(mode,target_id)");
      s.execute("PRAGMA user_version=20");
      assertEquals(
          "c6942c6b3d3b606558e3ea1e25642930c3a73deffd11421ac8f4b561fc10bb2b",
          schemaHash(c),
          "Fixture schema matches the compiled20 binary's sqlite_master, not current migration"
              + " helpers");
    }
  }

  String schemaHash(Connection c) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE"
                    + " 'sqlite_%' ORDER BY type,name")) {
      while (r.next())
        digest.update(
            (r.getString(1) + "\0" + r.getString(2) + "\0" + r.getString(3) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  @Test
  void actual20UpgradeAddsOnlyEmptyDatedSummariesAndPreservesEveryOriginalRow() throws Exception {
    var f = fixture();
    createActual20(f);
    Map<String, List<String>> before;
    try (var c = f.raw()) {
      assertEquals(20, f.scalar(c, "PRAGMA user_version"));
      before = f.rows(c);
      assertEquals(53, before.size());
    }
    try (var c = new SqliteCatalog(f.database()).connect()) {
      assertEquals(SqliteCatalog.SCHEMA_VERSION, f.scalar(c, "PRAGMA user_version"));
      var after = f.rows(c);
      assertEquals(List.of(), after.remove("build_readiness_observations"));
      assertEquals(before, after);
    }
  }

  @Test
  void laterDdlFailureRollsBackReadinessTableAndVersionWithAllPreviousRowsUntouched()
      throws Exception {
    var f = fixture();
    createActual20(f);
    Map<String, List<String>> before;
    try (var c = f.raw();
        var s = c.createStatement()) {
      s.execute("DROP TABLE build_retention_sources");
      s.execute("CREATE VIEW build_retention_sources AS SELECT 1 AS authored_late_fault");
      before = f.rows(c);
    }
    assertThrows(Exception.class, () -> new SqliteCatalog(f.database()).connect());
    try (var c = f.raw()) {
      assertEquals(20, f.scalar(c, "PRAGMA user_version"));
      assertEquals(
          0,
          f.scalar(
              c, "SELECT count(*) FROM sqlite_master WHERE name='build_readiness_observations'"));
      assertEquals(before, f.rows(c));
    }
  }
}
