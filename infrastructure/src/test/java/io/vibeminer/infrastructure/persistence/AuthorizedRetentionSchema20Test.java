package io.vibeminer.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildretention.BuildRetention.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real schema-19 DDL exported from binary f533bb3; all seeded values are authored metadata. */
class AuthorizedRetentionSchema20Test {
  @TempDir Path directory;

  Path database() {
    return directory.resolve("catalog.sqlite");
  }

  Connection raw() throws SQLException {
    var c = DriverManager.getConnection("jdbc:sqlite:" + database());
    try (var s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
    }
    return c;
  }

  void createActual19() throws Exception {
    try (var input = getClass().getResourceAsStream("schema19.sql")) {
      assertNotNull(input);
      String ddl =
          new String(input.readAllBytes(), StandardCharsets.UTF_8)
              .replace("\r\n", "\n")
              .replace('\r', '\n');
      try (var c = raw();
          var s = c.createStatement()) {
        for (String sql : ddl.split("(?m)^-- VIBEMINER-STATEMENT\\R"))
          if (sql.stripLeading().startsWith("CREATE ")) s.execute(sql);
        s.execute("INSERT INTO build_library_admission_counter VALUES(1,0)");
        s.execute("INSERT INTO build_library_revision VALUES(1,0)");
        s.execute("PRAGMA user_version=19");
      }
    }
    byte[] source = "authored schema19 source bytes".getBytes(StandardCharsets.UTF_8);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(source));
    var target =
        new Target(
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            1,
            "all-declared-v1",
            "e".repeat(64));
    try (var c = raw()) {
      c.setAutoCommit(false);
      try (var q = c.prepareStatement("INSERT INTO source_blobs VALUES(?,?)")) {
        q.setString(1, digest);
        q.setBytes(2, source);
        q.executeUpdate();
      }
      try (var q = c.prepareStatement("INSERT INTO catalog_objects VALUES(?,?)")) {
        q.setString(1, digest);
        q.setLong(2, source.length);
        q.executeUpdate();
      }
      try (var q = c.prepareStatement("INSERT INTO source_aliases VALUES(?,?,?,?,?,?)")) {
        String[] values = {
          "tact.config.raw-md5",
          md5,
          "tact-config-raw-md5-v1",
          digest,
          "https://us.cdn.blizzard.com/tpr/configs/data/" + md5,
          "2026-10-18T12:00:00Z"
        };
        for (int i = 0; i < values.length; i++) q.setString(i + 1, values[i]);
        q.executeUpdate();
      }
      try (var s = c.createStatement()) {
        s.execute(
            "INSERT INTO canonical_content_slots VALUES('wow','"
                + "1".repeat(32)
                + "','"
                + "2".repeat(32)
                + "',17,'11111111-1111-1111-1111-111111111111','PUBLISHING','22222222-2222-2222-2222-222222222222',NULL,'33333333-3333-3333-3333-333333333333')");
      }
      try (var q =
          c.prepareStatement(
              "INSERT INTO build_retention_intents"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
        Object[] values = {
          target.identity(),
          target.groupId(),
          target.snapshotId(),
          target.admissionId(),
          target.inventoryId(),
          target.inventoryGeneration(),
          target.scopeRevision(),
          target.scopeCommitment(),
          "DOWNLOAD_ALL",
          3,
          "2026-10-18T12:00:00Z",
          1,
          0,
          100,
          10000,
          10000,
          10000,
          3,
          0,
          0,
          0
        };
        for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
        q.executeUpdate();
      }
      // Opaque historical proof bytes are preserved, never promoted into a readiness capability.
      try (var q = c.prepareStatement("INSERT INTO build_retention_sources VALUES(?,?,?,?)")) {
        q.setString(1, target.identity());
        q.setString(2, "f".repeat(64));
        q.setBytes(3, new byte[] {1, 2, 3});
        q.setString(4, "9".repeat(64));
        q.executeUpdate();
      }
      c.commit();
    }
  }

  long scalar(Connection c, String sql) throws SQLException {
    try (var s = c.createStatement();
        var r = s.executeQuery(sql)) {
      assertTrue(r.next());
      return r.getLong(1);
    }
  }

  Map<String, List<String>> rows(Connection c) throws Exception {
    var tables = new ArrayList<String>();
    try (var s = c.createStatement();
        var r =
            s.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                    + " ORDER BY name")) {
      while (r.next()) tables.add(r.getString(1));
    }
    var all = new TreeMap<String, List<String>>();
    for (var table : tables) {
      var data = new ArrayList<String>();
      try (var s = c.createStatement();
          var r = s.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"")) {
        while (r.next()) {
          var line = new StringBuilder();
          for (int col = 1; col <= r.getMetaData().getColumnCount(); col++) {
            Object v = r.getObject(col);
            String text =
                v == null
                    ? "NULL"
                    : v instanceof byte[] bytes
                        ? "BLOB:" + HexFormat.of().formatHex(bytes)
                        : v.getClass().getName() + ":" + v;
            line.append(text.length()).append(':').append(text);
          }
          data.add(line.toString());
        }
      }
      Collections.sort(data);
      all.put(table, List.copyOf(data));
    }
    return all;
  }

  @Test
  void actual19NormalConnectInstallsIndexAndPreservesAllRowsReceiptsAndActiveNonce()
      throws Exception {
    createActual19();
    Map<String, List<String>> before;
    try (var c = raw()) {
      assertEquals(19, scalar(c, "PRAGMA user_version"));
      assertEquals(
          0,
          scalar(
              c,
              "SELECT count(*) FROM sqlite_master WHERE name='build_retention_authorized_target'"));
      before = rows(c);
      assertEquals(53, before.size());
    }
    try (var c = new SqliteCatalog(database()).connect()) {
      assertEquals(SqliteCatalog.SCHEMA_VERSION, scalar(c, "PRAGMA user_version"));
      var after = rows(c);
      assertEquals(List.of(), after.remove("build_readiness_observations"));
      assertEquals(before, after);
      boolean indexed = false;
      try (var s = c.createStatement();
          var plan =
              s.executeQuery(
                  "EXPLAIN QUERY PLAN SELECT target_id FROM build_retention_intents WHERE"
                      + " mode='DOWNLOAD_ALL' AND target_id>'' ORDER BY target_id LIMIT 16")) {
        while (plan.next())
          indexed |= plan.getString(4).contains("build_retention_authorized_target");
      }
      assertTrue(
          indexed, "Normal version19 upgrade installs restart index without manual helper calls");
    }
  }

  @Test
  void lateMigrationFailureRollsBackNewIndexVersionAndEveryPriorRow() throws Exception {
    createActual19();
    Map<String, List<String>> before;
    try (var c = raw();
        var s = c.createStatement()) {
      s.execute("DROP TABLE build_retention_sources");
      s.execute("CREATE VIEW build_retention_sources AS SELECT 1 AS authored_fault");
      before = rows(c);
    }
    assertThrows(Exception.class, () -> new SqliteCatalog(database()).connect());
    try (var c = raw()) {
      assertEquals(19, scalar(c, "PRAGMA user_version"));
      assertEquals(
          0,
          scalar(
              c,
              "SELECT count(*) FROM sqlite_master WHERE name='build_retention_authorized_target'"));
      assertEquals(before, rows(c));
    }
  }
}
