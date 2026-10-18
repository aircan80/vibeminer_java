package io.vibeminer.infrastructure.selectedfile;

import java.sql.*;

/** Additive schema5 metadata; no encoded payload enters SQLite. */
public final class SelectedFileSchema {
  private SelectedFileSchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS canonical_content_slots(namespace TEXT NOT NULL,content_key"
              + " TEXT NOT NULL,encoding_key TEXT NOT NULL,encoded_bytes INTEGER NOT NULL"
              + " CHECK(typeof(encoded_bytes)='integer' AND encoded_bytes>0),token TEXT NOT NULL"
              + " UNIQUE,status TEXT NOT NULL,owner_session TEXT NOT NULL,representation BLOB"
              + " CHECK(representation IS NULL OR length(representation)<=65536),PRIMARY"
              + " KEY(namespace,content_key),CHECK(status IN"
              + " ('PENDING','PUBLISHING','RECOVERY','RETAINED')),CHECK(status<>'RETAINED' OR"
              + " representation IS NOT NULL))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS encoded_file_receipts(namespace TEXT NOT NULL,encoding_key"
              + " TEXT NOT NULL,verifier_semantics TEXT NOT NULL,record BLOB NOT NULL"
              + " CHECK(length(record)<=65536),PRIMARY"
              + " KEY(namespace,encoding_key,verifier_semantics))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS selected_file_associations(snapshot_id TEXT NOT NULL"
              + " REFERENCES snapshots(id),role TEXT NOT NULL,slot_namespace TEXT NOT"
              + " NULL,slot_content_key TEXT NOT NULL,record BLOB NOT NULL"
              + " CHECK(length(record)<=65536),PRIMARY KEY(snapshot_id,role),FOREIGN"
              + " KEY(snapshot_id,role) REFERENCES"
              + " build_object_memberships(snapshot_id,role),FOREIGN"
              + " KEY(slot_namespace,slot_content_key) REFERENCES"
              + " canonical_content_slots(namespace,content_key))");
    }
  }
}
