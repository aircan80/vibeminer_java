package io.vibeminer.infrastructure.content;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.retention.RetentionException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Per-consumer atomic attachment; publisher claims and original receipts are never changed. */
public final class SqliteMetadataAssociationRepository implements MetadataAssociationRepository {
  private final SqliteCatalog catalog;
  private final ObjectStore store;
  private final FileObjectStore.StoreSession session;
  private final SqliteBuildObjectCatalog objects;
  private final SqliteRetentionRepository retention;

  public SqliteMetadataAssociationRepository(
      SqliteCatalog catalog, ObjectStore store, FileObjectStore.StoreSession session) {
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.session = Objects.requireNonNull(session);
    if (!(store instanceof FileObjectStore files) || files.contentSession() != session)
      throw new IllegalArgumentException("Content session belongs to another object store");
    objects = new SqliteBuildObjectCatalog(catalog, store);
    retention = new SqliteRetentionRepository(catalog);
  }

  @Override
  public MetadataBuildAssociation attach(
      MetadataBuildAssociation association, RetentionScope scope, CancellationToken cancellation) {
    Objects.requireNonNull(association);
    Objects.requireNonNull(scope);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    ready();
    try {
      MetadataAssociationRecords.encode(association);
    } catch (IllegalArgumentException failure) {
      throw fail(INVALID_REQUEST, "Association cannot be represented without loss", failure);
    }
    if (!scope.equals(expectedScope(association)))
      throw fail(
          INVALID_REQUEST,
          "Retention closure must describe exact actual metadata and configuration",
          null);
    try (var configLease =
            store.openRead(
                association.declaration().configuration().object().digest(), cancellation);
        var actualLease =
            store.openRead(association.actualRepresentation().object().digest(), cancellation);
        var connection = catalog.connect()) {
      if (!configLease.object().equals(association.declaration().configuration().object())
          || !actualLease.object().equals(association.actualRepresentation().object()))
        throw fail(INTEGRITY_FAILURE, "Verified physical lengths differ", null);
      connection.setAutoCommit(false);
      try {
        var declaration = association.declaration();
        var id = declaration.build().snapshotId();
        var stored =
            catalog
                .readSnapshot(connection, id)
                .orElseThrow(
                    () -> fail(INVALID_STATE, "Register exact build before association", null));
        CanonicalContentGuards.check(connection, declaration.slot());
        requireCanonical(connection, association);
        requireAlias(connection, declaration.configuration());
        requireAlias(connection, association.actualRepresentation().originalAlias());
        var normalized =
            new MetadataBuildAssociation(
                new MetadataDeclaration(
                    stored,
                    declaration.configuration(),
                    declaration.kind(),
                    declaration.slot(),
                    declaration.preferredEncodingKey(),
                    declaration.declaredEncodedBytes(),
                    declaration.declaredDecodedBytes()),
                association.canonicalReservationToken(),
                association.actualRepresentation(),
                association.associatedAt());
        var existing = read(connection, id, declaration.kind());
        if (existing.isPresent() && !sameImmutable(existing.get(), normalized))
          throw fail(INTEGRITY_FAILURE, "Immutable metadata association conflicts", null);
        var config = declaration.configuration();
        var actual = association.actualRepresentation().originalAlias();
        objects.attachInTransaction(
            connection,
            id,
            "BUILD_CONFIG",
            config.source(),
            config.object(),
            config.evidence(),
            configLease,
            cancellation);
        objects.attachInTransaction(
            connection,
            id,
            role(declaration.kind()),
            actual.source(),
            actual.object(),
            actual.evidence(),
            actualLease,
            cancellation);
        if (existing.isEmpty()) insert(connection, normalized);
        retention.pinInTransaction(connection, scope, cancellation);
        var result = read(connection, id, declaration.kind()).orElseThrow();
        requireReferences(connection, result);
        cancellation.throwIfCancellationRequested();
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException failure) {
      throw fail(STORAGE_UNAVAILABLE, "Cannot attach metadata association", failure);
    } catch (ObjectStoreException failure) {
      throw storeFailure(failure);
    } catch (CatalogException failure) {
      throw fail(
          failure.code() == CatalogException.Code.STORAGE_UNAVAILABLE
              ? STORAGE_UNAVAILABLE
              : INTEGRITY_FAILURE,
          "Immutable catalog references conflict",
          failure);
    } catch (RetentionException failure) {
      throw fail(
          failure.code() == RetentionException.Code.STORAGE_UNAVAILABLE
              ? STORAGE_UNAVAILABLE
              : INTEGRITY_FAILURE,
          "Immutable retention facts conflict",
          failure);
    }
  }

  @Override
  public Optional<MetadataBuildAssociation> find(
      String snapshotId, MetadataStructureAttestation.Kind kind, CancellationToken cancellation) {
    BuildObjectReference.validateSnapshotId(snapshotId);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    ready();
    try (var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        var result = read(connection, snapshotId, kind);
        if (result.isPresent()) requireReferences(connection, result.get());
        cancellation.throwIfCancellationRequested();
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException failure) {
      throw fail(STORAGE_UNAVAILABLE, "Cannot read metadata association", failure);
    } catch (CatalogException failure) {
      throw fail(
          failure.code() == CatalogException.Code.STORAGE_UNAVAILABLE
              ? STORAGE_UNAVAILABLE
              : INTEGRITY_FAILURE,
          "Original association references are invalid",
          failure);
    }
  }

  private void requireCanonical(Connection connection, MetadataBuildAssociation association)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT"
                + " namespace,content_key,encoding_key,encoded_bytes,status,length(representation),representation,owner_session"
                + " FROM canonical_content_slots WHERE token=?")) {
      query.setString(1, association.canonicalReservationToken().toString());
      try (var row = query.executeQuery()) {
        if (!row.next()) throw fail(INVALID_STATE, "Canonical reservation is absent", null);
        if (!session.epoch().toString().equals(row.getString(8)))
          throw fail(
              RECOVERY_REQUIRED, "Canonical claim belongs to a different store session", null);
        if (!row.getString(5).equals("RETAINED"))
          throw fail(RECOVERY_REQUIRED, "Canonical metadata is not retained", null);
        var d = association.declaration();
        var actual = association.actualRepresentation();
        if (!row.getString(1).equals(d.slot().namespace())
            || !row.getString(2).equals(d.slot().contentKey())
            || !row.getString(3).equals(actual.actualEncodingKey())
            || !(row.getObject(4) instanceof Long || row.getObject(4) instanceof Integer)
            || row.getLong(4) != actual.encodedBytes()
            || row.getLong(6) < 1
            || row.getLong(6) > CanonicalContentRecords.MAX_BYTES)
          throw fail(INTEGRITY_FAILURE, "Canonical header differs from association", null);
        try {
          if (!CanonicalContentRecords.decode(row.getBytes(7)).equals(actual))
            throw fail(INTEGRITY_FAILURE, "Canonical original proof differs", null);
        } catch (IllegalArgumentException failure) {
          throw fail(INTEGRITY_FAILURE, "Invalid canonical proof", failure);
        }
        ContentProofPolicy.requirePublicationReady(actual);
      }
    }
  }

  private void requireAlias(Connection connection, VerifiedSourceAlias expected)
      throws SQLException {
    if (!objects
        .findAliasInTransaction(connection, expected.source())
        .filter(expected::equals)
        .isPresent())
      throw fail(INTEGRITY_FAILURE, "Original source alias missing or changed", null);
  }

  private void requireReferences(Connection connection, MetadataBuildAssociation association)
      throws SQLException {
    requireAlias(connection, association.declaration().configuration());
    requireAlias(connection, association.actualRepresentation().originalAlias());
    requireReference(
        connection,
        association.declaration().build().snapshotId(),
        "BUILD_CONFIG",
        association.declaration().configuration());
    requireReference(
        connection,
        association.declaration().build().snapshotId(),
        role(association.declaration().kind()),
        association.actualRepresentation().originalAlias());
  }

  private void requireReference(
      Connection connection, String snapshot, String role, VerifiedSourceAlias expected)
      throws SQLException {
    var reference =
        objects
            .findInTransaction(connection, snapshot, role)
            .orElseThrow(() -> fail(INTEGRITY_FAILURE, "Original build membership missing", null));
    if (!reference.source().equals(expected.source())
        || !reference.object().equals(expected.object())
        || !reference.evidence().equals(expected.evidence()))
      throw fail(INTEGRITY_FAILURE, "Original build membership changed", null);
  }

  private Optional<MetadataBuildAssociation> read(
      Connection connection, String snapshot, MetadataStructureAttestation.Kind kind)
      throws SQLException {
    try (var query =
        connection.prepareStatement(
            "SELECT role,canonical_token,length(record),record,record_sha256 FROM"
                + " metadata_build_associations WHERE snapshot_id=? AND kind=?")) {
      query.setString(1, snapshot);
      query.setString(2, kind.name());
      try (var row = query.executeQuery()) {
        if (!row.next()) return Optional.empty();
        if (row.getLong(3) < 1 || row.getLong(3) > MetadataAssociationRecords.MAX_BYTES)
          throw fail(INTEGRITY_FAILURE, "Association record is oversized", null);
        var bytes = row.getBytes(4);
        if (!sha(bytes).equals(row.getString(5)))
          throw fail(INTEGRITY_FAILURE, "Association record checksum differs", null);
        var build =
            catalog
                .readSnapshot(connection, snapshot)
                .orElseThrow(() -> fail(INTEGRITY_FAILURE, "Association build is missing", null));
        try {
          var result = MetadataAssociationRecords.decode(bytes, build);
          if (result.declaration().kind() != kind
              || !row.getString(1).equals(role(kind))
              || !row.getString(2).equals(result.canonicalReservationToken().toString()))
            throw fail(INTEGRITY_FAILURE, "Association indexed facts differ", null);
          return Optional.of(result);
        } catch (IllegalArgumentException failure) {
          throw fail(INTEGRITY_FAILURE, "Malformed association record", failure);
        }
      }
    }
  }

  private static void insert(Connection connection, MetadataBuildAssociation value)
      throws SQLException {
    var bytes = MetadataAssociationRecords.encode(value);
    try (var query =
        connection.prepareStatement(
            "INSERT INTO metadata_build_associations VALUES(?,?,?,?,?,?)")) {
      query.setString(1, value.declaration().build().snapshotId());
      query.setString(2, value.declaration().kind().name());
      query.setString(3, role(value.declaration().kind()));
      query.setString(4, value.canonicalReservationToken().toString());
      query.setBytes(5, bytes);
      query.setString(6, sha(bytes));
      query.executeUpdate();
    }
  }

  private static boolean sameImmutable(
      MetadataBuildAssociation first, MetadataBuildAssociation next) {
    return first.declaration().equals(next.declaration())
        && first.canonicalReservationToken().equals(next.canonicalReservationToken())
        && first.actualRepresentation().equals(next.actualRepresentation());
  }

  private static RetentionScope expectedScope(MetadataBuildAssociation a) {
    var config = a.declaration().configuration();
    var actual = a.actualRepresentation();
    return new RetentionScope(
        a.declaration().build().snapshotId(),
        a.declaration().kind() == MetadataStructureAttestation.Kind.ROOT
            ? ScopeKind.ROOT_METADATA
            : ScopeKind.INSTALL_METADATA,
        List.of(
            new ScopeRequirement(
                "BUILD_CONFIG",
                Optional.of(config.source()),
                OptionalLong.of(config.object().length()),
                OptionalLong.of(config.object().length())),
            new ScopeRequirement(
                role(a.declaration().kind()),
                Optional.of(actual.originalAlias().source()),
                OptionalLong.of(actual.encodedBytes()),
                OptionalLong.of(actual.decodedAttestation().orElseThrow().actualDecodedBytes()))));
  }

  private static String role(MetadataStructureAttestation.Kind kind) {
    return kind == MetadataStructureAttestation.Kind.ROOT ? "ROOT_MANIFEST" : "INSTALL_MANIFEST";
  }

  private void ready() {
    try {
      session.beforeProducerOperation();
    } catch (IllegalStateException failure) {
      throw fail(STORAGE_UNAVAILABLE, "Content session is not ready", failure);
    }
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException suppressed) {
      failure.addSuppressed(suppressed);
    }
  }

  private static ContentClaimException storeFailure(ObjectStoreException failure) {
    var code =
        switch (failure.code()) {
          case MISSING_OBJECT -> MISSING_LOCAL;
          case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
          case LIMIT_EXCEEDED -> INVALID_REQUEST;
          default -> STORAGE_UNAVAILABLE;
        };
    return fail(code, "Original metadata object is unavailable", failure);
  }

  private static ContentClaimException fail(
      ContentClaimException.Code code, String message, Throwable cause) {
    return new ContentClaimException(code, message, cause);
  }
}
