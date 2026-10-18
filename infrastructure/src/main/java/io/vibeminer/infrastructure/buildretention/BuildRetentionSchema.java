package io.vibeminer.infrastructure.buildretention;

import java.sql.*;

/** Durable retention ledger tables installed by the shared catalog migration. */
public final class BuildRetentionSchema {
  private BuildRetentionSchema() {}

  public static void install(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS build_retention_intents(
            target_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
            admission_id TEXT NOT NULL, inventory_id TEXT NOT NULL, inventory_generation INTEGER NOT NULL CHECK(typeof(inventory_generation)='integer' AND inventory_generation>0),
            scope_revision TEXT NOT NULL, scope_commitment TEXT NOT NULL,
            mode TEXT NOT NULL CHECK(mode IN ('ON_DEMAND','DOWNLOAD_ALL')),
            generation INTEGER NOT NULL CHECK(typeof(generation)='integer' AND generation>0), authorized_at TEXT NOT NULL,
            member_count INTEGER NOT NULL CHECK(typeof(member_count)='integer' AND member_count>=0), next_ordinal INTEGER NOT NULL CHECK(typeof(next_ordinal)='integer' AND next_ordinal>=0 AND next_ordinal<=member_count),
            maximum_object INTEGER NOT NULL CHECK(typeof(maximum_object)='integer' AND maximum_object>0), maximum_wire INTEGER NOT NULL CHECK(typeof(maximum_wire)='integer' AND maximum_wire>=0),
            maximum_additional INTEGER NOT NULL CHECK(typeof(maximum_additional)='integer' AND maximum_additional>=0), maximum_requests INTEGER NOT NULL CHECK(typeof(maximum_requests)='integer' AND maximum_requests>=0),
            maximum_attempts INTEGER NOT NULL CHECK(typeof(maximum_attempts)='integer' AND maximum_attempts BETWEEN 1 AND 10),
            reserved_wire INTEGER NOT NULL CHECK(typeof(reserved_wire)='integer' AND reserved_wire>=0 AND reserved_wire<=maximum_wire),
            reserved_additional INTEGER NOT NULL CHECK(typeof(reserved_additional)='integer' AND reserved_additional>=0 AND reserved_additional<=maximum_additional),
            reserved_requests INTEGER NOT NULL CHECK(typeof(reserved_requests)='integer' AND reserved_requests>=0 AND reserved_requests<=maximum_requests))
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS build_retention_jobs(
            target_id TEXT NOT NULL REFERENCES build_retention_intents(target_id),
            member_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0),
            content_key TEXT NOT NULL, encoding_key TEXT NOT NULL, encoded_bytes INTEGER NOT NULL CHECK(typeof(encoded_bytes)='integer' AND encoded_bytes>0),
            cost_wire INTEGER NOT NULL CHECK(typeof(cost_wire)='integer' AND cost_wire>=0), cost_additional INTEGER NOT NULL CHECK(typeof(cost_additional)='integer' AND cost_additional>=0),
            cost_requests INTEGER NOT NULL CHECK(typeof(cost_requests)='integer' AND cost_requests>=0), membership_commitment TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
            attempts INTEGER NOT NULL CHECK(typeof(attempts)='integer' AND attempts BETWEEN 0 AND 10), worker_nonce TEXT, owner_epoch TEXT, lease_generation INTEGER CHECK(lease_generation IS NULL OR (typeof(lease_generation)='integer' AND lease_generation>0)),
            fulfillment_proof TEXT, failure_code TEXT, failure_message TEXT,
            PRIMARY KEY(target_id,member_id), UNIQUE(target_id,ordinal),
            CHECK((state='RUNNING')=(worker_nonce IS NOT NULL)),
            CHECK((worker_nonce IS NULL)=(owner_epoch IS NULL)), CHECK((worker_nonce IS NULL)=(lease_generation IS NULL)),
            CHECK((state='SUCCEEDED')=(fulfillment_proof IS NOT NULL)),
            CHECK(state!='FAILED' OR failure_code IS NOT NULL), CHECK((failure_code IS NULL)=(failure_message IS NULL)))
          """);
      statement.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS build_retention_active_group ON"
              + " build_retention_intents(group_id) WHERE mode='DOWNLOAD_ALL'");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS build_retention_job_state ON"
              + " build_retention_jobs(state,target_id,ordinal)");
    }
  }
}
