package io.vibeminer.infrastructure.discoveryhistory;

import java.sql.*;

/** Additive app-owned observations; does not migrate history into successful check baselines. */
public final class BuildLibrarySchema {
  private BuildLibrarySchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var q = connection.createStatement()) {
      q.execute(
          "CREATE TABLE IF NOT EXISTS build_library_admission_counter(singleton INTEGER PRIMARY KEY"
              + " CHECK(singleton=1),ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND"
              + " ordinal>=0))");
      q.execute("INSERT OR IGNORE INTO build_library_admission_counter VALUES(1,0)");
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_product_checks(
            attempt_id TEXT PRIMARY KEY,product_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL UNIQUE CHECK(typeof(ordinal)='integer' AND ordinal>0),
            requested_at TEXT NOT NULL,record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64))
          """);
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_checks(
            attempt_id TEXT PRIMARY KEY,product_id TEXT NOT NULL,region TEXT NOT NULL,
            ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>0),
            requested_at TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('PENDING','SUCCESS','FAILED','CANCELLED')),
            event_id TEXT REFERENCES discovery_history_events(event_id),completed_at TEXT,
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
            parent_attempt_id TEXT REFERENCES build_library_product_checks(attempt_id),parent_sha256 TEXT,
            CHECK((parent_attempt_id IS NULL AND parent_sha256 IS NULL) OR(parent_attempt_id IS NOT NULL AND length(parent_sha256)=64)),UNIQUE(product_id,region,ordinal),
            CHECK((status='PENDING' AND event_id IS NULL AND completed_at IS NULL) OR
                  (status='SUCCESS' AND event_id IS NOT NULL AND completed_at IS NOT NULL) OR
                  (status IN ('FAILED','CANCELLED') AND event_id IS NULL AND completed_at IS NOT NULL)))
          """);
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_registrations(
            registration_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id TEXT NOT NULL UNIQUE REFERENCES discovery_history_events(event_id),
            kind TEXT NOT NULL CHECK(kind IN ('HISTORICAL_IMPORT','SUCCESSFUL_CHECK')),
            check_id TEXT UNIQUE REFERENCES build_library_checks(attempt_id),recorded_at TEXT NOT NULL,
            group_count INTEGER NOT NULL CHECK(typeof(group_count)='integer' AND group_count BETWEEN 1 AND 64),
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
            CHECK((kind='HISTORICAL_IMPORT' AND check_id IS NULL) OR(kind='SUCCESSFUL_CHECK' AND check_id IS NOT NULL)))
          """);
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_groups(
            group_id TEXT PRIMARY KEY,product_id TEXT NOT NULL,
            key_record BLOB NOT NULL CHECK(typeof(key_record)='blob' AND length(key_record) BETWEEN 1 AND 2097152),
            key_sha256 TEXT NOT NULL CHECK(length(key_sha256)=64),
            first_event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
            first_row_ordinal INTEGER NOT NULL CHECK(first_row_ordinal BETWEEN 0 AND 9999),
            first_observation_sequence INTEGER NOT NULL CHECK(first_observation_sequence>0),
            first_seen_at TEXT NOT NULL,new_eligible INTEGER NOT NULL CHECK(new_eligible IN (0,1)),
            acknowledged_at TEXT,seen_sha256 TEXT NOT NULL CHECK(length(seen_sha256)=64))
          """);
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_event_groups(
            event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
            group_id TEXT NOT NULL REFERENCES build_library_groups(group_id),
            first_row_ordinal INTEGER NOT NULL CHECK(first_row_ordinal BETWEEN 0 AND 9999),
            PRIMARY KEY(event_id,group_id))
          """);
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_baselines(
            product_id TEXT NOT NULL,region TEXT NOT NULL,
            event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
            observation_sequence INTEGER NOT NULL CHECK(observation_sequence>0),recorded_at TEXT NOT NULL,
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),PRIMARY KEY(product_id,region))
          """);
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_region_states(
            product_id TEXT NOT NULL,region TEXT NOT NULL,
            finished_ordinal INTEGER NOT NULL CHECK(finished_ordinal>0),
            check_id TEXT NOT NULL REFERENCES build_library_checks(attempt_id),
            latest_successful_event_id TEXT REFERENCES build_library_registrations(event_id),
            check_state TEXT NOT NULL CHECK(check_state IN ('SUCCESS','FAILED','CANCELLED')),
            checked_at TEXT NOT NULL,record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
            PRIMARY KEY(product_id,region))
          """);
      q.execute(
          """
          CREATE TABLE IF NOT EXISTS build_library_snapshot_groups(
            snapshot_id TEXT PRIMARY KEY REFERENCES snapshots(id),
            group_id TEXT NOT NULL REFERENCES build_library_groups(group_id),
            versions_digest TEXT NOT NULL REFERENCES source_blobs(digest),
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64))
          """);
      q.execute(
          "CREATE INDEX IF NOT EXISTS build_library_group_events ON"
              + " build_library_event_groups(group_id,event_id)");
      q.execute(
          "CREATE TABLE IF NOT EXISTS build_library_revision(singleton INTEGER PRIMARY KEY"
              + " CHECK(singleton=1),revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND"
              + " revision>=0))");
      q.execute("INSERT OR IGNORE INTO build_library_revision VALUES(1,0)");
      for (var table :
          java.util.List.of(
              "build_library_product_checks",
              "build_library_checks",
              "build_library_registrations",
              "build_library_groups",
              "build_library_event_groups",
              "build_library_baselines",
              "build_library_region_states",
              "build_library_snapshot_groups",
              "retention_scopes",
              "build_object_memberships")) {
        for (var operation : java.util.List.of("INSERT", "UPDATE", "DELETE"))
          q.execute(
              "CREATE TRIGGER IF NOT EXISTS library_revision_"
                  + table
                  + "_"
                  + operation
                  + " AFTER "
                  + operation
                  + " ON "
                  + table
                  + " BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE"
                  + " singleton=1;END");
      }
      q.execute(
          "CREATE TRIGGER IF NOT EXISTS library_compatible_identity_immutable BEFORE UPDATE ON"
              + " build_library_snapshot_groups BEGIN SELECT RAISE(ABORT,'Immutable full-group"
              + " compatibility');END");
      for (var table :
          java.util.List.of(
              "build_library_registrations",
              "build_library_event_groups",
              "build_library_baselines")) {
        q.execute(
            "CREATE TRIGGER IF NOT EXISTS library_immutable_"
                + table
                + " BEFORE UPDATE ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'Immutable library provenance');END");
      }
      q.execute(
          "CREATE TRIGGER IF NOT EXISTS library_group_identity_immutable BEFORE UPDATE OF"
              + " group_id,product_id,key_record,key_sha256,first_event_id,first_row_ordinal,first_observation_sequence,first_seen_at,new_eligible"
              + " ON build_library_groups BEGIN SELECT RAISE(ABORT,'Immutable library group"
              + " identity');END");
      q.execute(
          "CREATE TRIGGER IF NOT EXISTS library_scope_identity_immutable BEFORE UPDATE OF"
              + " product_id,region ON build_library_region_states BEGIN SELECT"
              + " RAISE(ABORT,'Immutable library scope identity');END");
      q.execute(
          "CREATE TRIGGER IF NOT EXISTS library_ticket_identity_immutable BEFORE UPDATE OF"
              + " attempt_id,product_id,region,ordinal,requested_at,parent_attempt_id,parent_sha256"
              + " ON build_library_checks BEGIN SELECT RAISE(ABORT,'Immutable check"
              + " admission');END");
      q.execute(
          "CREATE TRIGGER IF NOT EXISTS library_group_ack_monotonic BEFORE UPDATE OF"
              + " acknowledged_at ON build_library_groups WHEN OLD.acknowledged_at IS NOT NULL AND"
              + " (NEW.acknowledged_at IS NULL OR NEW.acknowledged_at<>OLD.acknowledged_at) BEGIN"
              + " SELECT RAISE(ABORT,'Open acknowledgment cannot be reset');END");
      for (var table :
          java.util.List.of(
              "build_library_region_states",
              "build_library_baselines",
              "build_library_product_checks",
              "build_library_checks",
              "build_library_snapshot_groups")) {
        q.execute(
            "CREATE TRIGGER IF NOT EXISTS library_no_prune_"
                + table
                + " BEFORE DELETE ON "
                + table
                + " BEGIN SELECT RAISE(ABORT,'Library state is not pruned');END");
      }
      q.execute(
          "CREATE TRIGGER IF NOT EXISTS library_admission_monotonic BEFORE UPDATE OF ordinal ON"
              + " build_library_admission_counter WHEN NEW.ordinal<>OLD.ordinal+1 BEGIN SELECT"
              + " RAISE(ABORT,'Admission order cannot rewind or skip');END");
      q.execute(
          "CREATE TRIGGER IF NOT EXISTS library_revision_monotonic BEFORE UPDATE OF revision ON"
              + " build_library_revision WHEN NEW.revision<=OLD.revision BEGIN SELECT"
              + " RAISE(ABORT,'Library revision cannot rewind');END");
    }
  }
}
