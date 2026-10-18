package io.vibeminer.infrastructure.content;

import java.sql.*;

/** Empty additive audit tables; migration never manufactures a successful repair observation. */
public final class MetadataRepairAuditSchema {
  private MetadataRepairAuditSchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var s = connection.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS metadata_repair_attempts(attempt_id TEXT PRIMARY"
              + " KEY,canonical_token TEXT NOT NULL REFERENCES"
              + " canonical_content_slots(token),owner_epoch TEXT NOT NULL,worker_nonce TEXT NOT"
              + " NULL,snapshot_id TEXT NOT NULL REFERENCES snapshots(id),kind TEXT NOT NULL"
              + " CHECK(kind IN ('ROOT','INSTALL')),started_at TEXT NOT NULL,record BLOB NOT NULL"
              + " CHECK(typeof(record)='blob' AND length(record)>0 AND"
              + " length(record)<=262144),record_sha256 TEXT NOT NULL"
              + " CHECK(length(record_sha256)=64))");
      s.execute(
          "CREATE INDEX IF NOT EXISTS metadata_repair_attempts_by_slot ON"
              + " metadata_repair_attempts(canonical_token,started_at,attempt_id)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS metadata_repair_events(attempt_id TEXT NOT NULL REFERENCES"
              + " metadata_repair_attempts(attempt_id),sequence INTEGER NOT NULL"
              + " CHECK(typeof(sequence)='integer' AND sequence BETWEEN 1 AND 4),kind TEXT NOT NULL"
              + " CHECK(kind IN"
              + " ('PROSPECTIVE','STRUCTURE','COMPLETED','CONCLUSIVELY_UNPUBLISHED','UNCERTAIN')),record"
              + " BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record)>0 AND"
              + " length(record)<=262144),record_sha256 TEXT NOT NULL"
              + " CHECK(length(record_sha256)=64),PRIMARY KEY(attempt_id,sequence))");
    }
  }
}
