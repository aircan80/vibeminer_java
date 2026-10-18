package io.vibeminer.infrastructure.selectedfile;

import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import java.sql.*;
import java.util.*;

/**
 * Durable one-representation slots and bounded typed associations; no networking or payload copies.
 */
public final class SqliteSelectedFileCatalog implements SelectedFileCatalog {
  private final SqliteCatalog catalog;
  private final ObjectStore store;
  private final UUID session;
  private final SqliteBuildObjectCatalog objects;
  private final SqliteRetentionRepository retention;

  public SqliteSelectedFileCatalog(SqliteCatalog catalog, ObjectStore store) {
    this(catalog, store, UUID.randomUUID());
  }

  public SqliteSelectedFileCatalog(SqliteCatalog catalog, ObjectStore store, UUID session) {
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.session = Objects.requireNonNull(session);
    objects = new SqliteBuildObjectCatalog(catalog, store);
    retention = new SqliteRetentionRepository(catalog);
  }

  private record Row(CanonicalReservation reservation, UUID owner) {}

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  @Override
  public Optional<CanonicalReservation> lookup(ContentSlotKey slot, CancellationToken token) {
    Objects.requireNonNull(slot);
    return tx(token, c -> readSlot(c, slot).map(Row::reservation));
  }

  @Override
  public CanonicalReservation reserve(
      ContentSlotKey slot, String encodingKey, long encodedBytes, CancellationToken token) {
    Objects.requireNonNull(slot);
    var key = ContentRepresentation.sourceIdentity(encodingKey).immutableSourceKey();
    if (encodedBytes < 1)
      throw new IllegalArgumentException("Known positive encoded size required");
    return tx(
        token,
        c -> {
          var existing = readSlot(c, slot);
          if (existing.isPresent()) {
            var row = existing.get();
            var reservation = row.reservation();
            if (!reservation.actualEncodingKey().equals(key))
              throw failure(
                  reservation.status() == CanonicalReservation.Status.RETAINED
                      ? REPLAN_REQUIRED
                      : BUSY,
                  "Canonical content already owns a different encoding");
            if (reservation.encodedBytes() != encodedBytes)
              throw failure(INTEGRITY_FAILURE, "Canonical encoded size conflicts");
            if (!row.owner().equals(session)
                && reservation.status() != CanonicalReservation.Status.RETAINED)
              try (var query =
                  c.prepareStatement(
                      "UPDATE canonical_content_slots SET owner_session=?,status='RECOVERY' WHERE"
                          + " token=?")) {
                query.setString(1, session.toString());
                query.setString(2, reservation.token().toString());
                query.executeUpdate();
              }
            return readToken(c, reservation.token()).reservation();
          }
          var id = UUID.randomUUID();
          try (var query =
              c.prepareStatement(
                  "INSERT INTO canonical_content_slots VALUES(?,?,?,?,?,?,?,NULL)")) {
            query.setString(1, slot.namespace());
            query.setString(2, slot.contentKey());
            query.setString(3, key);
            query.setLong(4, encodedBytes);
            query.setString(5, id.toString());
            query.setString(6, "PENDING");
            query.setString(7, session.toString());
            query.executeUpdate();
          }
          var origin = readOrigin(c, key);
          if (origin.isPresent()) {
            var adopted = rebind(origin.get(), slot);
            if (adopted.encoded().object().length() != encodedBytes)
              throw failure(INTEGRITY_FAILURE, "Existing encoded origin length conflicts");
            try (var query =
                c.prepareStatement(
                    "UPDATE canonical_content_slots SET status='RETAINED',representation=? WHERE"
                        + " token=?")) {
              query.setBytes(1, SelectedFileRecords.representation(adopted));
              query.setString(2, id.toString());
              query.executeUpdate();
            }
          }
          return readToken(c, id).reservation();
        });
  }

  @Override
  public void markPublishing(UUID id, CancellationToken token) {
    tx(
        token,
        c -> {
          var row = readToken(c, id);
          if (row.reservation().status() == CanonicalReservation.Status.RETAINED) return null;
          requireOwner(row);
          updateState(c, id, "PUBLISHING");
          return null;
        });
  }

  @Override
  public void recordVerifiedCompletion(
      UUID id, ContentRepresentation representation, CancellationToken token) {
    Objects.requireNonNull(representation);
    tx(
        token,
        c -> {
          var row = readToken(c, id);
          match(row.reservation(), representation);
          if (row.reservation().representation().isPresent()) {
            equivalent(row.reservation().representation().get(), representation);
            return null;
          }
          requireOwner(row);
          if (row.reservation().status() != CanonicalReservation.Status.PUBLISHING)
            throw failure(RECOVERY_REQUIRED, "Start publication before recording completion");
          try (var query =
              c.prepareStatement(
                  "UPDATE canonical_content_slots SET representation=? WHERE token=?")) {
            query.setBytes(1, SelectedFileRecords.representation(representation));
            query.setString(2, id.toString());
            query.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public ContentRepresentation completePublication(
      UUID id, ContentRepresentation representation, CancellationToken token) {
    Objects.requireNonNull(representation);
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    var expected =
        tx(
            token,
            c -> {
              var row = readToken(c, id);
              match(row.reservation(), representation);
              var proof =
                  row.reservation()
                      .representation()
                      .orElseThrow(
                          () ->
                              failure(
                                  INTEGRITY_FAILURE,
                                  "No durable verifier completion precedes publication"));
              equivalent(proof, representation);
              return proof;
            });
    try (var lease = open(expected.encoded().object(), token)) {
      return tx(
          token,
          c -> {
            var row = readToken(c, id);
            var original = row.reservation().representation().orElseThrow();
            equivalent(original, representation);
            if (row.reservation().status() == CanonicalReservation.Status.RETAINED) return original;
            requireOwner(row);
            var origin = readOrigin(c, row.reservation().actualEncodingKey());
            var canonical = origin.isPresent() ? rebind(origin.get(), original.slot()) : original;
            equivalent(canonical, original);
            var alias =
                objects.registerAliasInTransaction(
                    c,
                    canonical.encoded().source(),
                    canonical.encoded().object(),
                    canonical.encoded().evidence(),
                    lease);
            if (!alias.equals(canonical.encoded()))
              throw failure(
                  INTEGRITY_FAILURE, "Encoded alias lacks matching original transport proof");
            if (origin.isEmpty())
              try (var query =
                  c.prepareStatement("INSERT INTO encoded_file_receipts VALUES(?,?,?,?)")) {
                query.setString(1, canonical.encoded().source().namespace());
                query.setString(2, canonical.encoded().source().immutableSourceKey());
                query.setString(3, canonical.encoded().source().verificationSemantics());
                query.setBytes(4, SelectedFileRecords.representation(canonical));
                query.executeUpdate();
              }
            try (var query =
                c.prepareStatement(
                    "UPDATE canonical_content_slots SET status='RETAINED',representation=? WHERE"
                        + " token=?")) {
              query.setBytes(1, SelectedFileRecords.representation(canonical));
              query.setString(2, id.toString());
              query.executeUpdate();
            }
            return canonical;
          });
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  @Override
  public void releaseUnpublished(UUID id, CancellationToken token) {
    tx(
        token,
        c -> {
          var row = readToken(c, id);
          requireOwner(row);
          if (row.reservation().status() == CanonicalReservation.Status.RECOVERY
              || row.reservation().status() == CanonicalReservation.Status.RETAINED
              || row.reservation().representation().isPresent())
            throw failure(
                RECOVERY_REQUIRED, "Completed or uncertain publication must retain its candidate");
          try (var query =
              c.prepareStatement("DELETE FROM canonical_content_slots WHERE token=?")) {
            query.setString(1, id.toString());
            query.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public void markUncertain(UUID id, CancellationToken token) {
    tx(
        token,
        c -> {
          var row = readToken(c, id);
          if (row.reservation().status() == CanonicalReservation.Status.RETAINED) return null;
          requireOwner(row);
          updateState(c, id, "RECOVERY");
          return null;
        });
  }

  @Override
  public SelectedFileAssociation attachAssociation(
      SelectedFileAssociation association, RetentionScope scope, CancellationToken token) {
    Objects.requireNonNull(association);
    Objects.requireNonNull(scope);
    validateScope(association, scope);
    if (association.proof().declaredDecodedBytes().isEmpty()
        || association.proof().authenticatedCandidates().stream()
            .noneMatch(
                candidate ->
                    candidate.encodingKey().equals(association.descriptor().actualEncodingKey())
                        && (candidate.encodedBytes().isEmpty()
                            || candidate.encodedBytes().getAsLong()
                                == association.representation().encoded().object().length())))
      throw failure(
          INTEGRITY_FAILURE,
          "New association needs authenticated actual EKey without conflicting declared encoded"
              + " length");
    try (var file = open(association.representation().encoded().object(), token);
        var configuration = open(association.configuration().object(), token);
        var root = open(association.root().object(), token)) {
      return tx(
          token,
          c -> {
            var saved =
                catalog
                    .readSnapshot(c, association.snapshotId())
                    .orElseThrow(
                        () -> failure(NOT_FOUND, "Exact selected snapshot is not registered"));
            var bound = withStoredObservation(association, saved);
            var row =
                readSlot(c, bound.representation().slot())
                    .orElseThrow(() -> failure(NOT_FOUND, "Canonical slot missing"));
            if (row.reservation().status() != CanonicalReservation.Status.RETAINED
                || !row.reservation().representation().orElseThrow().equals(bound.representation()))
              throw failure(
                  INTEGRITY_FAILURE, "Association must preserve the actual canonical receipt");
            requireDependency(c, bound.snapshotId(), "BUILD_CONFIG", bound.configuration());
            requireDependency(c, bound.snapshotId(), "ROOT_MANIFEST", bound.root());
            var existing = readAssociation(c, bound.snapshotId(), bound.role());
            if (existing.isPresent() && !existing.get().equals(bound))
              throw failure(INTEGRITY_FAILURE, "Immutable selected association conflicts");
            var encoded = bound.representation().encoded();
            objects.attachInTransaction(
                c,
                bound.snapshotId(),
                bound.role(),
                encoded.source(),
                encoded.object(),
                encoded.evidence(),
                file,
                token);
            if (existing.isEmpty())
              try (var query =
                  c.prepareStatement("INSERT INTO selected_file_associations VALUES(?,?,?,?,?)")) {
                query.setString(1, bound.snapshotId());
                query.setString(2, bound.role());
                query.setString(3, bound.representation().slot().namespace());
                query.setString(4, bound.representation().slot().contentKey());
                query.setBytes(5, SelectedFileRecords.association(bound));
                query.executeUpdate();
              }
            retention.pinInTransaction(c, scope, token);
            return readAssociation(c, bound.snapshotId(), bound.role()).orElseThrow();
          });
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  @Override
  public Optional<SelectedFileAssociation> findAssociation(
      String snapshot, String role, CancellationToken token) {
    BuildObjectReference.validateSnapshotId(snapshot);
    BuildObjectReference.validateRole(role);
    return tx(token, c -> readAssociation(c, snapshot, role));
  }

  private Optional<SelectedFileAssociation> readAssociation(
      Connection c, String snapshot, String role) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT slot_namespace,slot_content_key,length(record),record FROM"
                + " selected_file_associations WHERE snapshot_id=? AND role=?")) {
      query.setString(1, snapshot);
      query.setString(2, role);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        if (rows.getLong(3) > SelectedFileRecords.MAX_BYTES)
          throw failure(INTEGRITY_FAILURE, "Association metadata too large");
        var slot = new ContentSlotKey(rows.getString(1), rows.getString(2));
        var canonical =
            readSlot(c, slot)
                .orElseThrow(() -> failure(INTEGRITY_FAILURE, "Association canonical slot missing"))
                .reservation();
        if (canonical.status() != CanonicalReservation.Status.RETAINED)
          throw failure(INTEGRITY_FAILURE, "Association canonical representation is incomplete");
        var build =
            catalog
                .readSnapshot(c, snapshot)
                .orElseThrow(
                    () -> failure(INTEGRITY_FAILURE, "Association exact snapshot missing"));
        var association =
            SelectedFileRecords.association(
                rows.getBytes(4), build, canonical.representation().orElseThrow());
        if (!association.role().equals(role))
          throw failure(INTEGRITY_FAILURE, "Association descriptor role mismatch");
        requireDependency(c, snapshot, "BUILD_CONFIG", association.configuration());
        requireDependency(c, snapshot, "ROOT_MANIFEST", association.root());
        requireDependency(c, snapshot, role, association.representation().encoded());
        return Optional.of(association);
      }
    }
  }

  private static SelectedFileAssociation withStoredObservation(
      SelectedFileAssociation association, BuildSnapshot saved) {
    var descriptor = association.descriptor();
    var variant = descriptor.association();
    var bound =
        new FileVariantAssociation(
            saved,
            variant.rootSource(),
            variant.fileDataId(),
            variant.contentKey(),
            variant.localeFlags(),
            variant.contentFlags(),
            variant.rawFlagsFirst(),
            variant.rawFlagsSecond(),
            variant.rawFlagsThird(),
            variant.nameHash());
    return new SelectedFileAssociation(
        new SelectedFileDescriptor(
            bound, descriptor.actualEncodingKey(), descriptor.encodedVerifierSemantics()),
        association.configuration(),
        association.root(),
        association.proof(),
        association.representation());
  }

  private void requireDependency(
      Connection c, String snapshot, String role, VerifiedSourceAlias expected)
      throws SQLException {
    var reference =
        objects
            .findInTransaction(c, snapshot, role)
            .orElseThrow(
                () -> failure(INTEGRITY_FAILURE, "Association dependency membership missing"));
    if (!reference.source().equals(expected.source())
        || !reference.object().equals(expected.object())
        || !reference.evidence().equals(expected.evidence()))
      throw failure(
          INTEGRITY_FAILURE, "Association dependency proof differs from exact membership");
  }

  private void validateScope(SelectedFileAssociation association, RetentionScope scope) {
    if (scope.kind() != ScopeKind.ENCODED_FILE
        || !scope.snapshotId().equals(association.snapshotId()))
      throw failure(INTEGRITY_FAILURE, "Selected scope does not bind the exact encoded file");
    var receipts =
        Map.of(
            "BUILD_CONFIG",
            association.configuration(),
            "ROOT_MANIFEST",
            association.root(),
            association.role(),
            association.representation().encoded());
    for (var requirement : scope.requirements()) {
      var expected = receipts.get(requirement.role());
      if (expected == null
          || requirement.source().isEmpty()
          || !requirement.source().get().equals(expected.source())
          || requirement.encodedBytes().isEmpty()
          || requirement.encodedBytes().getAsLong() != expected.object().length())
        throw failure(INTEGRITY_FAILURE, "Selected dependency scope is inconsistent");
      if (requirement.role().equals(association.role())
          && requirement.logicalDecodedBytes().isPresent())
        throw failure(INTEGRITY_FAILURE, "Encoded-only retention cannot claim decoded length");
    }
  }

  private ObjectReadLease open(StoredObject expected, CancellationToken token) {
    var lease = store.openRead(expected.digest(), token);
    if (!lease.object().equals(expected)) {
      lease.close();
      throw failure(INTEGRITY_FAILURE, "Physical object length conflicts");
    }
    return lease;
  }

  private Optional<ContentRepresentation> readOrigin(Connection c, String ekey)
      throws SQLException {
    var source = ContentRepresentation.sourceIdentity(ekey);
    try (var query =
        c.prepareStatement(
            "SELECT length(record),record FROM encoded_file_receipts WHERE namespace=? AND"
                + " encoding_key=? AND verifier_semantics=?")) {
      query.setString(1, source.namespace());
      query.setString(2, source.immutableSourceKey());
      query.setString(3, source.verificationSemantics());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        if (rows.getLong(1) > SelectedFileRecords.MAX_BYTES)
          throw failure(INTEGRITY_FAILURE, "Encoded origin metadata exceeds limit");
        var origin = SelectedFileRecords.representation(rows.getBytes(2));
        if (!origin.encoded().source().equals(source))
          throw failure(INTEGRITY_FAILURE, "Encoded origin source mismatch");
        return Optional.of(origin);
      }
    }
  }

  private static ContentRepresentation rebind(ContentRepresentation original, ContentSlotKey slot) {
    return new ContentRepresentation(
        slot, original.encoded(), original.scope(), original.sourceReceipt());
  }

  private Row validateRetainedOrigin(Connection c, Row row) throws SQLException {
    if (row.reservation().status() == CanonicalReservation.Status.RETAINED) {
      var origin =
          readOrigin(c, row.reservation().actualEncodingKey())
              .orElseThrow(
                  () -> failure(INTEGRITY_FAILURE, "Retained canonical origin is missing"));
      if (!rebind(origin, row.reservation().slot())
          .equals(row.reservation().representation().orElseThrow()))
        throw failure(INTEGRITY_FAILURE, "Canonical slot changed its original acquisition proof");
      var alias =
          objects
              .findAliasInTransaction(c, origin.encoded().source())
              .orElseThrow(
                  () -> failure(INTEGRITY_FAILURE, "Retained encoded source alias missing"));
      if (!alias.equals(origin.encoded()))
        throw failure(INTEGRITY_FAILURE, "Retained source alias conflicts with actual origin");
    }
    return row;
  }

  private Optional<Row> readSlot(Connection c, ContentSlotKey slot) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT *,length(representation) AS representation_bytes FROM canonical_content_slots"
                + " WHERE namespace=? AND content_key=?")) {
      query.setString(1, slot.namespace());
      query.setString(2, slot.contentKey());
      try (var rows = query.executeQuery()) {
        return rows.next() ? Optional.of(validateRetainedOrigin(c, row(rows))) : Optional.empty();
      }
    }
  }

  private Row readToken(Connection c, UUID id) throws SQLException {
    Objects.requireNonNull(id);
    try (var query =
        c.prepareStatement(
            "SELECT *,length(representation) AS representation_bytes FROM canonical_content_slots"
                + " WHERE token=?")) {
      query.setString(1, id.toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw failure(NOT_FOUND, "Canonical reservation token not found");
        return validateRetainedOrigin(c, row(rows));
      }
    }
  }

  private Row row(ResultSet rows) throws SQLException {
    try {
      if (!(rows.getObject("encoded_bytes") instanceof Integer
          || rows.getObject("encoded_bytes") instanceof Long))
        throw failure(INTEGRITY_FAILURE, "Invalid canonical encoded size");
      var owner = UUID.fromString(rows.getString("owner_session"));
      var status = CanonicalReservation.Status.valueOf(rows.getString("status"));
      if (status != CanonicalReservation.Status.RETAINED && !owner.equals(session))
        status = CanonicalReservation.Status.RECOVERY;
      if (rows.getObject("representation_bytes") != null
          && rows.getLong("representation_bytes") > SelectedFileRecords.MAX_BYTES)
        throw failure(INTEGRITY_FAILURE, "Canonical representation metadata exceeds limit");
      byte[] bytes = rows.getBytes("representation");
      Optional<ContentRepresentation> representation =
          bytes == null ? Optional.empty() : Optional.of(SelectedFileRecords.representation(bytes));
      return new Row(
          new CanonicalReservation(
              new ContentSlotKey(rows.getString("namespace"), rows.getString("content_key")),
              rows.getString("encoding_key"),
              rows.getLong("encoded_bytes"),
              UUID.fromString(rows.getString("token")),
              status,
              representation),
          owner);
    } catch (IllegalArgumentException invalid) {
      throw failure(INTEGRITY_FAILURE, "Invalid canonical reservation metadata", invalid);
    }
  }

  private void requireOwner(Row row) {
    if (!row.owner().equals(session))
      throw failure(RECOVERY_REQUIRED, "Reserve the original candidate before recovery");
  }

  private static void match(
      CanonicalReservation reservation, ContentRepresentation representation) {
    if (!reservation.slot().equals(representation.slot())
        || !reservation
            .actualEncodingKey()
            .equals(representation.encoded().source().immutableSourceKey())
        || reservation.encodedBytes() != representation.encoded().object().length())
      throw failure(INTEGRITY_FAILURE, "Completion does not match reserved candidate");
  }

  private static void equivalent(ContentRepresentation first, ContentRepresentation second) {
    if (!first.slot().equals(second.slot())
        || !first.encoded().source().equals(second.encoded().source())
        || !first.encoded().object().equals(second.encoded().object())
        || first.scope() != second.scope())
      throw failure(INTEGRITY_FAILURE, "Completed encoded identity conflicts with durable proof");
  }

  private static void updateState(Connection c, UUID id, String status) throws SQLException {
    try (var query =
        c.prepareStatement("UPDATE canonical_content_slots SET status=? WHERE token=?")) {
      query.setString(1, status);
      query.setString(2, id.toString());
      query.executeUpdate();
    }
  }

  private <T> T tx(CancellationToken token, Work<T> work) {
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try {
        var result = work.run(c);
        token.throwIfCancellationRequested();
        c.commit();
        return result;
      } catch (SQLException | RuntimeException failure) {
        try {
          c.rollback();
        } catch (SQLException suppressed) {
          failure.addSuppressed(suppressed);
        }
        throw failure;
      }
    } catch (SQLException failure) {
      throw failure(STORAGE_UNAVAILABLE, "Selected-file catalog unavailable", failure);
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  private static RuntimeException classify(RuntimeException error) {
    if (error instanceof SelectedFileException
        || error instanceof java.util.concurrent.CancellationException) return error;
    if (error instanceof ObjectStoreException storage)
      return failure(
          switch (storage.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
            default -> STORAGE_UNAVAILABLE;
          },
          storage.getMessage(),
          storage);
    if (error instanceof CatalogException catalog)
      return failure(
          switch (catalog.code()) {
            case CORRUPT_METADATA, INTEGRITY_FAILURE, IMMUTABLE_CONFLICT, INVALID_EVIDENCE ->
                INTEGRITY_FAILURE;
            case MISSING_OBJECT -> MISSING_LOCAL;
            case UNKNOWN_SNAPSHOT -> NOT_FOUND;
            default -> STORAGE_UNAVAILABLE;
          },
          catalog.getMessage(),
          catalog);
    if (error instanceof RetentionException retention)
      return failure(
          retention.code() == RetentionException.Code.INTEGRITY_FAILURE
              ? INTEGRITY_FAILURE
              : STORAGE_UNAVAILABLE,
          retention.getMessage(),
          retention);
    return error;
  }

  private static SelectedFileException failure(SelectedFileException.Code code, String message) {
    return new SelectedFileException(code, message);
  }

  private static SelectedFileException failure(
      SelectedFileException.Code code, String message, Throwable cause) {
    return new SelectedFileException(code, message, cause);
  }
}
