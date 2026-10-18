package io.vibeminer.infrastructure.buildopening;

import java.sql.*;

/** Additive schema 15; the shared SqliteCatalog owns the surrounding migration transaction. */
public final class BuildMetadataAdmissionSql {
  private BuildMetadataAdmissionSql() {}

  public static void migrate(Connection c) throws SQLException {
    try (var s = c.createStatement()) {
      s.execute(
          """
          CREATE TABLE IF NOT EXISTS build_open_admissions(
            context_id TEXT PRIMARY KEY CHECK(length(context_id)=64),
            snapshot_id TEXT NOT NULL REFERENCES snapshots(id),locale TEXT NOT NULL,
            scope_revision TEXT NOT NULL,classifier_revision TEXT NOT NULL,
            required_count INTEGER NOT NULL CHECK(required_count BETWEEN 5 AND 1024),
            closure_sha256 TEXT NOT NULL CHECK(length(closure_sha256)=64),committed_at TEXT NOT NULL,
            UNIQUE(snapshot_id,locale,scope_revision))
          """);
      s.execute(
          """
          CREATE TABLE IF NOT EXISTS build_open_role_facts(
            context_id TEXT NOT NULL REFERENCES build_open_admissions(context_id),role TEXT NOT NULL,
            namespace TEXT NOT NULL,source_key TEXT NOT NULL,verifier_semantics TEXT NOT NULL,
            object_digest TEXT NOT NULL REFERENCES catalog_objects(digest),object_length INTEGER NOT NULL CHECK(object_length>0),
            canonical_token TEXT,proof_sha256 TEXT NOT NULL CHECK(length(proof_sha256)=64),
            PRIMARY KEY(context_id,role),
            FOREIGN KEY(namespace,source_key,verifier_semantics) REFERENCES source_aliases(namespace,source_key,verifier_semantics))
          """);
      s.execute(
          """
          CREATE TABLE IF NOT EXISTS build_open_receipts(
            context_id TEXT NOT NULL REFERENCES build_open_admissions(context_id),
            event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
            row_ordinal INTEGER NOT NULL CHECK(row_ordinal BETWEEN 0 AND 9999),
            group_id TEXT NOT NULL REFERENCES build_library_groups(group_id),
            opened_at TEXT NOT NULL,PRIMARY KEY(context_id,event_id,row_ordinal))
          """);
      for (var table :
          java.util.List.of(
              "build_open_admissions", "build_open_role_facts", "build_open_receipts")) {
        s.execute(
            "CREATE TRIGGER IF NOT EXISTS "
                + table
                + "_immutable BEFORE UPDATE ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'Immutable build admission');END");
        s.execute(
            "CREATE TRIGGER IF NOT EXISTS "
                + table
                + "_no_delete BEFORE DELETE ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'Build admission is not pruned');END");
      }
      for (var table : java.util.List.of("build_open_admissions", "build_open_receipts"))
        s.execute(
            "CREATE TRIGGER IF NOT EXISTS library_revision_"
                + table
                + " AFTER INSERT ON "
                + table
                + " BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE"
                + " singleton=1;END");
    }
  }
}
