package io.vibeminer.infrastructure.item;

import java.sql.*;

/** Additive derived metadata only; source payloads remain in the shared CAS. */
public final class ItemProjectionSchema {
  private ItemProjectionSchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS item_projection_artifacts(artifact_id TEXT PRIMARY"
              + " KEY,definition BLOB NOT NULL CHECK(length(definition)<=262144),generation TEXT"
              + " NOT NULL,owner_epoch TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN"
              + " ('STAGING','READY')),section_ordinal INTEGER NOT NULL"
              + " CHECK(typeof(section_ordinal)='integer' AND section_ordinal>=0),physical_rows"
              + " INTEGER,copy_count INTEGER,excluded_sections"
              + " INTEGER,UNIQUE(artifact_id,generation),CHECK(status<>'READY' OR"
              + " (typeof(physical_rows)='integer' AND physical_rows>=0 AND"
              + " typeof(copy_count)='integer' AND copy_count>=0 AND"
              + " typeof(excluded_sections)='integer' AND excluded_sections>=0)))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS item_projection_rows(artifact_id TEXT NOT NULL,generation"
              + " TEXT NOT NULL,record_id INTEGER NOT NULL CHECK(typeof(record_id)='integer' AND"
              + " record_id BETWEEN 0 AND 4294967295),physical_ordinal INTEGER NOT NULL"
              + " CHECK(typeof(physical_ordinal)='integer' AND physical_ordinal>=0),row BLOB NOT"
              + " NULL CHECK(length(row)<=1024),row_sha256 TEXT NOT NULL"
              + " CHECK(length(row_sha256)=64),PRIMARY"
              + " KEY(artifact_id,generation,record_id),UNIQUE(artifact_id,generation,physical_ordinal),FOREIGN"
              + " KEY(artifact_id,generation) REFERENCES"
              + " item_projection_artifacts(artifact_id,generation) ON DELETE CASCADE)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS item_projection_copies(artifact_id TEXT NOT NULL,generation"
              + " TEXT NOT NULL,target_id INTEGER NOT NULL CHECK(typeof(target_id)='integer' AND"
              + " target_id BETWEEN 1 AND 4294967295),source_id INTEGER NOT NULL"
              + " CHECK(typeof(source_id)='integer' AND source_id BETWEEN 1 AND 4294967295),PRIMARY"
              + " KEY(artifact_id,generation,target_id),FOREIGN KEY(artifact_id,generation)"
              + " REFERENCES item_projection_artifacts(artifact_id,generation) ON DELETE CASCADE)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS item_projection_contexts(artifact_id TEXT NOT NULL REFERENCES"
              + " item_projection_artifacts(artifact_id),snapshot_id TEXT NOT NULL,source_role TEXT"
              + " NOT NULL,locale TEXT NOT NULL,evidence BLOB NOT NULL"
              + " CHECK(length(evidence)<=2097152),PRIMARY"
              + " KEY(artifact_id,snapshot_id,source_role,locale),FOREIGN"
              + " KEY(snapshot_id,source_role) REFERENCES"
              + " build_object_memberships(snapshot_id,role))");
    }
  }
}
