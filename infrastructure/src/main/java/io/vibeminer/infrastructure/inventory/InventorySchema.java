package io.vibeminer.infrastructure.inventory;

import java.sql.*;

/** Derived all-variant membership state; central schema owner wires revision 18 separately. */
public final class InventorySchema {
  private InventorySchema() {}

  public static void migrate(Connection c) throws SQLException {
    try (var s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS build_inventory_generations(id TEXT PRIMARY KEY,snapshot_id"
              + " TEXT NOT NULL,admission_id TEXT NOT NULL,en_artifact TEXT NOT NULL,en_generation"
              + " TEXT NOT NULL,scope TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN"
              + " ('STAGING','READY')),row_count INTEGER NOT NULL DEFAULT 0,derived_bytes INTEGER"
              + " NOT NULL DEFAULT 0,batches INTEGER NOT NULL DEFAULT 0,member_count INTEGER)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS build_inventory_sources(generation TEXT NOT NULL REFERENCES"
              + " build_inventory_generations(id) ON DELETE CASCADE,role TEXT NOT NULL,proof BLOB"
              + " NOT NULL CHECK(length(proof)<=262144),complete INTEGER NOT NULL DEFAULT 0"
              + " CHECK(complete IN (0,1)),expected_rows INTEGER,PRIMARY KEY(generation,role))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS build_inventory_references(generation TEXT NOT NULL,role TEXT"
              + " NOT NULL,ordinal INTEGER NOT NULL,block_ordinal INTEGER NOT NULL,key_ref TEXT NOT"
              + " NULL,declared_bytes INTEGER,raw_fact TEXT NOT NULL"
              + " CHECK(length(raw_fact)<=8192),resolution TEXT,ckey TEXT,ekey TEXT,decoded_bytes"
              + " INTEGER,encoded_bytes INTEGER,PRIMARY KEY(generation,role,ordinal),FOREIGN"
              + " KEY(generation,role) REFERENCES build_inventory_sources(generation,role) ON"
              + " DELETE CASCADE)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS build_inventory_members(generation TEXT NOT NULL REFERENCES"
              + " build_inventory_generations(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,ckey"
              + " TEXT NOT NULL,ekey TEXT NOT NULL,decoded_bytes INTEGER NOT NULL,encoded_bytes"
              + " INTEGER NOT NULL,PRIMARY KEY(generation,ordinal),UNIQUE(generation,ckey))");
      s.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS build_inventory_ready_context ON"
              + " build_inventory_generations(snapshot_id,admission_id,en_artifact,en_generation,scope)"
              + " WHERE status='READY'");
      for (var table :
          java.util.List.of(
              "build_inventory_sources", "build_inventory_references", "build_inventory_members")) {
        for (var action : java.util.List.of("INSERT", "UPDATE", "DELETE")) {
          String ref = action.equals("INSERT") ? "NEW" : "OLD";
          s.execute(
              "CREATE TRIGGER IF NOT EXISTS "
                  + table
                  + "_ready_"
                  + action
                  + " BEFORE "
                  + action
                  + " ON "
                  + table
                  + " WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id="
                  + ref
                  + ".generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable"
                  + " inventory'); END");
        }
      }
      for (var table :
          java.util.List.of(
              "build_inventory_sources", "build_inventory_references", "build_inventory_members")) {
        s.execute(
            "CREATE TRIGGER IF NOT EXISTS "
                + table
                + "_ready_target BEFORE UPDATE ON "
                + table
                + " WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=NEW.generation"
                + " AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory target');"
                + " END");
      }
      s.execute(
          "CREATE TRIGGER IF NOT EXISTS build_inventory_ready_delete BEFORE DELETE ON"
              + " build_inventory_generations WHEN OLD.status='READY' BEGIN SELECT"
              + " RAISE(ABORT,'Immutable inventory generation'); END");
      s.execute(
          "CREATE TRIGGER IF NOT EXISTS build_inventory_ready_immutable BEFORE UPDATE ON"
              + " build_inventory_generations WHEN OLD.status='READY' BEGIN SELECT"
              + " RAISE(ABORT,'Immutable inventory'); END");
    }
  }
}
