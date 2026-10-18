package io.vibeminer.infrastructure.buildreadiness;

import java.sql.*;

/** Additive latest dated summaries. The shared schema owner supplies the migration transaction. */
public final class BuildReadinessSchema {
  private BuildReadinessSchema() {}

  public static void install(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS build_readiness_observations(
            context_id TEXT PRIMARY KEY CHECK(length(context_id)=64),
            record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record) BETWEEN 1 AND 16384),
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64))
          """);
    }
  }
}
