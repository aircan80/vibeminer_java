package io.vibeminer.infrastructure.retention;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.retention.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.sql.*;
import java.util.*;

/** Additive retention definitions/pins over the single shared catalog schema owner. */
public final class SqliteRetentionRepository implements RetentionRepository {
  private final SqliteCatalog catalog;

  public SqliteRetentionRepository(SqliteCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog);
  }

  @Override
  public RetentionScope pin(RetentionScope scope, CancellationToken cancellation) {
    Objects.requireNonNull(scope);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    try (var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        if (catalog.readSnapshot(connection, scope.snapshotId()).isEmpty())
          throw new RetentionException(
              RetentionException.Code.NOT_FOUND,
              "Register the exact build before pinning its scope");
        for (var requirement : scope.requirements()) {
          cancellation.throwIfCancellationRequested();
          checkFacts(connection, scope, requirement);
        }
        RetentionSql.insert(connection, scope);
        if (!RetentionSql.read(connection, scope.scopeId()).equals(scope))
          throw RetentionSql.corrupt("Conflicting immutable scope");
        try (var query =
            connection.prepareStatement("UPDATE retention_scopes SET pinned=1 WHERE id=?")) {
          query.setString(1, scope.scopeId());
          query.executeUpdate();
        }
        cancellation.throwIfCancellationRequested();
        connection.commit();
        return scope;
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  @Override
  public void release(String scopeId, CancellationToken cancellation) {
    validateId(scopeId);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    try (var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        RetentionSql.read(connection, scopeId);
        try (var query =
            connection.prepareStatement("UPDATE retention_scopes SET pinned=0 WHERE id=?")) {
          query.setString(1, scopeId);
          query.executeUpdate();
        }
        cancellation.throwIfCancellationRequested();
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  @Override
  public RetentionView readView(
      String scopeId, RetentionLimits limits, CancellationToken cancellation) {
    validateId(scopeId);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    try (var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        var selected = RetentionSql.read(connection, scopeId);
        boolean pinned;
        try (var query =
            connection.prepareStatement("SELECT pinned FROM retention_scopes WHERE id=?")) {
          query.setString(1, scopeId);
          try (var rows = query.executeQuery()) {
            pinned = rows.next() && rows.getInt(1) == 1;
          }
        }
        int requirements = selected.requirements().size();
        if (requirements > limits.maximumRequirements()) throw capacity();
        var others = new ArrayList<RetentionScope>();
        try (var query =
            connection.prepareStatement(
                "SELECT id FROM retention_scopes WHERE pinned=1 AND id<>? ORDER BY id LIMIT ?")) {
          query.setString(1, scopeId);
          query.setInt(2, limits.maximumScopes());
          try (var rows = query.executeQuery()) {
            while (rows.next()) {
              cancellation.throwIfCancellationRequested();
              if (others.size() + 1 >= limits.maximumScopes()) throw capacity();
              var scope = RetentionSql.read(connection, rows.getString(1));
              requirements = Math.addExact(requirements, scope.requirements().size());
              if (requirements > limits.maximumRequirements()) throw capacity();
              others.add(scope);
            }
          }
        }
        cancellation.throwIfCancellationRequested();
        connection.commit();
        return new RetentionView(selected, pinned, others);
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private void checkFacts(Connection connection, RetentionScope scope, ScopeRequirement requirement)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT"
                + " r.namespace,r.source_key,r.verifier_semantics,r.encoded_bytes,r.logical_decoded_bytes"
                + " FROM retention_scope_requirements r JOIN retention_scopes s ON s.id=r.scope_id"
                + " WHERE (s.snapshot_id=? AND r.role=?) OR (r.namespace=? AND r.source_key=? AND"
                + " r.verifier_semantics=?)")) {
      query.setString(1, scope.snapshotId());
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
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          if (requirement.source().isPresent() && rows.getString(1) != null) {
            var source = requirement.source().get();
            if (!source.namespace().equals(rows.getString(1))
                || !source.immutableSourceKey().equals(rows.getString(2))
                || !source.verificationSemantics().equals(rows.getString(3)))
              throw RetentionSql.corrupt("Conflicting exact-role source fact");
          }
          sameSize(requirement.encodedBytes(), RetentionSql.size(rows, 4));
          sameSize(requirement.logicalDecodedBytes(), RetentionSql.size(rows, 5));
        }
      }
    }
    try (var query =
        connection.prepareStatement(
            "SELECT m.namespace,m.source_key,m.verifier_semantics,o.length FROM"
                + " build_object_memberships m LEFT JOIN source_aliases a ON"
                + " a.namespace=m.namespace AND a.source_key=m.source_key AND"
                + " a.verifier_semantics=m.verifier_semantics LEFT JOIN catalog_objects o ON"
                + " o.digest=a.object_digest WHERE m.snapshot_id=? AND m.role=?")) {
      query.setString(1, scope.snapshotId());
      query.setString(2, requirement.role());
      try (var rows = query.executeQuery()) {
        if (rows.next()) {
          if (requirement.source().isPresent()) {
            var source = requirement.source().get();
            if (!source.namespace().equals(rows.getString(1))
                || !source.immutableSourceKey().equals(rows.getString(2))
                || !source.verificationSemantics().equals(rows.getString(3)))
              throw RetentionSql.corrupt("Scope conflicts with immutable build membership");
          }
          var length = RetentionSql.size(rows, 4);
          if (length.isEmpty())
            throw RetentionSql.corrupt("Membership has no physical object metadata");
          sameSize(requirement.encodedBytes(), length);
        }
      }
    }
    if (requirement.source().isPresent())
      try (var query =
          connection.prepareStatement(
              "SELECT o.length FROM source_aliases a LEFT JOIN catalog_objects o ON"
                  + " o.digest=a.object_digest WHERE a.namespace=? AND a.source_key=? AND"
                  + " a.verifier_semantics=?")) {
        var source = requirement.source().get();
        query.setString(1, source.namespace());
        query.setString(2, source.immutableSourceKey());
        query.setString(3, source.verificationSemantics());
        try (var rows = query.executeQuery()) {
          if (rows.next()) {
            var length = RetentionSql.size(rows, 1);
            if (length.isEmpty()) throw RetentionSql.corrupt("Source alias has no object metadata");
            sameSize(requirement.encodedBytes(), length);
          }
        }
      }
  }

  private static void sameSize(OptionalLong first, OptionalLong second) {
    if (first.isPresent() && second.isPresent() && first.getAsLong() != second.getAsLong())
      throw RetentionSql.corrupt("Conflicting known retained size facts");
  }

  private static void validateId(String id) {
    if (id == null || !id.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid scope identity");
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException suppressed) {
      failure.addSuppressed(suppressed);
    }
  }

  private static RetentionException capacity() {
    return new RetentionException(
        RetentionException.Code.CAPACITY_EXCEEDED, "Retention view exceeds declared limits");
  }

  private static RetentionException unavailable(SQLException failure) {
    return new RetentionException(
        RetentionException.Code.STORAGE_UNAVAILABLE, "Retention catalog is unavailable", failure);
  }
}
