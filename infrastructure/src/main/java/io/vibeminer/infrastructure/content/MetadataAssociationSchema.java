package io.vibeminer.infrastructure.content;

import java.sql.*;

/** Additive association ledger; migration grants no existing reference a new capability. */
public final class MetadataAssociationSchema {
  private MetadataAssociationSchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS metadata_build_associations(snapshot_id TEXT NOT NULL"
              + " REFERENCES snapshots(id),kind TEXT NOT NULL CHECK(kind IN"
              + " ('ROOT','INSTALL')),role TEXT NOT NULL,canonical_token TEXT NOT NULL REFERENCES"
              + " canonical_content_slots(token),record BLOB NOT NULL CHECK(typeof(record)='blob'"
              + " AND length(record)>0 AND length(record)<=262144),record_sha256 TEXT NOT NULL"
              + " CHECK(length(record_sha256)=64),PRIMARY KEY(snapshot_id,kind),FOREIGN"
              + " KEY(snapshot_id,role) REFERENCES"
              + " build_object_memberships(snapshot_id,role),CHECK((kind='ROOT' AND"
              + " role='ROOT_MANIFEST') OR (kind='INSTALL' AND role='INSTALL_MANIFEST')))");
    }
  }
}
