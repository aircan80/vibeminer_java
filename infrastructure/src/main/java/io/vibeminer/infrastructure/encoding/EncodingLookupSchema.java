package io.vibeminer.infrastructure.encoding;

import java.sql.*;

/** Derived facts only. The central migration owner must explicitly wire this schema. */
public final class EncodingLookupSchema {
  private EncodingLookupSchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var s = connection.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS en_lookup_artifacts(artifact TEXT NOT NULL,generation TEXT"
              + " NOT NULL,owner TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN"
              + " ('STAGING','READY')),source_proof BLOB NOT NULL"
              + " CHECK(length(source_proof)<=262144),content_count INTEGER,encoding_count"
              + " INTEGER,candidate_count INTEGER,PRIMARY"
              + " KEY(artifact,generation),CHECK(status<>'READY' OR"
              + " (typeof(content_count)='integer' AND typeof(encoding_count)='integer' AND"
              + " typeof(candidate_count)='integer' AND content_count>=0 AND encoding_count>=0 AND"
              + " candidate_count>=0)))");
      s.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS en_lookup_active_writer ON"
              + " en_lookup_artifacts(artifact) WHERE status='STAGING'");
      s.execute(
          "CREATE TABLE IF NOT EXISTS en_lookup_content(artifact TEXT NOT NULL,generation TEXT NOT"
              + " NULL,ckey TEXT NOT NULL CHECK(length(ckey)=32),decoded_bytes INTEGER NOT NULL"
              + " CHECK(decoded_bytes BETWEEN 0 AND 1099511627775),candidate_count INTEGER NOT NULL"
              + " CHECK(candidate_count BETWEEN 1 AND 255),PRIMARY"
              + " KEY(artifact,generation,ckey),FOREIGN KEY(artifact,generation) REFERENCES"
              + " en_lookup_artifacts(artifact,generation) ON DELETE CASCADE)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS en_lookup_candidates(artifact TEXT NOT NULL,generation TEXT"
              + " NOT NULL,ckey TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND"
              + " 254),ekey TEXT NOT NULL CHECK(length(ekey)=32),PRIMARY"
              + " KEY(artifact,generation,ckey,ordinal),FOREIGN KEY(artifact,generation,ckey)"
              + " REFERENCES en_lookup_content(artifact,generation,ckey) ON DELETE CASCADE)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS en_lookup_reverse ON"
              + " en_lookup_candidates(artifact,generation,ekey)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS en_lookup_encoding(artifact TEXT NOT NULL,generation TEXT NOT"
              + " NULL,ekey TEXT NOT NULL CHECK(length(ekey)=32),encoded_bytes INTEGER NOT NULL"
              + " CHECK(encoded_bytes BETWEEN 0 AND 1099511627775),spec_ordinal INTEGER NOT NULL"
              + " CHECK(spec_ordinal>=0),PRIMARY KEY(artifact,generation,ekey),FOREIGN"
              + " KEY(artifact,generation) REFERENCES en_lookup_artifacts(artifact,generation) ON"
              + " DELETE CASCADE)");
      for (var table :
          java.util.List.of("en_lookup_content", "en_lookup_candidates", "en_lookup_encoding")) {
        for (var action : java.util.List.of("INSERT", "UPDATE", "DELETE")) {
          var ref = action.equals("INSERT") ? "NEW" : "OLD";
          s.execute(
              "CREATE TRIGGER IF NOT EXISTS "
                  + table
                  + "_ready_"
                  + action
                  + " BEFORE "
                  + action
                  + " ON "
                  + table
                  + " WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact="
                  + ref
                  + ".artifact AND generation="
                  + ref
                  + ".generation AND status='READY')"
                  + (action.equals("UPDATE")
                      ? " OR EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=NEW.artifact"
                            + " AND generation=NEW.generation AND status='READY')"
                      : "")
                  + " BEGIN SELECT RAISE(ABORT,'Immutable ready EN"
                  + " facts'); END");
        }
      }
      s.execute(
          "CREATE TRIGGER IF NOT EXISTS en_lookup_ready_immutable BEFORE UPDATE ON"
              + " en_lookup_artifacts WHEN OLD.status='READY' BEGIN SELECT RAISE(ABORT,'Immutable"
              + " ready EN generation'); END");
    }
  }
}
