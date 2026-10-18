package io.vibeminer.infrastructure.content;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Bounded append-only audit. Completion never changes canonical readiness or original receipts. */
public final class SqliteMetadataRepairAuditRepository implements MetadataRepairAuditRepository {
  private final SqliteCatalog catalog;
  private final ObjectStore store;
  private final FileObjectStore.StoreSession session;
  private final Clock clock;

  private record Row(MetadataRepairAudit audit, UUID epoch, UUID nonce) {}

  @FunctionalInterface
  private interface Work<T> {
    T apply(Connection connection) throws SQLException;
  }

  public SqliteMetadataRepairAuditRepository(
      SqliteCatalog catalog, ObjectStore store, FileObjectStore.StoreSession session) {
    this(catalog, store, session, Clock.systemUTC());
  }

  public SqliteMetadataRepairAuditRepository(
      SqliteCatalog catalog, ObjectStore store, FileObjectStore.StoreSession session, Clock clock) {
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.session = Objects.requireNonNull(session);
    this.clock = Objects.requireNonNull(clock);
    if (!(store instanceof FileObjectStore files) || files.contentSession() != session)
      throw new IllegalArgumentException("Content session belongs to another store");
  }

  @Override
  public UUID begin(
      ContentWorkerPermit permit, MetadataRepairPlan issued, CancellationToken cancellation) {
    Objects.requireNonNull(permit);
    Objects.requireNonNull(issued);
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    var at = clock.instant();
    if (!issued.isUsableAt(at))
      throw repair(MetadataRepairException.Code.INVALID_PLAN, "Repair consent has expired", null);
    return tx(
        cancellation,
        c -> {
          requireOriginal(c, permit, issued);
          var build =
              catalog
                  .readSnapshot(c, issued.request().build().snapshotId())
                  .orElseThrow(
                      () -> claim(INVALID_STATE, "Register exact build before repair audit", null));
          var alias =
              new SqliteBuildObjectCatalog(catalog, store)
                  .findAliasInTransaction(c, issued.request().configuration().source());
          if (alias.isEmpty() || !alias.get().equals(issued.request().configuration()))
            throw claim(INTEGRITY_FAILURE, "Original configuration alias changed", null);
          try (var query =
              c.prepareStatement(
                  "SELECT count(*) FROM (SELECT 1 FROM metadata_repair_attempts WHERE"
                      + " canonical_token=? LIMIT 129)")) {
            query.setString(1, issued.reservationToken().toString());
            try (var rows = query.executeQuery()) {
              if (rows.getLong(1) >= MAXIMUM_ATTEMPTS_PER_SLOT)
                throw repair(
                    MetadataRepairException.Code.LIMIT,
                    "Repair audit admission bound reached",
                    null);
            }
          }
          var request = issued.request();
          var normalized =
              new MetadataRepairPlan(
                  issued.issuanceId(),
                  issued.reservationToken(),
                  new MetadataContentRequest(
                      build,
                      request.configuration(),
                      request.kind(),
                      request.slot(),
                      request.requestedEncodingKey(),
                      request.requestedEncodedBytes(),
                      request.declaredDecodedBytes()),
                  issued.originalProof(),
                  issued.sourceObservation(),
                  issued.cost(),
                  issued.issuedAt(),
                  issued.expiresAt());
          // SQLite admission may have waited for another writer. Stamp and validate the actual
          // admitted instant, never the earlier preflight observation.
          var begunAt = clock.instant();
          if (!issued.isUsableAt(begunAt))
            throw repair(
                MetadataRepairException.Code.INVALID_PLAN,
                "Repair consent expired before admission",
                null);
          var id = UUID.randomUUID();
          var bytes = MetadataRepairAuditRecords.plan(normalized);
          try (var query =
              c.prepareStatement(
                  "INSERT INTO metadata_repair_attempts VALUES(?,?,?,?,?,?,?,?,?)")) {
            query.setString(1, id.toString());
            query.setString(2, issued.reservationToken().toString());
            query.setString(3, session.epoch().toString());
            query.setString(4, permit.workerNonce().toString());
            query.setString(5, build.snapshotId());
            query.setString(6, request.kind().name());
            query.setString(7, begunAt.toString());
            query.setBytes(8, bytes);
            query.setString(9, sha(bytes));
            query.executeUpdate();
          }
          return id;
        });
  }

  @Override
  public void recordProspective(
      ContentWorkerPermit permit,
      UUID attempt,
      MetadataRepairObservation observation,
      CancellationToken cancellation) {
    append(permit, attempt, MetadataRepairAuditEvent.Kind.PROSPECTIVE, observation, cancellation);
  }

  @Override
  public void recordStructure(
      ContentWorkerPermit permit,
      UUID attempt,
      MetadataRepairObservation observation,
      CancellationToken cancellation) {
    append(permit, attempt, MetadataRepairAuditEvent.Kind.STRUCTURE, observation, cancellation);
  }

  private void append(
      ContentWorkerPermit permit,
      UUID attempt,
      MetadataRepairAuditEvent.Kind kind,
      MetadataRepairObservation observation,
      CancellationToken cancellation) {
    Objects.requireNonNull(observation);
    tx(
        cancellation,
        c -> {
          var row = owned(c, permit, attempt);
          requireOriginal(c, permit, row.audit().issued());
          append(c, row.audit(), kind, Optional.of(observation));
          return null;
        });
  }

  @Override
  public MetadataRepairAudit complete(
      ContentWorkerPermit permit,
      UUID attempt,
      MetadataRepairObservation observation,
      CancellationToken cancellation) {
    Objects.requireNonNull(observation);
    Objects.requireNonNull(permit);
    Objects.requireNonNull(attempt);
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    ready();
    var expected =
        tx(
            cancellation,
            c -> {
              var row = owned(c, permit, attempt);
              requireOriginal(c, permit, row.audit().issued());
              if (!observation.repairAttemptId().equals(attempt)
                  || !observation.originalProof().equals(row.audit().issued().originalProof()))
                throw repair(
                    MetadataRepairException.Code.INTEGRITY,
                    "Completion belongs to another repair attempt",
                    null);
              return row.audit().issued().originalProof().object();
            });
    try (var lease = store.openRead(expected.digest(), cancellation)) {
      if (!lease.object().equals(expected))
        throw claim(INTEGRITY_FAILURE, "Repair original physical geometry differs", null);
      return tx(
          cancellation,
          c -> {
            var row = owned(c, permit, attempt);
            requireOriginal(c, permit, row.audit().issued());
            if (terminal(row.audit())) {
              if (row.audit().events().getLast().kind() == MetadataRepairAuditEvent.Kind.COMPLETED
                  && row.audit().events().getLast().observation().equals(Optional.of(observation)))
                return row.audit();
              throw repair(
                  MetadataRepairException.Code.INVALID_PLAN,
                  "Repair attempt already terminated",
                  null);
            }
            return append(
                c, row.audit(), MetadataRepairAuditEvent.Kind.COMPLETED, Optional.of(observation));
          });
    } catch (ObjectStoreException failure) {
      throw claim(
          failure.code() == ObjectStoreException.Code.MISSING_OBJECT
              ? MISSING_LOCAL
              : failure.code() == ObjectStoreException.Code.INTEGRITY_FAILURE
                  ? INTEGRITY_FAILURE
                  : STORAGE_UNAVAILABLE,
          "Repair original physical object is unavailable",
          failure);
    }
  }

  @Override
  public void fail(
      ContentWorkerPermit permit,
      UUID attempt,
      MetadataAcquisitionFailure.Publication publication,
      CancellationToken cancellation) {
    Objects.requireNonNull(publication);
    tx(
        cancellation,
        c -> {
          var row = owned(c, permit, attempt);
          if (terminal(row.audit())) return null;
          requireOriginal(c, permit, row.audit().issued());
          var kind =
              publication == MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED
                      && row.audit().events().isEmpty()
                  ? MetadataRepairAuditEvent.Kind.CONCLUSIVELY_UNPUBLISHED
                  : MetadataRepairAuditEvent.Kind.UNCERTAIN;
          append(c, row.audit(), kind, Optional.empty());
          return null;
        });
  }

  @Override
  public Optional<MetadataRepairAudit> find(UUID attempt, CancellationToken cancellation) {
    Objects.requireNonNull(attempt);
    return tx(cancellation, c -> read(c, attempt).map(Row::audit));
  }

  private MetadataRepairAudit append(
      Connection c,
      MetadataRepairAudit audit,
      MetadataRepairAuditEvent.Kind kind,
      Optional<MetadataRepairObservation> observation)
      throws SQLException {
    var events = new ArrayList<>(audit.events());
    events.add(new MetadataRepairAuditEvent(events.size() + 1, kind, clock.instant(), observation));
    MetadataRepairAudit next;
    try {
      next = new MetadataRepairAudit(audit.attemptId(), audit.issued(), audit.begunAt(), events);
    } catch (IllegalArgumentException failure) {
      throw repair(
          MetadataRepairException.Code.INTEGRITY,
          "Repair event violates immutable verification ordering",
          failure);
    }
    var event = events.getLast();
    var bytes = MetadataRepairAuditRecords.event(event);
    try (var query = c.prepareStatement("INSERT INTO metadata_repair_events VALUES(?,?,?,?,?)")) {
      query.setString(1, audit.attemptId().toString());
      query.setInt(2, event.sequence());
      query.setString(3, kind.name());
      query.setBytes(4, bytes);
      query.setString(5, sha(bytes));
      query.executeUpdate();
    }
    return next;
  }

  private Row owned(Connection c, ContentWorkerPermit permit, UUID attempt) throws SQLException {
    Objects.requireNonNull(permit);
    Objects.requireNonNull(attempt);
    var row =
        read(c, attempt)
            .orElseThrow(
                () ->
                    repair(
                        MetadataRepairException.Code.INVALID_PLAN,
                        "Repair attempt is absent",
                        null));
    CanonicalContentGuards.requireWorker(
        c, row.audit().issued().reservationToken(), session.epoch(), permit);
    if (!permit.reservationToken().equals(row.audit().issued().reservationToken())
        || !row.epoch().equals(session.epoch())
        || !row.nonce().equals(permit.workerNonce()))
      throw claim(INVALID_STATE, "Repair attempt belongs to another actual worker", null);
    return row;
  }

  private void requireOriginal(Connection c, ContentWorkerPermit permit, MetadataRepairPlan issued)
      throws SQLException {
    if (!permit.reservationToken().equals(issued.reservationToken()))
      throw claim(INVALID_STATE, "Repair permit belongs to another reservation", null);
    CanonicalContentGuards.requireWorker(c, issued.reservationToken(), session.epoch(), permit);
    CanonicalContentGuards.check(c, issued.request().slot());
    try (var q =
        c.prepareStatement(
            "SELECT"
                + " namespace,content_key,encoding_key,encoded_bytes,status,owner_session,length(representation),representation"
                + " FROM canonical_content_slots WHERE token=?")) {
      q.setString(1, issued.reservationToken().toString());
      try (var r = q.executeQuery()) {
        if (!r.next()) throw claim(INVALID_STATE, "Original canonical reservation is absent", null);
        if (!session.epoch().toString().equals(r.getString(6)))
          throw claim(RECOVERY_REQUIRED, "Repair claim belongs to another store session", null);
        if (!r.getString(5).equals("RETAINED") && !r.getString(5).equals("RECOVERY"))
          throw claim(BUSY, "Repair original has an unfinished publication", null);
        var request = issued.request();
        if (!r.getString(1).equals(request.slot().namespace())
            || !r.getString(2).equals(request.slot().contentKey())
            || !r.getString(3).equals(request.requestedEncodingKey())
            || !(r.getObject(4) instanceof Long || r.getObject(4) instanceof Integer)
            || r.getLong(4) != request.requestedEncodedBytes()
            || r.getLong(7) < 1
            || r.getLong(7) > CanonicalContentRecords.MAX_BYTES)
          throw claim(INTEGRITY_FAILURE, "Original canonical header differs", null);
        try {
          if (!CanonicalContentRecords.decode(r.getBytes(8)).equals(issued.originalProof()))
            throw claim(INTEGRITY_FAILURE, "Original canonical proof changed", null);
        } catch (IllegalArgumentException failure) {
          throw claim(INTEGRITY_FAILURE, "Original canonical proof is malformed", failure);
        }
        var original = issued.originalProof().originalAlias();
        var alias =
            new SqliteBuildObjectCatalog(catalog, store)
                .findAliasInTransaction(c, original.source());
        if (alias.isPresent() && !alias.get().equals(original)
            || r.getString(5).equals("RETAINED") && alias.isEmpty())
          throw claim(INTEGRITY_FAILURE, "Original canonical source alias differs", null);
      }
    }
  }

  private Optional<Row> read(Connection c, UUID attempt) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT"
                + " canonical_token,owner_epoch,worker_nonce,snapshot_id,kind,started_at,length(record),record,record_sha256"
                + " FROM metadata_repair_attempts WHERE attempt_id=?")) {
      query.setString(1, attempt.toString());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        if (rows.getLong(7) < 1 || rows.getLong(7) > MetadataRepairAuditRecords.MAX_BYTES)
          throw corrupt("Repair attempt record exceeds bound", null);
        var bytes = rows.getBytes(8);
        if (!sha(bytes).equals(rows.getString(9)))
          throw corrupt("Repair attempt checksum differs", null);
        var build =
            catalog
                .readSnapshot(c, rows.getString(4))
                .orElseThrow(() -> corrupt("Repair attempt exact build is missing", null));
        try {
          var plan = MetadataRepairAuditRecords.plan(bytes, build);
          if (!plan.reservationToken().toString().equals(rows.getString(1))
              || !plan.request().kind().name().equals(rows.getString(5)))
            throw corrupt("Repair attempt indexed facts differ", null);
          var events = events(c, attempt);
          var audit =
              new MetadataRepairAudit(attempt, plan, Instant.parse(rows.getString(6)), events);
          return Optional.of(
              new Row(
                  audit, UUID.fromString(rows.getString(2)), UUID.fromString(rows.getString(3))));
        } catch (IllegalArgumentException failure) {
          throw corrupt("Repair attempt facts are malformed", failure);
        }
      }
    }
  }

  private List<MetadataRepairAuditEvent> events(Connection c, UUID attempt) throws SQLException {
    var result = new ArrayList<MetadataRepairAuditEvent>();
    try (var query =
        c.prepareStatement(
            "SELECT sequence,kind,length(record),record,record_sha256 FROM metadata_repair_events"
                + " WHERE attempt_id=? ORDER BY sequence LIMIT 5")) {
      query.setString(1, attempt.toString());
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          if (result.size() >= 4
              || rows.getLong(3) < 1
              || rows.getLong(3) > MetadataRepairAuditRecords.MAX_BYTES)
            throw corrupt("Repair event bound exceeded", null);
          var bytes = rows.getBytes(4);
          if (!sha(bytes).equals(rows.getString(5)))
            throw corrupt("Repair event checksum differs", null);
          try {
            var event = MetadataRepairAuditRecords.event(bytes);
            if (event.sequence() != rows.getLong(1)
                || !event.kind().name().equals(rows.getString(2)))
              throw corrupt("Repair event indexed facts differ", null);
            result.add(event);
          } catch (IllegalArgumentException failure) {
            throw corrupt("Malformed repair event", failure);
          }
        }
      }
    }
    return List.copyOf(result);
  }

  private <T> T tx(CancellationToken cancellation, Work<T> work) {
    Objects.requireNonNull(cancellation);
    cancellation.throwIfCancellationRequested();
    ready();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try {
        var result = work.apply(c);
        cancellation.throwIfCancellationRequested();
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
      throw claim(STORAGE_UNAVAILABLE, "Cannot access repair audit storage", failure);
    } catch (CatalogException failure) {
      throw claim(
          failure.code() == CatalogException.Code.STORAGE_UNAVAILABLE
              ? STORAGE_UNAVAILABLE
              : INTEGRITY_FAILURE,
          "Repair audit catalog is invalid",
          failure);
    }
  }

  private void ready() {
    try {
      session.beforeProducerOperation();
    } catch (IllegalStateException | ObjectStoreException failure) {
      throw claim(STORAGE_UNAVAILABLE, "Repair store session is not ready", failure);
    }
  }

  private static boolean terminal(MetadataRepairAudit audit) {
    if (audit.events().isEmpty()) return false;
    return switch (audit.events().getLast().kind()) {
      case COMPLETED, CONCLUSIVELY_UNPUBLISHED, UNCERTAIN -> true;
      default -> false;
    };
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static ContentClaimException claim(
      ContentClaimException.Code code, String message, Throwable cause) {
    return new ContentClaimException(code, message, cause);
  }

  private static MetadataRepairException repair(
      MetadataRepairException.Code code, String message, Throwable cause) {
    return new MetadataRepairException(code, message, cause);
  }

  private static MetadataRepairException corrupt(String message, Throwable cause) {
    return repair(MetadataRepairException.Code.INTEGRITY, message, cause);
  }
}
