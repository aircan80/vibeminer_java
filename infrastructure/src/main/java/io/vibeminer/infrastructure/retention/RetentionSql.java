package io.vibeminer.infrastructure.retention;

import io.vibeminer.application.retention.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Shared schema/row mechanics. No payload bytes or physical availability are stored here. */
public final class RetentionSql {
  private RetentionSql() {}

  public static void migrate(Connection connection, int oldVersion) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS retention_scopes(id TEXT PRIMARY KEY,snapshot_id TEXT NOT"
              + " NULL REFERENCES snapshots(id),kind TEXT NOT NULL,pinned INTEGER NOT NULL"
              + " CHECK(typeof(pinned)='integer' AND pinned IN (0,1)),created_at TEXT NOT NULL)");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS retention_scope_requirements(scope_id TEXT NOT NULL"
              + " REFERENCES retention_scopes(id),role TEXT NOT NULL,namespace TEXT,source_key"
              + " TEXT,verifier_semantics TEXT,encoded_bytes INTEGER CHECK(encoded_bytes IS NULL OR"
              + " (typeof(encoded_bytes)='integer' AND encoded_bytes>=0)),logical_decoded_bytes"
              + " INTEGER CHECK(logical_decoded_bytes IS NULL OR"
              + " (typeof(logical_decoded_bytes)='integer' AND logical_decoded_bytes>=0)),PRIMARY"
              + " KEY(scope_id,role),CHECK((namespace IS NULL AND source_key IS NULL AND"
              + " verifier_semantics IS NULL) OR (namespace IS NOT NULL AND source_key IS NOT NULL"
              + " AND verifier_semantics IS NOT NULL)))");
    }
    if (oldVersion >= 4) return;
    // One explicit compatibility root per preexisting exact-build role preserves all content.
    // Per-role roots also avoid an unbounded legacy definition while retaining unknown totals.
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT"
                    + " m.snapshot_id,m.role,m.namespace,m.source_key,m.verifier_semantics,a.object_digest,o.length"
                    + " FROM build_object_memberships m LEFT JOIN source_aliases a ON"
                    + " a.namespace=m.namespace AND a.source_key=m.source_key AND"
                    + " a.verifier_semantics=m.verifier_semantics LEFT JOIN catalog_objects o ON"
                    + " o.digest=a.object_digest ORDER BY m.snapshot_id,m.role")) {
      while (rows.next()) {
        try {
          if (!(rows.getObject(7) instanceof Integer || rows.getObject(7) instanceof Long)
              || rows.getString(6) == null)
            throw corrupt("Legacy role is missing source/object metadata");
          new ObjectDigest(rows.getString(6));
          var source =
              new ResolvedSourceIdentity(rows.getString(3), rows.getString(4), rows.getString(5));
          var requirement =
              new ScopeRequirement(
                  rows.getString(2),
                  Optional.of(source),
                  OptionalLong.of(rows.getLong(7)),
                  OptionalLong.empty());
          insert(
              connection,
              new RetentionScope(
                  rows.getString(1), ScopeKind.LEGACY_RETAINED, List.of(requirement)));
        } catch (IllegalArgumentException invalid) {
          throw new RetentionException(
              RetentionException.Code.INTEGRITY_FAILURE,
              "Cannot migrate invalid retained content facts",
              invalid);
        }
      }
    }
  }

  static void insert(Connection connection, RetentionScope scope) throws SQLException {
    int inserted;
    try (var query =
        connection.prepareStatement("INSERT OR IGNORE INTO retention_scopes VALUES(?,?,?,?,?)")) {
      query.setString(1, scope.scopeId());
      query.setString(2, scope.snapshotId());
      query.setString(3, scope.kind().name());
      query.setInt(4, 1);
      query.setString(5, Instant.now().toString());
      inserted = query.executeUpdate();
    }
    if (inserted == 0) return;
    for (var requirement : scope.requirements())
      try (var query =
          connection.prepareStatement(
              "INSERT INTO retention_scope_requirements VALUES(?,?,?,?,?,?,?)")) {
        query.setString(1, scope.scopeId());
        query.setString(2, requirement.role());
        if (requirement.source().isPresent()) {
          var source = requirement.source().get();
          query.setString(3, source.namespace());
          query.setString(4, source.immutableSourceKey());
          query.setString(5, source.verificationSemantics());
        } else {
          query.setNull(3, Types.VARCHAR);
          query.setNull(4, Types.VARCHAR);
          query.setNull(5, Types.VARCHAR);
        }
        bindSize(query, 6, requirement.encodedBytes());
        bindSize(query, 7, requirement.logicalDecodedBytes());
        query.executeUpdate();
      }
  }

  static RetentionScope read(Connection connection, String id) throws SQLException {
    String snapshot, kind;
    try (var query =
        connection.prepareStatement(
            "SELECT snapshot_id,kind,pinned,created_at FROM retention_scopes WHERE id=?")) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next())
          throw new RetentionException(
              RetentionException.Code.NOT_FOUND, "Retention scope not found");
        snapshot = rows.getString(1);
        kind = rows.getString(2);
        if (!(rows.getObject(3) instanceof Integer || rows.getObject(3) instanceof Long)
            || rows.getInt(3) < 0
            || rows.getInt(3) > 1) throw corrupt("Invalid pin state");
        try {
          Instant.parse(rows.getString(4));
        } catch (RuntimeException invalid) {
          throw corrupt("Invalid retention timestamp");
        }
      }
    }
    var requirements = new ArrayList<ScopeRequirement>();
    try (var query =
        connection.prepareStatement(
            "SELECT"
                + " role,namespace,source_key,verifier_semantics,encoded_bytes,logical_decoded_bytes"
                + " FROM retention_scope_requirements WHERE scope_id=? ORDER BY role LIMIT 257")) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          try {
            var namespace = rows.getString(2);
            var key = rows.getString(3);
            var semantics = rows.getString(4);
            if (namespace == null && (key != null || semantics != null))
              throw corrupt("Incomplete source identity");
            Optional<ResolvedSourceIdentity> source =
                namespace == null
                    ? Optional.empty()
                    : Optional.of(new ResolvedSourceIdentity(namespace, key, semantics));
            requirements.add(
                new ScopeRequirement(rows.getString(1), source, size(rows, 5), size(rows, 6)));
          } catch (IllegalArgumentException invalid) {
            throw new RetentionException(
                RetentionException.Code.INTEGRITY_FAILURE,
                "Invalid retention requirement",
                invalid);
          }
        }
      }
    }
    try {
      var scope = new RetentionScope(snapshot, ScopeKind.valueOf(kind), requirements);
      if (!scope.scopeId().equals(id))
        throw corrupt("Scope definition does not match its immutable identity");
      try (var query = connection.prepareStatement("SELECT 1 FROM snapshots WHERE id=?")) {
        query.setString(1, snapshot);
        try (var rows = query.executeQuery()) {
          if (!rows.next()) throw corrupt("Retention scope has no exact-build snapshot");
        }
      }
      return scope;
    } catch (IllegalArgumentException invalid) {
      throw new RetentionException(
          RetentionException.Code.INTEGRITY_FAILURE, "Invalid retention definition", invalid);
    }
  }

  static OptionalLong size(ResultSet rows, int column) throws SQLException {
    Object value = rows.getObject(column);
    if (value == null) return OptionalLong.empty();
    if (!(value instanceof Integer || value instanceof Long) || rows.getLong(column) < 0)
      throw corrupt("Invalid retained size fact");
    return OptionalLong.of(rows.getLong(column));
  }

  private static void bindSize(PreparedStatement query, int index, OptionalLong size)
      throws SQLException {
    if (size.isPresent()) query.setLong(index, size.getAsLong());
    else query.setNull(index, Types.BIGINT);
  }

  static RetentionException corrupt(String message) {
    return new RetentionException(RetentionException.Code.INTEGRITY_FAILURE, message);
  }
}
