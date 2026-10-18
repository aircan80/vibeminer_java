package io.vibeminer.infrastructure.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static io.vibeminer.application.buildretention.BuildRetentionException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Isolated durable ledger, not a pump or downloader. Schema installation is deliberately unwired.
 */
public final class SqliteBuildRetentionRepository implements BuildRetentionRepository {
  private final SqliteCatalog catalog;
  private final FileObjectStore.StoreSession session;
  private final BuildRetentionInventory inventory;

  /** Concrete SQL authority extension; application inventory ports remain SQL-free. */
  public interface TransactionalInventory extends BuildRetentionInventory {
    void validateInTransaction(
        Connection connection,
        BuildRetentionInventory.Opened opened,
        CancellationToken cancellation);
  }

  private <T> T authorityTransaction(
      CancellationToken token, BuildRetentionInventory.Opened opened, Work<T> work) {
    return transaction(
        token,
        c -> {
          if (inventory instanceof TransactionalInventory authority)
            authority.validateInTransaction(c, opened, token);
          T result = work.run(c);
          if (inventory instanceof TransactionalInventory authority)
            authority.validateInTransaction(c, opened, token);
          return result;
        });
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  public SqliteBuildRetentionRepository(
      SqliteCatalog catalog,
      FileObjectStore store,
      FileObjectStore.StoreSession session,
      BuildRetentionInventory inventory) {
    this.catalog = Objects.requireNonNull(catalog);
    this.session = Objects.requireNonNull(session);
    this.inventory = Objects.requireNonNull(inventory);
    if (Objects.requireNonNull(store).contentSession() != session)
      throw new IllegalArgumentException("Store session belongs to another store");
  }

  /** Isolated one-shot fence. Production must compose this SQL in the ONE shared startup fence. */
  public static void recoverAtStartup(SqliteCatalog catalog, FileObjectStore.StoreSession session) {
    Objects.requireNonNull(session)
        .recoverAtStartup(
            () -> {
              try (var connection = catalog.connect()) {
                connection.setAutoCommit(false);
                try {
                  recoverInStartupTransaction(connection, session);
                  connection.commit();
                } catch (SQLException | RuntimeException failure) {
                  rollback(connection, failure);
                  throw failure;
                }
              } catch (SQLException failure) {
                throw unavailable(failure);
              }
            });
  }

  /**
   * Fixed SQL recovery only; cannot be invoked before, after, or outside the exclusive callback.
   */
  public static void recoverInStartupTransaction(
      Connection connection, FileObjectStore.StoreSession session) throws SQLException {
    Objects.requireNonNull(session).requireRecoveryInProgress();
    if (Objects.requireNonNull(connection).getAutoCommit())
      throw new IllegalStateException("Recovery requires caller's one transaction");
    int running = 0;
    try (var query = connection.createStatement();
        var rows = query.executeQuery("SELECT * FROM build_retention_jobs WHERE state='RUNNING'")) {
      while (rows.next()) {
        if (++running > MAXIMUM_RUNNING)
          throw fail(CORRUPT_STATE, "Stored running work exceeds the bounded source limit");
        var target = targetById(connection, rows.getString("target_id"));
        require(connection, target);
        job(rows, target);
      }
    }
    try (var update = connection.createStatement()) {
      update.executeUpdate(
          """
          UPDATE build_retention_jobs SET
            state=CASE WHEN (SELECT mode FROM build_retention_intents i WHERE i.target_id=build_retention_jobs.target_id)='ON_DEMAND' THEN 'FAILED'
              WHEN attempts<(SELECT maximum_attempts FROM build_retention_intents i WHERE i.target_id=build_retention_jobs.target_id) THEN 'QUEUED' ELSE 'FAILED' END,
            worker_nonce=NULL,owner_epoch=NULL,lease_generation=NULL,
            failure_code=CASE WHEN (SELECT mode FROM build_retention_intents i WHERE i.target_id=build_retention_jobs.target_id)='ON_DEMAND' THEN 'CANCELLED' ELSE 'INTERRUPTED' END,
            failure_message=CASE WHEN (SELECT mode FROM build_retention_intents i WHERE i.target_id=build_retention_jobs.target_id)='ON_DEMAND' THEN 'Bulk intent paused before interrupted worker retired' ELSE 'Previous actual worker was interrupted' END
          WHERE state='RUNNING'
          """);
    }
  }

  @Override
  public Optional<Intent> readIntent(Target target, CancellationToken cancellation) {
    return transaction(cancellation, connection -> read(connection, target));
  }

  /**
   * Bounded restart projection of prior explicit authorization. Does not open inventory, content,
   * transport or a newer scope. An empty cursor begins the strict target-identity keyset.
   */
  public List<Intent> pageAuthorizedIntents(
      String afterTargetIdentity, int maximum, CancellationToken cancellation) {
    if (afterTargetIdentity == null
        || !afterTargetIdentity.isEmpty() && !afterTargetIdentity.matches("[0-9a-f]{64}")
        || maximum < 1
        || maximum > 16)
      throw fail(INVALID_REQUEST, "Restart page requires a target cursor and size1..16");
    return transaction(
        cancellation,
        c -> {
          var result = new ArrayList<Intent>();
          try (var q =
              c.prepareStatement(
                  "SELECT * FROM build_retention_intents WHERE mode='DOWNLOAD_ALL' AND target_id>?"
                      + " ORDER BY target_id LIMIT ?")) {
            q.setString(1, afterTargetIdentity);
            q.setInt(2, maximum);
            try (var rows = q.executeQuery()) {
              while (rows.next()) {
                cancellation.throwIfCancellationRequested();
                var target = target(rows);
                if (!target.identity().equals(rows.getString("target_id")))
                  throw fail(CORRUPT_STATE, "Stored authorized target commitment changed");
                try {
                  result.add(intent(rows, target));
                } catch (java.time.DateTimeException | ArithmeticException malformed) {
                  throw new BuildRetentionException(
                      CORRUPT_STATE, "Malformed persisted authorization", malformed);
                }
              }
            }
          }
          return List.copyOf(result);
        });
  }

  @Override
  public boolean hasPending(Target target, CancellationToken cancellation) {
    return transaction(
        cancellation,
        c -> {
          require(c, target);
          try (var q =
              c.prepareStatement(
                  "SELECT EXISTS(SELECT 1 FROM build_retention_jobs WHERE state IN"
                      + " ('QUEUED','RUNNING') AND target_id=?)")) {
            q.setString(1, target.identity());
            try (var r = q.executeQuery()) {
              return r.next() && r.getInt(1) == 1;
            }
          }
        });
  }

  @Override
  public Intent setMode(
      Target target,
      long expectedGeneration,
      Mode mode,
      Policy policy,
      Instant authorizedAt,
      CancellationToken cancellation) {
    Objects.requireNonNull(target);
    Objects.requireNonNull(mode);
    Objects.requireNonNull(policy);
    Objects.requireNonNull(authorizedAt);
    if (expectedGeneration < 0) throw fail(INVALID_REQUEST, "Negative mode generation");
    gate(cancellation);
    // The source authority holds exact immutable inventory/metadata leases across the SQL commit.
    if (mode == Mode.DOWNLOAD_ALL)
      try (var opened = inventory.openSealed(target, cancellation)) {
        var seal = checkedSeal(target, opened);
        return authorityTransaction(
            cancellation,
            opened,
            connection ->
                setMode(connection, target, expectedGeneration, mode, policy, authorizedAt, seal));
      }
    return transaction(
        cancellation,
        connection ->
            setMode(connection, target, expectedGeneration, mode, policy, authorizedAt, null));
  }

  private Intent setMode(
      Connection connection,
      Target target,
      long expected,
      Mode mode,
      Policy policy,
      Instant when,
      Seal seal)
      throws SQLException {
    if (mode == Mode.DOWNLOAD_ALL) {
      try (var query =
          connection.prepareStatement(
              "SELECT target_id FROM build_retention_intents WHERE group_id=? AND"
                  + " mode='DOWNLOAD_ALL' AND target_id<>?")) {
        query.setString(1, target.groupId());
        query.setString(2, target.identity());
        try (var rows = query.executeQuery()) {
          if (rows.next())
            throw fail(
                STALE_GENERATION,
                "Pause the prior immutable scope before explicitly replanning this build");
        }
      }
    }
    var prior = read(connection, target);
    if (prior.isEmpty()) {
      if (expected != 0)
        throw fail(STALE_GENERATION, "Intent does not have the expected generation");
      if (mode != Mode.DOWNLOAD_ALL || seal == null)
        throw fail(NOT_FOUND, "No existing retention intent to pause");
      try (var insert =
          connection.prepareStatement(
              "INSERT INTO build_retention_intents"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
        int i = 1;
        insert.setString(i++, target.identity());
        insert.setString(i++, target.groupId());
        insert.setString(i++, target.snapshotId());
        insert.setString(i++, target.admissionId());
        insert.setString(i++, target.inventoryId());
        insert.setLong(i++, target.inventoryGeneration());
        insert.setString(i++, target.scopeRevision());
        insert.setString(i++, target.scopeCommitment());
        insert.setString(i++, mode.name());
        insert.setLong(i++, 1);
        insert.setString(i++, when.toString());
        insert.setLong(i++, seal.memberCount());
        insert.setLong(i++, 0);
        insert.setLong(i++, policy.maximumObjectBytes());
        insert.setLong(i++, policy.maximumWireBytes());
        insert.setLong(i++, policy.maximumAdditionalBytes());
        insert.setLong(i++, policy.maximumRequests());
        insert.setInt(i++, policy.maximumAttempts());
        insert.setLong(i++, 0);
        insert.setLong(i++, 0);
        insert.setLong(i++, 0);
        insert.executeUpdate();
      }
    } else {
      var current = prior.get();
      requireGeneration(current, expected);
      if (!current.policy().equals(policy))
        throw fail(INVALID_REQUEST, "Existing immutable safety policy needs a separate replan");
      if (seal != null && seal.memberCount() != current.memberCount())
        throw fail(CORRUPT_STATE, "Sealed inventory count changed");
      if (current.mode() != mode) {
        if (current.generation() == Long.MAX_VALUE)
          throw fail(LIMIT_EXCEEDED, "Mode generation exhausted");
        try (var update =
            connection.prepareStatement(
                "UPDATE build_retention_intents SET mode=?,generation=generation+1 WHERE"
                    + " target_id=?")) {
          update.setString(1, mode.name());
          update.setString(2, target.identity());
          update.executeUpdate();
        }
        if (mode == Mode.ON_DEMAND) {
          try (var withdraw =
              connection.prepareStatement(
                  "UPDATE build_retention_jobs SET"
                      + " state='FAILED',failure_code='CANCELLED',failure_message='Queued bulk"
                      + " intent withdrawn by On demand' WHERE target_id=? AND state='QUEUED'")) {
            withdraw.setString(1, target.identity());
            withdraw.executeUpdate();
          }
        }
      }
    }
    return require(connection, target);
  }

  @Override
  public Intent fillWindow(
      Target target, long generation, int maximum, CancellationToken cancellation) {
    if (maximum < 1 || maximum > MAXIMUM_FILL)
      throw fail(INVALID_REQUEST, "Fill size must be1..128");
    gate(cancellation);
    try (var opened = inventory.openSealed(target, cancellation)) {
      var seal = checkedSeal(target, opened);
      return authorityTransaction(
          cancellation,
          opened,
          connection -> {
            var current = require(connection, target);
            requireActive(current, generation);
            if (seal.memberCount() != current.memberCount())
              throw fail(CORRUPT_STATE, "Inventory count changed");
            long pending = count(connection, "state IN ('QUEUED','RUNNING')");
            int wanted = (int) Math.min(maximum, MAXIMUM_QUEUED - pending);
            if (wanted <= 0) return current;
            int resumed =
                resumeCancelled(connection, target, wanted, current.policy().maximumAttempts());
            wanted =
                (int) Math.min(wanted - resumed, current.memberCount() - current.nextOrdinal());
            if (wanted <= 0) return require(connection, target);
            var members = List.copyOf(opened.members(current.nextOrdinal(), wanted, cancellation));
            if (members.size() != wanted)
              throw fail(CORRUPT_STATE, "Inventory returned incomplete bounded page");
            for (int n = 0; n < members.size(); n++) {
              cancellation.throwIfCancellationRequested();
              var member = members.get(n);
              if (member.ordinal() != current.nextOrdinal() + n)
                throw fail(CORRUPT_STATE, "Inventory page changed exact ordinal");
              if (member.encodedBytes() > current.policy().maximumObjectBytes())
                throw fail(LIMIT_EXCEEDED, "Resolved object exceeds authorized bound");
              insertJob(connection, target, member);
            }
            try (var update =
                connection.prepareStatement(
                    "UPDATE build_retention_intents SET next_ordinal=? WHERE target_id=?")) {
              update.setLong(1, current.nextOrdinal() + wanted);
              update.setString(2, target.identity());
              update.executeUpdate();
            }
            return require(connection, target);
          });
    }
  }

  @Override
  public Optional<Job> claimNext(Target target, long generation, CancellationToken cancellation) {
    gate(cancellation);
    try (var opened = inventory.openSealed(target, cancellation)) {
      var seal = checkedSeal(target, opened);
      return authorityTransaction(
          cancellation,
          opened,
          connection -> {
            var current = require(connection, target);
            requireActive(current, generation);
            if (seal.memberCount() != current.memberCount())
              throw fail(CORRUPT_STATE, "Inventory count changed");
            if (count(connection, "state='RUNNING'") >= MAXIMUM_RUNNING) return Optional.empty();
            Job job;
            try (var query =
                connection.prepareStatement(
                    "SELECT * FROM build_retention_jobs WHERE target_id=? AND state='QUEUED' ORDER"
                        + " BY ordinal LIMIT 1")) {
              query.setString(1, target.identity());
              try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                job = job(rows, target);
              }
            }
            var authoritative =
                List.copyOf(opened.members(job.member().ordinal(), 1, cancellation));
            if (authoritative.size() != 1 || !authoritative.getFirst().equals(job.member()))
              throw fail(
                  CORRUPT_STATE,
                  "Queued source or reservation differs from sealed inventory authority");
            if (job.member().encodedBytes() > current.policy().maximumObjectBytes())
              throw fail(LIMIT_EXCEEDED, "Object exceeds authorized bound");
            if (job.attempts() >= current.policy().maximumAttempts())
              throw fail(LIMIT_EXCEEDED, "Actual source attempt limit exhausted");
            var total = add(current.reserved(), job.member().cost(), current.policy());
            var nonce = UUID.randomUUID();
            try (var update =
                connection.prepareStatement(
                    "UPDATE build_retention_jobs SET"
                        + " state='RUNNING',attempts=attempts+1,worker_nonce=?,owner_epoch=?,lease_generation=?,failure_code=NULL,failure_message=NULL"
                        + " WHERE target_id=? AND member_id=?")) {
              update.setString(1, nonce.toString());
              update.setString(2, session.epoch().toString());
              update.setLong(3, generation);
              update.setString(4, target.identity());
              update.setString(5, job.member().memberId());
              update.executeUpdate();
            }
            try (var update =
                connection.prepareStatement(
                    "UPDATE build_retention_intents SET"
                        + " reserved_wire=?,reserved_additional=?,reserved_requests=? WHERE"
                        + " target_id=?")) {
              update.setLong(1, total.wireBytes());
              update.setLong(2, total.additionalBytes());
              update.setLong(3, total.requests());
              update.setString(4, target.identity());
              update.executeUpdate();
            }
            return Optional.of(requireJob(connection, target, job.member().memberId()));
          });
    }
  }

  @Override
  public Job finish(Lease lease, Finish finish, CancellationToken cancellation) {
    Objects.requireNonNull(lease);
    Objects.requireNonNull(finish);
    return transaction(
        cancellation,
        connection -> {
          if (!lease.storeEpoch().equals(session.epoch()))
            throw fail(STALE_GENERATION, "Actual job lease belongs to an old store epoch");
          var target = targetById(connection, lease.targetId());
          var job = requireJob(connection, target, lease.memberId());
          if (job.lease().isEmpty() || !job.lease().get().equals(lease))
            throw fail(STALE_GENERATION, "Actual job nonce already retired or changed");
          try (var update =
              connection.prepareStatement(
                  "UPDATE build_retention_jobs SET"
                      + " state=?,worker_nonce=NULL,owner_epoch=NULL,lease_generation=NULL,fulfillment_proof=?,failure_code=?,failure_message=?"
                      + " WHERE target_id=? AND member_id=? AND worker_nonce=? AND"
                      + " owner_epoch=?")) {
            update.setString(1, finish.state().name());
            update.setString(2, finish.fulfillmentProof().orElse(null));
            update.setString(3, finish.failure().map(value -> value.code().name()).orElse(null));
            update.setString(4, finish.failure().map(Failure::message).orElse(null));
            update.setString(5, lease.targetId());
            update.setString(6, lease.memberId());
            update.setString(7, lease.workerNonce().toString());
            update.setString(8, lease.storeEpoch().toString());
            if (update.executeUpdate() != 1)
              throw fail(STALE_GENERATION, "Job ownership changed before completion");
          }
          return requireJob(connection, target, lease.memberId());
        });
  }

  @Override
  public Job retryFailed(
      Target target, long generation, String memberId, CancellationToken cancellation) {
    return transaction(
        cancellation,
        connection -> {
          var current = require(connection, target);
          requireActive(current, generation);
          var job = requireJob(connection, target, memberId);
          if (job.state() != State.FAILED)
            throw fail(BUSY, "Only an actually retired failed job can retry");
          if (job.attempts() >= current.policy().maximumAttempts())
            throw fail(LIMIT_EXCEEDED, "Attempt limit exhausted");
          if (count(connection, "state IN ('QUEUED','RUNNING')") >= MAXIMUM_QUEUED)
            throw fail(BUSY, "Bounded queue is full");
          try (var update =
              connection.prepareStatement(
                  "UPDATE build_retention_jobs SET state='QUEUED' WHERE target_id=? AND"
                      + " member_id=?")) {
            update.setString(1, target.identity());
            update.setString(2, memberId);
            update.executeUpdate();
          }
          return requireJob(connection, target, memberId);
        });
  }

  @Override
  public List<Job> pageJobs(
      Target target, long afterOrdinal, int maximum, CancellationToken cancellation) {
    if (afterOrdinal < -1 || maximum < 1 || maximum > MAXIMUM_FILL)
      throw fail(INVALID_REQUEST, "Bounded keyset job page required");
    return transaction(
        cancellation,
        connection -> {
          require(connection, target);
          var jobs = new ArrayList<Job>();
          try (var query =
              connection.prepareStatement(
                  "SELECT * FROM build_retention_jobs WHERE target_id=? AND ordinal>? ORDER BY"
                      + " ordinal LIMIT ?")) {
            query.setString(1, target.identity());
            query.setLong(2, afterOrdinal);
            query.setInt(3, maximum);
            try (var rows = query.executeQuery()) {
              while (rows.next()) {
                cancellation.throwIfCancellationRequested();
                jobs.add(job(rows, target));
              }
            }
          }
          return List.copyOf(jobs);
        });
  }

  private static int resumeCancelled(
      Connection connection, Target target, int maximum, int attempts) throws SQLException {
    var ids = new ArrayList<String>();
    try (var query =
        connection.prepareStatement(
            "SELECT member_id FROM build_retention_jobs WHERE target_id=? AND state='FAILED' AND"
                + " failure_code='CANCELLED' AND attempts<? ORDER BY ordinal LIMIT ?")) {
      query.setString(1, target.identity());
      query.setInt(2, attempts);
      query.setInt(3, maximum);
      try (var rows = query.executeQuery()) {
        while (rows.next()) ids.add(rows.getString(1));
      }
    }
    try (var update =
        connection.prepareStatement(
            "UPDATE build_retention_jobs SET state='QUEUED',failure_code=NULL,failure_message=NULL"
                + " WHERE target_id=? AND member_id=?")) {
      for (var id : ids) {
        update.setString(1, target.identity());
        update.setString(2, id);
        update.executeUpdate();
      }
    }
    return ids.size();
  }

  private static Seal checkedSeal(Target target, BuildRetentionInventory.Opened opened) {
    var seal = Objects.requireNonNull(opened).seal();
    if (seal == null || !seal.target().equals(target))
      throw fail(CORRUPT_STATE, "Inventory authority changed exact target");
    return seal;
  }

  private static void insertJob(Connection c, Target target, Member member) throws SQLException {
    try (var insert =
        c.prepareStatement(
            "INSERT INTO"
                + " build_retention_jobs(target_id,member_id,ordinal,content_key,encoding_key,encoded_bytes,cost_wire,cost_additional,cost_requests,membership_commitment,state,attempts)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,'QUEUED',0)")) {
      insert.setString(1, target.identity());
      insert.setString(2, member.memberId());
      insert.setLong(3, member.ordinal());
      insert.setString(4, member.contentKey());
      insert.setString(5, member.encodingKey());
      insert.setLong(6, member.encodedBytes());
      insert.setLong(7, member.cost().wireBytes());
      insert.setLong(8, member.cost().additionalBytes());
      insert.setLong(9, member.cost().requests());
      insert.setString(10, member.membershipCommitment());
      insert.executeUpdate();
    }
  }

  private static Optional<Intent> read(Connection c, Target expected) throws SQLException {
    try (var query =
        c.prepareStatement("SELECT * FROM build_retention_intents WHERE target_id=?")) {
      query.setString(1, expected.identity());
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        var target = target(rows);
        if (!target.equals(expected)) throw fail(CORRUPT_STATE, "Stored target identity changed");
        return Optional.of(intent(rows, target));
      }
    }
  }

  private static Intent require(Connection c, Target target) throws SQLException {
    return read(c, target).orElseThrow(() -> fail(NOT_FOUND, "Retention intent not found"));
  }

  private static Target targetById(Connection c, String id) throws SQLException {
    try (var query =
        c.prepareStatement("SELECT * FROM build_retention_intents WHERE target_id=?")) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw fail(NOT_FOUND, "Job intent not found");
        var target = target(rows);
        if (!target.identity().equals(id))
          throw fail(CORRUPT_STATE, "Stored target commitment changed");
        return target;
      }
    }
  }

  private static Target target(ResultSet r) throws SQLException {
    return new Target(
        r.getString("group_id"),
        r.getString("snapshot_id"),
        r.getString("admission_id"),
        r.getString("inventory_id"),
        integer(r, "inventory_generation"),
        r.getString("scope_revision"),
        r.getString("scope_commitment"));
  }

  private static Intent intent(ResultSet r, Target target) throws SQLException {
    return new Intent(
        target,
        Mode.valueOf(r.getString("mode")),
        integer(r, "generation"),
        new Policy(
            integer(r, "maximum_object"),
            integer(r, "maximum_wire"),
            integer(r, "maximum_additional"),
            integer(r, "maximum_requests"),
            smallInteger(r, "maximum_attempts")),
        Instant.parse(r.getString("authorized_at")),
        integer(r, "member_count"),
        integer(r, "next_ordinal"),
        new Cost(
            integer(r, "reserved_wire"),
            integer(r, "reserved_additional"),
            integer(r, "reserved_requests")));
  }

  private static Job requireJob(Connection c, Target target, String id) throws SQLException {
    try (var query =
        c.prepareStatement(
            "SELECT * FROM build_retention_jobs WHERE target_id=? AND member_id=?")) {
      query.setString(1, target.identity());
      query.setString(2, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw fail(NOT_FOUND, "Inventory job not found");
        return job(rows, target);
      }
    }
  }

  private static Job job(ResultSet r, Target target) throws SQLException {
    var member =
        new Member(
            integer(r, "ordinal"),
            r.getString("member_id"),
            r.getString("content_key"),
            r.getString("encoding_key"),
            integer(r, "encoded_bytes"),
            new Cost(
                integer(r, "cost_wire"),
                integer(r, "cost_additional"),
                integer(r, "cost_requests")),
            r.getString("membership_commitment"));
    var nonce = r.getString("worker_nonce");
    var failure = r.getString("failure_code");
    return new Job(
        target,
        member,
        State.valueOf(r.getString("state")),
        smallInteger(r, "attempts"),
        nonce == null
            ? Optional.empty()
            : Optional.of(
                new Lease(
                    target.identity(),
                    member.memberId(),
                    integer(r, "lease_generation"),
                    UUID.fromString(nonce),
                    UUID.fromString(r.getString("owner_epoch")))),
        Optional.ofNullable(r.getString("fulfillment_proof")),
        failure == null
            ? Optional.empty()
            : Optional.of(
                new Failure(FailureCode.valueOf(failure), r.getString("failure_message"))));
  }

  private static long integer(ResultSet rows, String column) throws SQLException {
    Object value = rows.getObject(column);
    if (value instanceof Integer number) return number.longValue();
    if (value instanceof Long number) return number;
    throw fail(CORRUPT_STATE, "SQLite INTEGER storage required for " + column);
  }

  private static int smallInteger(ResultSet rows, String column) throws SQLException {
    long value = integer(rows, column);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
      throw fail(CORRUPT_STATE, "Integer field exceeds supported range: " + column);
    return (int) value;
  }

  private static long count(Connection c, String predicate) throws SQLException {
    try (var q = c.createStatement();
        var r = q.executeQuery("SELECT count(*) FROM build_retention_jobs WHERE " + predicate)) {
      r.next();
      return r.getLong(1);
    }
  }

  private static Cost add(Cost prior, Cost cost, Policy policy) {
    try {
      var total =
          new Cost(
              Math.addExact(prior.wireBytes(), cost.wireBytes()),
              Math.addExact(prior.additionalBytes(), cost.additionalBytes()),
              Math.addExact(prior.requests(), cost.requests()));
      if (total.wireBytes() > policy.maximumWireBytes()
          || total.additionalBytes() > policy.maximumAdditionalBytes()
          || total.requests() > policy.maximumRequests())
        throw fail(QUOTA_EXCEEDED, "Reserved source costs exceed explicit authorization");
      return total;
    } catch (ArithmeticException overflow) {
      throw fail(QUOTA_EXCEEDED, "Reserved costs exceed supported accounting range");
    }
  }

  private static void requireGeneration(Intent intent, long generation) {
    if (intent.generation() != generation) throw fail(STALE_GENERATION, "Mode generation changed");
  }

  private static void requireActive(Intent intent, long generation) {
    requireGeneration(intent, generation);
    if (intent.mode() != Mode.DOWNLOAD_ALL) throw fail(NOT_READY, "Download all is paused");
  }

  private void gate(CancellationToken cancellation) {
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    session.beforeProducerOperation();
  }

  private <T> T transaction(CancellationToken cancellation, Work<T> work) {
    gate(cancellation);
    try (var connection = catalog.connect()) {
      connection.setAutoCommit(false);
      try {
        var result = work.run(connection);
        cancellation.throwIfCancellationRequested();
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException failure) {
        rollback(connection, failure);
        throw failure;
      }
    } catch (SQLException failure) {
      throw unavailable(failure);
    } catch (IllegalArgumentException failure) {
      throw new BuildRetentionException(CORRUPT_STATE, "Malformed retained ledger facts", failure);
    }
  }

  private static void rollback(Connection c, Throwable failure) {
    try {
      c.rollback();
    } catch (SQLException suppressed) {
      failure.addSuppressed(suppressed);
    }
  }

  private static BuildRetentionException fail(BuildRetentionException.Code code, String message) {
    return new BuildRetentionException(code, message);
  }

  private static BuildRetentionException unavailable(Throwable cause) {
    return new BuildRetentionException(
        STORAGE_UNAVAILABLE, "Build retention ledger is unavailable", cause);
  }
}
