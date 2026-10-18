package io.vibeminer.infrastructure.content;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.sql.*;
import java.util.*;

/** Capability-neutral transactions over the existing authoritative content header. */
public final class SqliteCanonicalContentRepository implements CanonicalContentRepository {
  private final SqliteCatalog catalog;
  private final ObjectStore store;
  private final UUID session;
  private final FileObjectStore.StoreSession storeSession;
  private final SqliteBuildObjectCatalog aliases;

  private record Row(ContentClaim claim, UUID owner) {}

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  public SqliteCanonicalContentRepository(SqliteCatalog catalog, ObjectStore store) {
    this(catalog, store, UUID.randomUUID());
  }

  /**
   * Shared composition must pass the SAME process session to this repository and
   * SqliteSelectedFileCatalog. A different session denotes recovery ownership, not another
   * simultaneously publishing facade. Neither adapter owns the catalog or object store.
   */
  public SqliteCanonicalContentRepository(SqliteCatalog catalog, ObjectStore store, UUID session) {
    this(catalog, store, session, null);
  }

  public SqliteCanonicalContentRepository(
      SqliteCatalog catalog, ObjectStore store, FileObjectStore.StoreSession capability) {
    this(catalog, store, Objects.requireNonNull(capability).epoch(), capability);
    if (!(store instanceof FileObjectStore physical) || physical.contentSession() != capability)
      throw new IllegalArgumentException("Content session belongs to another object store");
  }

  private SqliteCanonicalContentRepository(
      SqliteCatalog catalog,
      ObjectStore store,
      UUID session,
      FileObjectStore.StoreSession capability) {
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.session = Objects.requireNonNull(session);
    this.storeSession = capability;
    aliases = new SqliteBuildObjectCatalog(catalog, store);
  }

  /** Compatibility entry point for the one shared, exclusive content startup transaction. */
  public static void recoverAtStartup(
      SqliteCatalog catalog, FileObjectStore.StoreSession capability) {
    SqliteContentStartupRecovery.recoverAtStartup(catalog, capability);
  }

  /** SQL extraction for the common unused-session startup fence; never commits. */
  static void recoverInStartupTransaction(
      Connection connection, FileObjectStore.StoreSession capability) throws SQLException {
    capability.requireRecoveryInProgress();
    if (connection.getAutoCommit())
      throw new IllegalArgumentException("Caller recovery transaction required");
    try (var update =
        connection.prepareStatement(
            "UPDATE canonical_content_slots SET owner_session=?,worker_nonce=NULL,status=CASE WHEN"
                + " status='RETAINED' THEN 'RETAINED' ELSE 'RECOVERY' END")) {
      update.setString(1, capability.epoch().toString());
      update.executeUpdate();
    }
  }

  @Override
  public Optional<ContentClaim> lookup(ContentSlotKey slot, CancellationToken token) {
    return tx(
        token,
        c -> {
          CanonicalContentGuards.check(c, slot);
          return find(c, slot).map(Row::claim);
        });
  }

  @Override
  public ContentClaim reserve(
      ContentSlotKey slot, String ekey, long bytes, CancellationToken token) {
    var normalized = ContentRepresentation.sourceIdentity(ekey).immutableSourceKey();
    if (bytes < 1) throw failure(INVALID_REQUEST, "Known positive encoded length required");
    return tx(
        token,
        c -> {
          CanonicalContentGuards.check(c, slot);
          var previous = find(c, slot);
          if (previous.isPresent()) {
            var row = previous.get();
            var claim = row.claim();
            if (!claim.actualEncodingKey().equals(normalized))
              throw failure(
                  claim.status() == CanonicalReservation.Status.RETAINED ? REPLAN_REQUIRED : BUSY,
                  "Another encoding owns this content");
            if (claim.encodedBytes() != bytes)
              throw failure(INTEGRITY_FAILURE, "Canonical encoded length conflicts");
            if (claim.status() != CanonicalReservation.Status.RETAINED
                && !row.owner().equals(session)) {
              if (storeSession != null)
                throw failure(
                    RECOVERY_REQUIRED, "Store startup must reconcile foreign content ownership");
              if (CanonicalContentGuards.hasActiveWorker(c, claim.token()))
                throw failure(
                    RECOVERY_REQUIRED,
                    "Foreign active worker cannot be adopted by another adapter");
              try (var query =
                  c.prepareStatement(
                      "UPDATE canonical_content_slots SET owner_session=?,status='RECOVERY' WHERE"
                          + " token=?")) {
                query.setString(1, session.toString());
                query.setString(2, claim.token().toString());
                query.executeUpdate();
              }
            }
            adoptEncodedOrigin(c, read(c, claim.token()).claim());
            return read(c, claim.token()).claim();
          }
          var id = UUID.randomUUID();
          try (var query =
              c.prepareStatement(
                  "INSERT INTO"
                      + " canonical_content_slots(namespace,content_key,encoding_key,encoded_bytes,token,status,owner_session,representation)"
                      + " VALUES(?,?,?,?,?,?,?,NULL)")) {
            query.setString(1, slot.namespace());
            query.setString(2, slot.contentKey());
            query.setString(3, normalized);
            query.setLong(4, bytes);
            query.setString(5, id.toString());
            query.setString(6, "PENDING");
            query.setString(7, session.toString());
            query.executeUpdate();
          }
          adoptEncodedOrigin(c, read(c, id).claim());
          return read(c, id).claim();
        });
  }

  @Override
  public void markPublishing(UUID id, CancellationToken token) {
    markPublishing(id, null, token);
  }

  @Override
  public void markPublishing(ContentWorkerPermit permit, CancellationToken token) {
    Objects.requireNonNull(permit);
    markPublishing(permit.reservationToken(), permit, token);
  }

  private void markPublishing(UUID id, ContentWorkerPermit permit, CancellationToken token) {
    tx(
        token,
        c -> {
          var row = readForMutation(c, id, permit);
          if (row.claim().status() != CanonicalReservation.Status.RETAINED) {
            owner(row);
            state(c, id, "PUBLISHING");
          }
          return null;
        });
  }

  @Override
  public void recordVerifiedCompletion(
      UUID id, CanonicalContentProof proof, CancellationToken token) {
    recordVerifiedCompletion(id, null, proof, token);
  }

  @Override
  public void recordVerifiedCompletion(
      ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken token) {
    Objects.requireNonNull(permit);
    recordVerifiedCompletion(permit.reservationToken(), permit, proof, token);
  }

  private void recordVerifiedCompletion(
      UUID id, ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken token) {
    tx(
        token,
        c -> {
          var row = readForMutation(c, id, permit);
          match(row.claim(), proof);
          if (row.claim().proof().isPresent()) {
            if (row.claim().status() == CanonicalReservation.Status.RETAINED
                && !row.claim().proof().get().equals(proof))
              throw failure(
                  INVALID_STATE, "Published capability upgrades require a physical lease");
            if (row.claim().status() != CanonicalReservation.Status.RETAINED) owner(row);
            ContentProofPolicy.requireMonotonicUpgrade(row.claim().proof().get(), proof);
            save(c, id, proof);
            return null;
          }
          owner(row);
          if (row.claim().status() != CanonicalReservation.Status.PUBLISHING)
            throw failure(RECOVERY_REQUIRED, "Start publication before recording completion");
          save(c, id, proof);
          return null;
        });
  }

  @Override
  public ContentClaim completePublication(
      UUID id, CanonicalContentProof proof, CancellationToken token) {
    return completePublication(id, null, proof, token);
  }

  @Override
  public ContentClaim completePublication(
      ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken token) {
    Objects.requireNonNull(permit);
    return completePublication(permit.reservationToken(), permit, proof, token);
  }

  private ContentClaim completePublication(
      UUID id, ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken token) {
    ContentProofPolicy.requirePublicationReady(proof);
    if (proof instanceof EncodedContentProof encoded) {
      var selected =
          storeSession == null
              ? new SqliteSelectedFileCatalog(catalog, store, session)
              : new SqliteSelectedFileCatalog(catalog, store, storeSession);
      if (permit == null) selected.completePublication(id, encoded.receipt(), token);
      else selected.completePublication(permit, encoded.receipt(), token);
      return lookup(encoded.receipt().slot(), token).orElseThrow();
    }
    var expected =
        tx(
            token,
            c -> {
              var row = readForMutation(c, id, permit);
              match(row.claim(), proof);
              var original =
                  row.claim()
                      .proof()
                      .orElseThrow(
                          () ->
                              failure(
                                  INTEGRITY_FAILURE, "No prospective proof precedes publication"));
              ContentProofPolicy.requireMonotonicUpgrade(original, proof);
              return original;
            });
    try (var lease = open(expected.object(), token)) {
      return tx(
          token,
          c -> {
            var row = readForMutation(c, id, permit);
            match(row.claim(), proof);
            var previous = row.claim().proof().orElseThrow();
            ContentProofPolicy.requireMonotonicUpgrade(previous, proof);
            if (row.claim().status() != CanonicalReservation.Status.RETAINED) owner(row);
            var metadata = (MetadataContentProof) proof;
            var alias = metadata.originalAlias();
            var originalAlias =
                aliases.registerAliasInTransaction(
                    c, alias.source(), alias.object(), alias.evidence(), lease);
            if (!originalAlias.equals(alias))
              throw failure(INTEGRITY_FAILURE, "Publication changed original source observation");
            save(c, id, proof);
            state(c, id, "RETAINED");
            return readForMutation(c, id, permit).claim();
          });
    } catch (ObjectStoreException failure) {
      throw storage(failure);
    }
  }

  @Override
  public ContentClaim upgradeProof(UUID id, CanonicalContentProof proof, CancellationToken token) {
    return upgradeProof(id, null, proof, token);
  }

  @Override
  public ContentClaim upgradeProof(
      ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken token) {
    Objects.requireNonNull(permit);
    return upgradeProof(permit.reservationToken(), permit, proof, token);
  }

  private ContentClaim upgradeProof(
      UUID id, ContentWorkerPermit permit, CanonicalContentProof proof, CancellationToken token) {
    var expected =
        tx(
            token,
            c -> {
              var row = readForMutation(c, id, permit);
              match(row.claim(), proof);
              ContentProofPolicy.requireMonotonicUpgrade(
                  row.claim()
                      .proof()
                      .orElseThrow(() -> failure(INVALID_STATE, "No proof to upgrade")),
                  proof);
              return row.claim().proof().orElseThrow();
            });
    try (var lease = open(expected.object(), token)) {
      return tx(
          token,
          c -> {
            var row = readForMutation(c, id, permit);
            ContentProofPolicy.requireMonotonicUpgrade(row.claim().proof().orElseThrow(), proof);
            if (row.claim().status() != CanonicalReservation.Status.RETAINED) owner(row);
            save(c, id, proof);
            return readForMutation(c, id, permit).claim();
          });
    } catch (ObjectStoreException failure) {
      throw storage(failure);
    }
  }

  @Override
  public void releaseUnpublished(UUID id, CancellationToken token) {
    tx(
        token,
        c -> {
          var row = readForMutation(c, id, null);
          owner(row);
          if (row.claim().proof().isPresent()
              || row.claim().status() == CanonicalReservation.Status.RECOVERY
              || row.claim().status() == CanonicalReservation.Status.RETAINED)
            throw failure(RECOVERY_REQUIRED, "Uncertain or published content remains exclusive");
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
          var row = readForMutation(c, id, null);
          if (row.claim().status() != CanonicalReservation.Status.RETAINED) {
            owner(row);
            state(c, id, "RECOVERY");
          }
          return null;
        });
  }

  @Override
  public ContentWorkerPermit claimWorker(UUID id, CancellationToken token) {
    return tx(
        token,
        c -> {
          var row = read(c, id);
          if (CanonicalContentGuards.hasActiveWorker(c, id))
            throw failure(BUSY, "Another actual source worker is active");
          owner(row);
          var nonce = UUID.randomUUID();
          try (var query =
              c.prepareStatement(
                  "UPDATE canonical_content_slots SET worker_nonce=? WHERE token=? AND worker_nonce"
                      + " IS NULL")) {
            query.setString(1, nonce.toString());
            query.setString(2, id.toString());
            if (query.executeUpdate() != 1)
              throw failure(BUSY, "Another actual source worker won admission");
          }
          return new ContentWorkerPermit(id, nonce);
        });
  }

  @Override
  public void finishWorker(
      ContentWorkerPermit permit, ContentWorkerFinishOutcome outcome, CancellationToken token) {
    Objects.requireNonNull(permit);
    Objects.requireNonNull(outcome);
    tx(
        token,
        c -> {
          var row = readForMutation(c, permit.reservationToken(), permit);
          if (row.claim().status() == CanonicalReservation.Status.RETAINED) {
            clearWorker(c, permit.reservationToken(), "RETAINED");
          } else if (outcome == ContentWorkerFinishOutcome.RETAINED)
            throw failure(INVALID_STATE, "Worker did not complete publication");
          else if (outcome == ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED
              && row.claim().proof().isEmpty()
              && row.claim().status() != CanonicalReservation.Status.RECOVERY) {
            try (var query =
                c.prepareStatement(
                    "DELETE FROM canonical_content_slots WHERE token=? AND worker_nonce=?")) {
              query.setString(1, permit.reservationToken().toString());
              query.setString(2, permit.workerNonce().toString());
              query.executeUpdate();
            }
          } else clearWorker(c, permit.reservationToken(), "RECOVERY");
          return null;
        });
  }

  private static void clearWorker(Connection c, UUID id, String state) throws SQLException {
    try (var query =
        c.prepareStatement(
            "UPDATE canonical_content_slots SET worker_nonce=NULL,status=? WHERE token=?")) {
      query.setString(1, state);
      query.setString(2, id.toString());
      query.executeUpdate();
    }
  }

  private Row readForMutation(Connection c, UUID id, ContentWorkerPermit permit)
      throws SQLException {
    CanonicalContentGuards.requireWorker(c, id, session, permit);
    return read(c, id);
  }

  private Optional<Row> find(Connection c, ContentSlotKey slot) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT *,length(representation) AS proof_bytes FROM canonical_content_slots WHERE"
                + " namespace=? AND content_key=?")) {
      query.setString(1, slot.namespace());
      query.setString(2, slot.contentKey());
      try (var rows = query.executeQuery()) {
        return rows.next() ? Optional.of(validateLedger(c, row(rows))) : Optional.empty();
      }
    }
  }

  private Row read(Connection c, UUID id) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT *,length(representation) AS proof_bytes FROM canonical_content_slots WHERE"
                + " token=?")) {
      query.setString(1, id.toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw failure(INVALID_STATE, "Canonical token not found");
        var row = row(rows);
        CanonicalContentGuards.check(c, row.claim().slot());
        return validateLedger(c, row);
      }
    }
  }

  private void adoptEncodedOrigin(Connection connection, ContentClaim claim) throws SQLException {
    if (claim.status() == CanonicalReservation.Status.RETAINED
        || CanonicalContentGuards.hasActiveWorker(connection, claim.token())) return;
    var source = ContentRepresentation.sourceIdentity(claim.actualEncodingKey());
    try (var query =
        connection.prepareStatement(
            "SELECT length(record),record FROM encoded_file_receipts WHERE namespace=? AND"
                + " encoding_key=? AND verifier_semantics=?")) {
      query.setString(1, source.namespace());
      query.setString(2, source.immutableSourceKey());
      query.setString(3, source.verificationSemantics());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return;
        if (rows.getLong(1) > CanonicalContentRecords.MAX_BYTES)
          throw failure(INTEGRITY_FAILURE, "Encoded origin exceeds limit");
        var proof = CanonicalContentRecords.decode(rows.getBytes(2));
        if (!(proof instanceof EncodedContentProof encoded))
          throw failure(INTEGRITY_FAILURE, "Wrong encoded origin kind");
        var original = encoded.receipt();
        var adopted =
            new EncodedContentProof(
                new ContentRepresentation(
                    claim.slot(), original.encoded(), original.scope(), original.sourceReceipt()));
        match(claim, adopted);
        if (claim.proof().isPresent()) {
          if (!(claim.proof().get() instanceof EncodedContentProof pending))
            throw failure(
                UNSUPPORTED_REUSE,
                "Explicit metadata capability cannot be converted to encoded proof");
          if (!pending.receipt().encoded().source().equals(adopted.receipt().encoded().source())
              || !pending.object().equals(adopted.object())
              || pending.receipt().scope() != adopted.receipt().scope())
            throw failure(
                INTEGRITY_FAILURE, "Prospective encoded proof differs from committed origin");
        }
        save(connection, claim.token(), adopted);
        state(connection, claim.token(), "RETAINED");
      }
    }
  }

  private Row validateLedger(Connection connection, Row row) throws SQLException {
    if (row.claim().status() != CanonicalReservation.Status.RETAINED) return row;
    var proof = row.claim().proof().orElseThrow();
    VerifiedSourceAlias expected;
    if (proof instanceof EncodedContentProof encoded) {
      var receipt = encoded.receipt();
      expected = receipt.encoded();
      try (var query =
          connection.prepareStatement(
              "SELECT length(record),record FROM encoded_file_receipts WHERE namespace=? AND"
                  + " encoding_key=? AND verifier_semantics=?")) {
        query.setString(1, expected.source().namespace());
        query.setString(2, expected.source().immutableSourceKey());
        query.setString(3, expected.source().verificationSemantics());
        try (var records = query.executeQuery()) {
          if (!records.next() || records.getLong(1) > CanonicalContentRecords.MAX_BYTES)
            throw failure(INTEGRITY_FAILURE, "Canonical encoded origin missing or oversized");
          var origin = CanonicalContentRecords.decode(records.getBytes(2));
          if (!(origin instanceof EncodedContentProof original))
            throw failure(INTEGRITY_FAILURE, "Canonical encoded origin has wrong proof kind");
          var rebound =
              new ContentRepresentation(
                  receipt.slot(),
                  original.receipt().encoded(),
                  original.receipt().scope(),
                  original.receipt().sourceReceipt());
          if (!receipt.equals(rebound))
            throw failure(INTEGRITY_FAILURE, "Canonical encoded origin changed");
        }
      }
    } else expected = ((MetadataContentProof) proof).originalAlias();
    var actual =
        aliases
            .findAliasInTransaction(connection, expected.source())
            .orElseThrow(() -> failure(INTEGRITY_FAILURE, "Canonical retained alias missing"));
    if (!actual.equals(expected))
      throw failure(INTEGRITY_FAILURE, "Canonical retained alias changed");
    return row;
  }

  private Row row(ResultSet rows) throws SQLException {
    if (!(rows.getObject("encoded_bytes") instanceof Integer
        || rows.getObject("encoded_bytes") instanceof Long))
      throw failure(INTEGRITY_FAILURE, "Invalid canonical encoded length");
    if (rows.getLong("proof_bytes") > CanonicalContentRecords.MAX_BYTES)
      throw failure(INTEGRITY_FAILURE, "Oversized canonical proof");
    var owner = UUID.fromString(rows.getString("owner_session"));
    var status = CanonicalReservation.Status.valueOf(rows.getString("status"));
    if (status != CanonicalReservation.Status.RETAINED && !owner.equals(session))
      status = CanonicalReservation.Status.RECOVERY;
    var bytes = rows.getBytes("representation");
    var claim =
        new ContentClaim(
            new ContentSlotKey(rows.getString("namespace"), rows.getString("content_key")),
            rows.getString("encoding_key"),
            rows.getLong("encoded_bytes"),
            UUID.fromString(rows.getString("token")),
            status,
            bytes == null ? Optional.empty() : Optional.of(CanonicalContentRecords.decode(bytes)));
    return new Row(claim, owner);
  }

  private ObjectReadLease open(StoredObject object, CancellationToken token) {
    var lease = store.openRead(object.digest(), token);
    if (!lease.object().equals(object)) {
      lease.close();
      throw failure(INTEGRITY_FAILURE, "Canonical physical length conflicts");
    }
    return lease;
  }

  private void owner(Row row) {
    if (!row.owner().equals(session))
      throw failure(RECOVERY_REQUIRED, "Reserve original candidate before recovery");
  }

  private static void match(ContentClaim claim, CanonicalContentProof proof) {
    if (!claim.actualEncodingKey().equals(proof.actualEncodingKey())
        || !claim.slot().contentKey().equals(proof.contentKey())
        || claim.encodedBytes() != proof.encodedBytes())
      throw failure(INTEGRITY_FAILURE, "Proof does not match reserved content");
    if (proof instanceof EncodedContentProof encoded
        && !encoded.receipt().slot().equals(claim.slot()))
      throw failure(INTEGRITY_FAILURE, "Encoded proof namespace mismatch");
  }

  private static void save(Connection c, UUID id, CanonicalContentProof proof) throws SQLException {
    try (var query =
        c.prepareStatement("UPDATE canonical_content_slots SET representation=? WHERE token=?")) {
      query.setBytes(1, CanonicalContentRecords.encode(proof));
      query.setString(2, id.toString());
      query.executeUpdate();
    }
  }

  private static void state(Connection c, UUID id, String value) throws SQLException {
    try (var query =
        c.prepareStatement("UPDATE canonical_content_slots SET status=? WHERE token=?")) {
      query.setString(1, value);
      query.setString(2, id.toString());
      query.executeUpdate();
    }
  }

  private <T> T tx(CancellationToken token, Work<T> work) {
    token.throwIfCancellationRequested();
    try {
      if (storeSession != null) storeSession.beforeProducerOperation();
      else store.publisherStarted();
    } catch (RuntimeException failure) {
      throw new ContentClaimException(
          STORAGE_UNAVAILABLE, "Content store session is not ready", failure);
    }
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
    } catch (ContentClaimException | java.util.concurrent.CancellationException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw new ContentClaimException(
          STORAGE_UNAVAILABLE, "Canonical catalog unavailable", failure);
    } catch (io.vibeminer.application.catalog.CatalogException failure) {
      throw new ContentClaimException(
          switch (failure.code()) {
            case CORRUPT_METADATA, INTEGRITY_FAILURE, IMMUTABLE_CONFLICT, INVALID_EVIDENCE ->
                INTEGRITY_FAILURE;
            case MISSING_OBJECT -> MISSING_LOCAL;
            default -> STORAGE_UNAVAILABLE;
          },
          "Canonical alias metadata unavailable",
          failure);
    } catch (IllegalArgumentException failure) {
      throw new ContentClaimException(
          INTEGRITY_FAILURE, "Invalid canonical ledger metadata", failure);
    }
  }

  private static ContentClaimException storage(ObjectStoreException failure) {
    return new ContentClaimException(
        switch (failure.code()) {
          case MISSING_OBJECT -> MISSING_LOCAL;
          case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
          default -> STORAGE_UNAVAILABLE;
        },
        failure.getMessage(),
        failure);
  }

  private static ContentClaimException failure(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }
}
