package io.vibeminer.infrastructure.inventory;

import java.sql.Connection;
import java.sql.SQLException;

/** Additive local issuance identities; the sequence is not an upstream inventory generation. */
public final class BuildRetentionBindingSchema {
  private BuildRetentionBindingSchema() {}

  public static void migrate(Connection c) throws SQLException {
    try (var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS build_retention_bindings(issuance INTEGER PRIMARY KEY"
              + " AUTOINCREMENT,inventory_id TEXT NOT NULL UNIQUE,group_id TEXT NOT"
              + " NULL,snapshot_id TEXT NOT NULL,admission_id TEXT NOT NULL,generation TEXT NOT"
              + " NULL REFERENCES build_inventory_generations(id),scope_revision TEXT NOT"
              + " NULL,scope_commitment TEXT NOT NULL,event_id TEXT NOT NULL,observation_sequence"
              + " INTEGER NOT NULL CHECK(typeof(observation_sequence)='integer' AND"
              + " observation_sequence>0),row_ordinal INTEGER NOT NULL"
              + " CHECK(typeof(row_ordinal)='integer' AND row_ordinal>=0),locale TEXT NOT"
              + " NULL,metadata_scope TEXT NOT NULL,policy TEXT NOT NULL,member_count INTEGER NOT"
              + " NULL CHECK(typeof(member_count)='integer' AND member_count>=0))");
      s.execute(
          "CREATE INDEX IF NOT EXISTS build_retention_authorized_target ON"
              + " build_retention_intents(mode,target_id)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS build_inventory_reference_content ON"
              + " build_inventory_references(generation,ckey)");
      s.execute(
          "CREATE TRIGGER IF NOT EXISTS build_retention_binding_immutable BEFORE UPDATE ON"
              + " build_retention_bindings BEGIN SELECT RAISE(ABORT,'Immutable retention"
              + " issuance'); END");
      s.execute(
          "CREATE TRIGGER IF NOT EXISTS build_retention_binding_delete BEFORE DELETE ON"
              + " build_retention_bindings BEGIN SELECT RAISE(ABORT,'Immutable retention"
              + " issuance'); END");
    }
  }
}
