package io.vibeminer.infrastructure.discoveryhistory;

import java.sql.Connection;
import java.sql.SQLException;

/** Additive metadata observations only. Caller owns the single catalog migration transaction. */
public final class DiscoveryHistorySchema {
  private DiscoveryHistorySchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS discovery_history_events(
            event_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id TEXT NOT NULL UNIQUE,
            product_id TEXT NOT NULL,region TEXT NOT NULL,completed_at TEXT NOT NULL,
            parser_revision TEXT NOT NULL,
            build_count INTEGER NOT NULL CHECK(typeof(build_count)='integer' AND build_count BETWEEN 1 AND 64),
            record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record) BETWEEN 1 AND 4194304),
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64))
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS discovery_history_documents(
            event_id TEXT NOT NULL REFERENCES discovery_history_events(event_id),
            role TEXT NOT NULL CHECK(role IN ('versions','cdns')),
            digest TEXT NOT NULL REFERENCES source_blobs(digest),
            source_url TEXT NOT NULL,captured_at TEXT NOT NULL,
            byte_length INTEGER NOT NULL CHECK(typeof(byte_length)='integer' AND byte_length BETWEEN 1 AND 1048576),
            PRIMARY KEY(event_id,role))
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS discovery_history_builds(
            event_id TEXT NOT NULL REFERENCES discovery_history_events(event_id),
            position INTEGER NOT NULL CHECK(typeof(position)='integer' AND position BETWEEN 0 AND 63),
            snapshot_id TEXT NOT NULL REFERENCES snapshots(id),
            versions_row_ordinal INTEGER NOT NULL CHECK(typeof(versions_row_ordinal)='integer' AND versions_row_ordinal BETWEEN 0 AND 9999),
            channel TEXT NOT NULL,
            PRIMARY KEY(event_id,position),UNIQUE(event_id,versions_row_ordinal))
          """);
      statement.execute(
          "CREATE INDEX IF NOT EXISTS discovery_history_filter ON"
              + " discovery_history_events(product_id,region,event_sequence)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS discovery_history_channel ON"
              + " discovery_history_builds(channel,event_id)");
    }
  }
}
