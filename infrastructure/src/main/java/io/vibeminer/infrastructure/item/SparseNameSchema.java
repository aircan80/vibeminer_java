package io.vibeminer.infrastructure.item;

import java.sql.*;

/** Independent derived-name rows only; existing Item and retained payload tables are unchanged. */
public final class SparseNameSchema {
  private SparseNameSchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS sparse_name_artifacts(
            artifact_id TEXT PRIMARY KEY,
            definition BLOB NOT NULL CHECK(typeof(definition)='blob' AND length(definition)<=262144),
            definition_sha256 TEXT NOT NULL CHECK(length(definition_sha256)=64),
            generation TEXT NOT NULL, owner_epoch TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('STAGING','READY')),
            copy_proof_version INTEGER NOT NULL DEFAULT 1 CHECK(copy_proof_version=1),
            section_ordinal INTEGER NOT NULL
              CHECK(typeof(section_ordinal)='integer' AND section_ordinal>=0),
            physical_rows INTEGER, copy_count INTEGER, excluded_sections INTEGER,
            projected_utf8_bytes INTEGER,
            UNIQUE(artifact_id,generation),
            CHECK(status<>'READY' OR (
              typeof(physical_rows)='integer' AND physical_rows>=0 AND
              typeof(copy_count)='integer' AND copy_count>=0 AND
              typeof(excluded_sections)='integer' AND excluded_sections>=0 AND
              typeof(projected_utf8_bytes)='integer' AND projected_utf8_bytes>=0)))
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS sparse_name_rows(
            artifact_id TEXT NOT NULL, generation TEXT NOT NULL,
            record_id INTEGER NOT NULL
              CHECK(typeof(record_id)='integer' AND record_id BETWEEN 1 AND 4294967295),
            physical_ordinal INTEGER NOT NULL
              CHECK(typeof(physical_ordinal)='integer' AND physical_ordinal>=0),
            utf8_bytes INTEGER NOT NULL
              CHECK(typeof(utf8_bytes)='integer' AND utf8_bytes BETWEEN 0 AND 65530),
            record BLOB NOT NULL CHECK(
              typeof(record)='blob' AND length(record)=40+utf8_bytes AND length(record)<=65600 AND
              hex(substr(record,1,4))='564D4E01' AND
              hex(substr(record,5,8))=printf('%016X',record_id) AND
              hex(substr(record,13,4))=printf('%08X',physical_ordinal) AND
              hex(substr(record,37,4))=printf('%08X',utf8_bytes)),
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
            PRIMARY KEY(artifact_id,generation,record_id),
            UNIQUE(artifact_id,generation,physical_ordinal),
            FOREIGN KEY(artifact_id,generation)
              REFERENCES sparse_name_artifacts(artifact_id,generation) ON DELETE CASCADE)
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS sparse_name_copies(
            artifact_id TEXT NOT NULL, generation TEXT NOT NULL,
            target_id INTEGER NOT NULL
              CHECK(typeof(target_id)='integer' AND target_id BETWEEN 1 AND 4294967295),
            source_id INTEGER NOT NULL
              CHECK(typeof(source_id)='integer' AND source_id BETWEEN 1 AND 4294967295),
            record BLOB NOT NULL CHECK(
              typeof(record)='blob' AND length(record)=20 AND
              hex(substr(record,1,4))='564D4E02' AND
              hex(substr(record,5,8))=printf('%016X',target_id) AND
              hex(substr(record,13,8))=printf('%016X',source_id)),
            record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
            PRIMARY KEY(artifact_id,generation,target_id),
            FOREIGN KEY(artifact_id,generation)
              REFERENCES sparse_name_artifacts(artifact_id,generation) ON DELETE CASCADE)
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS sparse_name_contexts(
            artifact_id TEXT NOT NULL REFERENCES sparse_name_artifacts(artifact_id),
            snapshot_id TEXT NOT NULL, source_role TEXT NOT NULL, locale TEXT NOT NULL,
            evidence BLOB NOT NULL CHECK(typeof(evidence)='blob' AND length(evidence)<=2097152),
            evidence_sha256 TEXT NOT NULL CHECK(length(evidence_sha256)=64),
            PRIMARY KEY(artifact_id,snapshot_id,source_role,locale),
            FOREIGN KEY(snapshot_id,source_role)
              REFERENCES build_object_memberships(snapshot_id,role))
          """);
    }
  }
}
