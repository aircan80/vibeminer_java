package io.vibeminer.infrastructure.acquisitioncatalog;

import static io.vibeminer.application.catalog.CatalogException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.ObjectReadLease;
import io.vibeminer.application.ObjectStore;
import io.vibeminer.application.ObjectStoreException;
import io.vibeminer.application.catalog.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.ObjectDigest;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.SourceVerificationEvidence;
import io.vibeminer.domain.StoredObject;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable source aliases and exact-build memberships, referencing one external physical CAS. */
public final class SqliteBuildObjectCatalog implements BuildObjectCatalog {
  private final SqliteCatalog catalog;
  private final ObjectStore store;

  public SqliteBuildObjectCatalog(SqliteCatalog catalog, ObjectStore store) {
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
  }

  @Override
  public void registerSnapshot(BuildSnapshot snapshot) {
    Objects.requireNonNull(snapshot);
    try (var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        catalog.ensureSnapshot(connection, snapshot);
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
  public Optional<VerifiedSourceAlias> findVerifiedSourceAlias(
      ResolvedSourceIdentity source, CancellationToken cancellation) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    try (var connection = catalog.connect()) {
      var alias = readAlias(connection, source);
      if (alias.isEmpty()) return Optional.empty();
      return verifyPhysical(alias.get().object(), cancellation)
          ? Optional.of(alias.get())
          : Optional.empty();
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  @Override
  public BuildObjectReference attachVerifiedObject(
      String snapshotId,
      String role,
      ResolvedSourceIdentity source,
      StoredObject object,
      SourceVerificationEvidence evidence,
      CancellationToken cancellation) {
    BuildObjectReference.validateSnapshotId(snapshotId);
    BuildObjectReference.validateRole(role);
    Objects.requireNonNull(source);
    Objects.requireNonNull(object);
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(cancellation);
    if (!source.verificationSemantics().equals(evidence.verifierSemantics())) {
      throw new CatalogException(
          INVALID_EVIDENCE, "Source verification evidence does not match its declared semantics");
    }
    cancellation.throwIfCancellationRequested();
    // Keep a verified read lease until the reference transaction commits. Physical bytes have
    // already been published; a failed transaction can leave only a harmless physical orphan.
    try (var lease = openVerifiedLease(object, cancellation);
        var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        var reference =
            attachInTransaction(
                connection, snapshotId, role, source, object, evidence, lease, cancellation);
        connection.commit();
        return reference;
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  /**
   * Infrastructure composition only: caller owns transaction and a verified lease through commit.
   */
  public VerifiedSourceAlias registerAliasInTransaction(
      Connection connection,
      ResolvedSourceIdentity source,
      StoredObject object,
      SourceVerificationEvidence evidence,
      ObjectReadLease lease)
      throws SQLException {
    if (connection.getAutoCommit() || !lease.object().equals(object))
      throw new IllegalStateException(
          "Alias registration requires transaction and matching verified lease");
    if (!source.verificationSemantics().equals(evidence.verifierSemantics()))
      throw new CatalogException(INVALID_EVIDENCE, "Source verification semantics mismatch");
    try (var insert =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO catalog_objects(digest,length) VALUES(?,?)")) {
      insert.setString(1, object.digest().value());
      insert.setLong(2, object.length());
      insert.executeUpdate();
    }
    try (var query =
        connection.prepareStatement("SELECT length FROM catalog_objects WHERE digest=?")) {
      query.setString(1, object.digest().value());
      try (var rows = query.executeQuery()) {
        if (!rows.next() || rows.getLong(1) != object.length())
          throw new CatalogException(
              IMMUTABLE_CONFLICT, "Physical object metadata conflicts with its stored identity");
      }
    }
    var alias = readAlias(connection, source);
    if (alias.isPresent() && !alias.get().object().equals(object))
      throw new CatalogException(
          IMMUTABLE_CONFLICT,
          "Immutable source identity already maps to different physical content");
    if (alias.isEmpty()) {
      try (var insert =
          connection.prepareStatement("INSERT INTO source_aliases VALUES(?,?,?,?,?,?)")) {
        bindIdentity(insert, 1, source);
        insert.setString(4, object.digest().value());
        insert.setString(5, evidence.sourceUrl());
        insert.setString(6, evidence.verifiedAt().toString());
        insert.executeUpdate();
      }
    }

    return readAlias(connection, source).orElseThrow();
  }

  /** Infrastructure composition only: preserves existing immutable membership/alias rules. */
  public BuildObjectReference attachInTransaction(
      Connection connection,
      String snapshotId,
      String role,
      ResolvedSourceIdentity source,
      StoredObject object,
      SourceVerificationEvidence evidence,
      ObjectReadLease lease,
      CancellationToken cancellation)
      throws SQLException {
    BuildObjectReference.validateSnapshotId(snapshotId);
    BuildObjectReference.validateRole(role);
    if (catalog.readSnapshot(connection, snapshotId).isEmpty())
      throw new CatalogException(
          UNKNOWN_SNAPSHOT, "Register the exact build before attaching content");
    registerAliasInTransaction(connection, source, object, evidence, lease);
    var existing = readReferences(connection, snapshotId, role);
    if (!existing.isEmpty()) {
      var reference = existing.getFirst();
      if (!reference.source().equals(source) || !reference.object().equals(object))
        throw new CatalogException(
            IMMUTABLE_CONFLICT,
            "Exact build role already references different content or verification semantics");
    } else {
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO build_object_memberships VALUES(?,?,?,?,?,?,?)")) {
        insert.setString(1, snapshotId);
        insert.setString(2, role);
        bindIdentity(insert, 3, source);
        insert.setString(6, evidence.sourceUrl());
        insert.setString(7, evidence.verifiedAt().toString());
        insert.executeUpdate();
      }
    }
    cancellation.throwIfCancellationRequested();
    return readReferences(connection, snapshotId, role).getFirst();
  }

  /**
   * Infrastructure metadata lookup inside a caller transaction; does not assert physical presence.
   */
  public Optional<VerifiedSourceAlias> findAliasInTransaction(
      Connection connection, ResolvedSourceIdentity source) throws SQLException {
    return readAlias(connection, source);
  }

  /** Metadata lookup within a caller-owned transaction; physical verification is separate. */
  public Optional<BuildObjectReference> findInTransaction(
      Connection connection, String snapshotId, String role) throws SQLException {
    BuildObjectReference.validateSnapshotId(snapshotId);
    BuildObjectReference.validateRole(role);
    var values = readReferences(connection, snapshotId, role);
    return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
  }

  @Override
  public List<BuildObjectReference> listReferences(String snapshotId) {
    BuildObjectReference.validateSnapshotId(snapshotId);
    try (var connection = catalog.connect()) {
      return readReferences(connection, snapshotId, null);
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  @Override
  public Optional<BuildObjectReference> findVerifiedObject(
      String snapshotId, String role, CancellationToken cancellation) {
    BuildObjectReference.validateSnapshotId(snapshotId);
    BuildObjectReference.validateRole(role);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    try (var connection = catalog.connect()) {
      var references = readReferences(connection, snapshotId, role);
      if (references.isEmpty()) return Optional.empty();
      var reference = references.getFirst();
      return verifyPhysical(reference.object(), cancellation)
          ? Optional.of(reference)
          : Optional.empty();
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  private Optional<VerifiedSourceAlias> readAlias(
      Connection connection, ResolvedSourceIdentity source) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT a.object_digest,a.source_url,a.verified_at,o.length FROM source_aliases a LEFT"
                + " JOIN catalog_objects o ON o.digest=a.object_digest WHERE a.namespace=? AND"
                + " a.source_key=? AND a.verifier_semantics=?")) {
      bindIdentity(query, 1, source);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        try {
          if (!integerValue(rows.getObject(4)))
            throw new IllegalArgumentException("Missing or invalid physical object metadata");
          var object = new StoredObject(new ObjectDigest(rows.getString(1)), rows.getLong(4));
          var evidence =
              new SourceVerificationEvidence(
                  source.verificationSemantics(),
                  rows.getString(2),
                  Instant.parse(rows.getString(3)));
          return Optional.of(new VerifiedSourceAlias(source, object, evidence));
        } catch (RuntimeException invalid) {
          throw corrupt(invalid);
        }
      }
    }
  }

  private List<BuildObjectReference> readReferences(
      Connection connection, String snapshotId, String role) throws SQLException {
    var sql =
        "SELECT"
            + " m.role,m.namespace,m.source_key,m.verifier_semantics,m.source_url,m.verified_at,a.object_digest,o.length,a.source_url,a.verified_at"
            + " FROM build_object_memberships m LEFT JOIN source_aliases a ON"
            + " a.namespace=m.namespace AND a.source_key=m.source_key AND"
            + " a.verifier_semantics=m.verifier_semantics LEFT JOIN catalog_objects o ON"
            + " o.digest=a.object_digest WHERE m.snapshot_id=?"
            + (role == null ? " ORDER BY m.role" : " AND m.role=?");
    try (var query = connection.prepareStatement(sql)) {
      query.setString(1, snapshotId);
      if (role != null) query.setString(2, role);
      var references = new ArrayList<BuildObjectReference>();
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          try {
            if (rows.getString(7) == null || !integerValue(rows.getObject(8)))
              throw new IllegalArgumentException(
                  "Membership references missing or invalid source/physical metadata");
            var source =
                new ResolvedSourceIdentity(rows.getString(2), rows.getString(3), rows.getString(4));
            // Validate alias attestation too; a valid membership must not conceal corrupt alias
            // provenance.
            new SourceVerificationEvidence(
                source.verificationSemantics(),
                rows.getString(9),
                Instant.parse(rows.getString(10)));
            var object = new StoredObject(new ObjectDigest(rows.getString(7)), rows.getLong(8));
            var evidence =
                new SourceVerificationEvidence(
                    source.verificationSemantics(),
                    rows.getString(5),
                    Instant.parse(rows.getString(6)));
            references.add(
                new BuildObjectReference(snapshotId, rows.getString(1), source, object, evidence));
          } catch (RuntimeException invalid) {
            throw corrupt(invalid);
          }
        }
      }
      if (!references.isEmpty() && catalog.readSnapshot(connection, snapshotId).isEmpty()) {
        throw new CatalogException(
            CORRUPT_METADATA, "Object membership references missing exact-build metadata");
      }
      return List.copyOf(references);
    }
  }

  private boolean verifyPhysical(StoredObject object, CancellationToken cancellation) {
    try {
      var verified = store.verify(object.digest(), cancellation);
      if (verified.isEmpty()) return false;
      if (!verified.get().equals(object))
        throw new CatalogException(
            INTEGRITY_FAILURE, "Catalog object length disagrees with verified physical content");
      return true;
    } catch (ObjectStoreException failure) {
      throw fromStore(failure);
    }
  }

  private ObjectReadLease openVerifiedLease(StoredObject expected, CancellationToken cancellation) {
    try {
      var lease = store.openRead(expected.digest(), cancellation);
      if (!lease.object().equals(expected)) {
        lease.close();
        throw new CatalogException(
            INTEGRITY_FAILURE, "Catalog object length disagrees with verified physical content");
      }
      return lease;
    } catch (ObjectStoreException failure) {
      throw fromStore(failure);
    }
  }

  private static boolean integerValue(Object value) {
    return value instanceof Integer || value instanceof Long;
  }

  private static void bindIdentity(
      PreparedStatement statement, int first, ResolvedSourceIdentity source) throws SQLException {
    statement.setString(first, source.namespace());
    statement.setString(first + 1, source.immutableSourceKey());
    statement.setString(first + 2, source.verificationSemantics());
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException suppressed) {
      failure.addSuppressed(suppressed);
    }
  }

  private static CatalogException fromStore(ObjectStoreException failure) {
    var code =
        switch (failure.code()) {
          case MISSING_OBJECT -> MISSING_OBJECT;
          case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
          default -> STORAGE_UNAVAILABLE;
        };
    return new CatalogException(
        code, "Local object content is unavailable or failed verification", failure);
  }

  private static CatalogException corrupt(Throwable failure) {
    return new CatalogException(
        CORRUPT_METADATA, "Saved object references failed integrity checks", failure);
  }

  private static CatalogException unavailable(SQLException failure) {
    return new CatalogException(
        STORAGE_UNAVAILABLE, "VibeMiner object catalog is unavailable", failure);
  }
}
