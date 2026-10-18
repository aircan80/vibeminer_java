package io.vibeminer.infrastructure.buildretention;

import java.sql.*;

/** Immutable actual-encoded inventory associations, installed by central migration 19. */
public final class BuildRetentionSourceSchema {
  private BuildRetentionSourceSchema() {}

  public static void install(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS build_retention_sources(
            target_id TEXT NOT NULL CHECK(length(target_id)=64),
            member_id TEXT NOT NULL CHECK(length(member_id)=64),
            record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record) BETWEEN 1 AND 131072),
            fulfillment_proof TEXT NOT NULL CHECK(length(fulfillment_proof)=64),
            PRIMARY KEY(target_id,member_id))
          """);
      statement.execute(
          """
          CREATE TRIGGER IF NOT EXISTS build_retention_sources_immutable_update
          BEFORE UPDATE ON build_retention_sources BEGIN
            SELECT RAISE(ABORT,'immutable build retention source'); END
          """);
      statement.execute(
          """
          CREATE TRIGGER IF NOT EXISTS build_retention_sources_immutable_delete
          BEFORE DELETE ON build_retention_sources BEGIN
            SELECT RAISE(ABORT,'immutable build retention source'); END
          """);
    }
  }
}
