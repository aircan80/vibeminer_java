package io.vibeminer.infrastructure.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SqliteBuildRetentionRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Target TARGET =
      new Target(
          "a".repeat(64),
          "b".repeat(64),
          "c".repeat(64),
          "d".repeat(64),
          1,
          "all-declared-v1",
          "e".repeat(64));
  static final Policy POLICY = new Policy(100, 10000, 10000, 10000, 3);
  final Inventory inventory = new Inventory();

  static final class Inventory implements BuildRetentionInventory {
    boolean ready = true;
    int opened, closed;
    long count = 300;

    public Opened openSealed(Target target, CancellationToken token) {
      opened++;
      if (!ready)
        throw new BuildRetentionException(BuildRetentionException.Code.NOT_READY, "Unresolved DL");
      return new Opened() {
        public Seal seal() {
          return new Seal(target, count, "test-sealed-inventory-v1");
        }

        public List<Member> members(long first, int maximum, CancellationToken cancellation) {
          var rows = new ArrayList<Member>();
          for (long i = first; i < Math.min(count, first + maximum); i++) rows.add(member(i));
          return rows;
        }

        public void close() {
          closed++;
        }
      };
    }
  }

  static Member member(long ordinal) {
    return new Member(
        ordinal,
        String.format("%064x", ordinal + 1),
        String.format("%032x", ordinal + 1),
        String.format("%032x", ordinal + 1000),
        10,
        new Cost(10, 10, 1),
        "f".repeat(64));
  }

  SqliteCatalog catalog() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var c = catalog.connect()) {
      BuildRetentionSchema.install(c);
    }
    return catalog;
  }

  SqliteBuildRetentionRepository repo(SqliteCatalog catalog, FileObjectStore store) {
    SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
    return new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), inventory);
  }

  @Test
  void durableModeRequiresRealAuthorityAndStaleGenerationCannotResume() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      inventory.ready = false;
      assertEquals(
          BuildRetentionException.Code.NOT_READY,
          assertThrows(
                  BuildRetentionException.class,
                  () -> repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE))
              .code());
      assertTrue(repo.readIntent(TARGET, NONE).isEmpty());
      inventory.ready = true;
      var accepted = repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      assertEquals(accepted, repo.readIntent(TARGET, NONE).orElseThrow());
      var paused = repo.setMode(TARGET, 1, Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
      assertEquals(2, paused.generation());
      assertEquals(
          BuildRetentionException.Code.STALE_GENERATION,
          assertThrows(BuildRetentionException.class, () -> repo.fillWindow(TARGET, 1, 128, NONE))
              .code());
      assertEquals(
          BuildRetentionException.Code.STALE_GENERATION,
          assertThrows(
                  BuildRetentionException.class,
                  () -> repo.setMode(TARGET, 1, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE))
              .code());
      assertEquals(Mode.ON_DEMAND, repo.readIntent(TARGET, NONE).orElseThrow().mode());
    }
  }

  @Test
  void windowsArePagedAndQueueNeverExceeds256WithoutFutures() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      assertEquals(128, repo.fillWindow(TARGET, 1, 128, NONE).nextOrdinal());
      assertEquals(256, repo.fillWindow(TARGET, 1, 128, NONE).nextOrdinal());
      assertEquals(256, repo.fillWindow(TARGET, 1, 128, NONE).nextOrdinal());
      assertEquals(128, repo.pageJobs(TARGET, -1, 128, NONE).size());
      assertEquals(128, repo.pageJobs(TARGET, 127, 128, NONE).size());
      assertTrue(repo.pageJobs(TARGET, 255, 128, NONE).isEmpty());
      assertEquals(
          BuildRetentionException.Code.INVALID_REQUEST,
          assertThrows(BuildRetentionException.class, () -> repo.fillWindow(TARGET, 1, 129, NONE))
              .code());
      assertEquals(inventory.opened, inventory.closed);
    }
  }

  @Test
  void claimReservesCostsBeforeIoAndTwoActualLeasesSurviveModeOff() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 128, NONE);
      var a = repo.claimNext(TARGET, 1, NONE).orElseThrow();
      var b = repo.claimNext(TARGET, 1, NONE).orElseThrow();
      assertTrue(repo.claimNext(TARGET, 1, NONE).isEmpty());
      assertEquals(new Cost(20, 20, 2), repo.readIntent(TARGET, NONE).orElseThrow().reserved());
      repo.setMode(TARGET, 1, Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
      var completed =
          repo.finish(
              a.lease().orElseThrow(),
              new Finish(State.SUCCEEDED, Optional.of("1".repeat(64)), Optional.empty()),
              NONE);
      assertEquals(State.SUCCEEDED, completed.state());
      repo.finish(
          b.lease().orElseThrow(),
          new Finish(
              State.FAILED,
              Optional.empty(),
              Optional.of(new Failure(FailureCode.CANCELLED, "Bulk consumer withdrew"))),
          NONE);
      assertEquals(Mode.ON_DEMAND, repo.readIntent(TARGET, NONE).orElseThrow().mode());
      assertEquals(new Cost(20, 20, 2), repo.readIntent(TARGET, NONE).orElseThrow().reserved());
      assertThrows(
          BuildRetentionException.class,
          () ->
              repo.finish(
                  a.lease().orElseThrow(),
                  new Finish(State.SUCCEEDED, Optional.of("1".repeat(64)), Optional.empty()),
                  NONE));
    }
  }

  @Test
  void quotaRefusalDoesNotClaimOrRefundPriorReservations() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(
          TARGET, 0, Mode.DOWNLOAD_ALL, new Policy(100, 10, 10, 1, 3), Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 10, NONE);
      var job = repo.claimNext(TARGET, 1, NONE).orElseThrow();
      repo.finish(
          job.lease().orElseThrow(),
          new Finish(
              State.FAILED,
              Optional.empty(),
              Optional.of(new Failure(FailureCode.UNAVAILABLE, "404"))),
          NONE);
      assertEquals(
          BuildRetentionException.Code.QUOTA_EXCEEDED,
          assertThrows(BuildRetentionException.class, () -> repo.claimNext(TARGET, 1, NONE))
              .code());
      assertEquals(10, repo.readIntent(TARGET, NONE).orElseThrow().reserved().wireBytes());
      assertEquals(State.QUEUED, repo.pageJobs(TARGET, 0, 1, NONE).getFirst().state());
    }
  }

  @Test
  void freshStoreEpochRecoversInterruptedJobsAndFencesOldFinally() throws Exception {
    var catalog = catalog();
    Lease old;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 5, NONE);
      old = repo.claimNext(TARGET, 1, NONE).orElseThrow().lease().orElseThrow();
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      assertEquals(Mode.DOWNLOAD_ALL, repo.readIntent(TARGET, NONE).orElseThrow().mode());
      assertEquals(State.QUEUED, repo.pageJobs(TARGET, -1, 1, NONE).getFirst().state());
      assertThrows(
          BuildRetentionException.class,
          () ->
              repo.finish(
                  old,
                  new Finish(State.SUCCEEDED, Optional.of("1".repeat(64)), Optional.empty()),
                  NONE));
      var next = repo.claimNext(TARGET, 1, NONE).orElseThrow();
      assertNotEquals(old.workerNonce(), next.lease().orElseThrow().workerNonce());
      assertEquals(2, next.attempts());
      assertEquals(20, repo.readIntent(TARGET, NONE).orElseThrow().reserved().wireBytes());
      assertThrows(
          IllegalStateException.class,
          () -> SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession()));
    }
  }

  @Test
  void cancelledFillRollsBackAndNoAutomaticSchemaIsInstalled() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> repo.fillWindow(TARGET, 1, 128, () -> true));
      assertEquals(0, repo.readIntent(TARGET, NONE).orElseThrow().nextOrdinal());
      try (var c = catalog.connect();
          var q = c.createStatement();
          var r = q.executeQuery("PRAGMA user_version")) {
        assertTrue(r.next());
        assertEquals(SqliteCatalog.SCHEMA_VERSION, r.getInt(1));
      }
    }
  }

  @Test
  void millionMemberInventoryOnlyMaterializesTheRequestedWindowAndCancellationRollsBack()
      throws Exception {
    inventory.count = 1_000_000;
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      var checks = new java.util.concurrent.atomic.AtomicInteger();
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> repo.fillWindow(TARGET, 1, 128, () -> checks.incrementAndGet() == 4));
      assertTrue(repo.pageJobs(TARGET, -1, 128, NONE).isEmpty());
      assertEquals(0, repo.readIntent(TARGET, NONE).orElseThrow().nextOrdinal());
      assertEquals(128, repo.fillWindow(TARGET, 1, 128, NONE).nextOrdinal());
      assertEquals(128, repo.pageJobs(TARGET, -1, 128, NONE).size());
      assertEquals(1_000_000, repo.readIntent(TARGET, NONE).orElseThrow().memberCount());
    }
  }

  @Test
  void explicitRetryKeepsChargedBudgetsAndCannotResetAttemptLimit() throws Exception {
    inventory.count = 1;
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(
          TARGET, 0, Mode.DOWNLOAD_ALL, new Policy(100, 1000, 1000, 1000, 2), Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 1, NONE);
      var a = repo.claimNext(TARGET, 1, NONE).orElseThrow();
      assertEquals(
          BuildRetentionException.Code.BUSY,
          assertThrows(
                  BuildRetentionException.class,
                  () -> repo.retryFailed(TARGET, 1, a.member().memberId(), NONE))
              .code());
      repo.finish(
          a.lease().orElseThrow(),
          new Finish(
              State.FAILED,
              Optional.empty(),
              Optional.of(new Failure(FailureCode.UNAVAILABLE, "404"))),
          NONE);
      repo.retryFailed(TARGET, 1, a.member().memberId(), NONE);
      var b = repo.claimNext(TARGET, 1, NONE).orElseThrow();
      assertEquals(2, b.attempts());
      repo.finish(
          b.lease().orElseThrow(),
          new Finish(
              State.FAILED,
              Optional.empty(),
              Optional.of(new Failure(FailureCode.UNAVAILABLE, "404"))),
          NONE);
      assertEquals(
          BuildRetentionException.Code.LIMIT_EXCEEDED,
          assertThrows(
                  BuildRetentionException.class,
                  () -> repo.retryFailed(TARGET, 1, a.member().memberId(), NONE))
              .code());
      assertEquals(new Cost(20, 20, 2), repo.readIntent(TARGET, NONE).orElseThrow().reserved());
    }
  }

  @Test
  void changingScopeRequiresExplicitPauseAndNeverEnablesTwoScopesForOneBuild() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      var changed =
          new Target(
              TARGET.groupId(),
              TARGET.snapshotId(),
              TARGET.admissionId(),
              TARGET.inventoryId(),
              2,
              TARGET.scopeRevision(),
              TARGET.scopeCommitment());
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      assertEquals(
          BuildRetentionException.Code.STALE_GENERATION,
          assertThrows(
                  BuildRetentionException.class,
                  () -> repo.setMode(changed, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE))
              .code());
      assertTrue(repo.readIntent(changed, NONE).isEmpty());
      repo.setMode(TARGET, 1, Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
      assertEquals(
          Mode.DOWNLOAD_ALL,
          repo.setMode(changed, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE).mode());
      assertEquals(Mode.ON_DEMAND, repo.readIntent(TARGET, NONE).orElseThrow().mode());
    }
  }

  @Test
  void queuedSourceTamperingCannotReachBudgetOrActualLeaseAdmission() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 1, NONE);
      try (var connection = catalog.connect();
          var update = connection.createStatement()) {
        update.executeUpdate(
            "UPDATE build_retention_jobs SET encoding_key='" + "9".repeat(32) + "'");
      }
      assertEquals(
          BuildRetentionException.Code.CORRUPT_STATE,
          assertThrows(BuildRetentionException.class, () -> repo.claimNext(TARGET, 1, NONE))
              .code());
      assertEquals(new Cost(0, 0, 0), repo.readIntent(TARGET, NONE).orElseThrow().reserved());
      assertTrue(repo.pageJobs(TARGET, -1, 1, NONE).getFirst().lease().isEmpty());
    }
  }

  @Test
  void modeOffWithdrawsQueuedIntentAndResumeReusesBoundedRows() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 128, NONE);
      repo.setMode(TARGET, 1, Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
      assertTrue(
          repo.pageJobs(TARGET, -1, 128, NONE).stream()
              .allMatch(
                  job ->
                      job.state() == State.FAILED
                          && job.failure().orElseThrow().code() == FailureCode.CANCELLED));
      repo.setMode(TARGET, 2, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      assertEquals(128, repo.fillWindow(TARGET, 3, 128, NONE).nextOrdinal());
      assertEquals(128, repo.pageJobs(TARGET, -1, 128, NONE).size());
      assertTrue(
          repo.pageJobs(TARGET, -1, 128, NONE).stream()
              .allMatch(job -> job.state() == State.QUEUED && job.attempts() == 0));
    }
  }

  @Test
  void fractionalStoredSourceCannotBeTruncatedIntoAnAuthenticatedMember() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 1, NONE);
      try (var connection = catalog.connect();
          var statement = connection.createStatement()) {
        statement.execute("PRAGMA ignore_check_constraints=ON");
        statement.executeUpdate("UPDATE build_retention_jobs SET encoded_bytes=10.5");
      }
      assertEquals(
          BuildRetentionException.Code.CORRUPT_STATE,
          assertThrows(BuildRetentionException.class, () -> repo.claimNext(TARGET, 1, NONE))
              .code());
      assertEquals(new Cost(0, 0, 0), repo.readIntent(TARGET, NONE).orElseThrow().reserved());
    }
  }

  @Test
  void everyNumericLedgerColumnRequiresSQLiteIntegerStorage() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 1, NONE);
      repo.claimNext(TARGET, 1, NONE);
      var columns =
          Map.of(
              "build_retention_intents",
              List.of(
                  "inventory_generation",
                  "generation",
                  "member_count",
                  "next_ordinal",
                  "maximum_object",
                  "maximum_wire",
                  "maximum_additional",
                  "maximum_requests",
                  "maximum_attempts",
                  "reserved_wire",
                  "reserved_additional",
                  "reserved_requests"),
              "build_retention_jobs",
              List.of(
                  "ordinal",
                  "encoded_bytes",
                  "cost_wire",
                  "cost_additional",
                  "cost_requests",
                  "attempts",
                  "lease_generation"));
      var cases = new ArrayList<org.junit.jupiter.api.function.Executable>();
      columns.forEach(
          (table, names) ->
              names.forEach(
                  column ->
                      cases.add(
                          () -> {
                            try (var connection = catalog.connect();
                                var statement = connection.createStatement()) {
                              connection.setAutoCommit(false);
                              try {
                                assertThrows(
                                    java.sql.SQLException.class,
                                    () ->
                                        statement.executeUpdate(
                                            "UPDATE " + table + " SET " + column + "=" + column
                                                + "+0.5"),
                                    column);
                              } finally {
                                connection.rollback();
                              }
                            }
                          })));
      assertAll(cases);
    }
  }

  @Test
  void interruptedPausedWorkerStaysWithdrawnAcrossRestartAndDoesNotOccupyQueue() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(TARGET, 1, 1, NONE);
      repo.claimNext(TARGET, 1, NONE);
      repo.setMode(TARGET, 1, Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repo = repo(catalog, store);
      var withdrawn = repo.pageJobs(TARGET, -1, 1, NONE).getFirst();
      assertEquals(State.FAILED, withdrawn.state());
      assertEquals(FailureCode.CANCELLED, withdrawn.failure().orElseThrow().code());
      repo.setMode(TARGET, 2, Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
      var other =
          new Target(
              "2".repeat(64),
              TARGET.snapshotId(),
              TARGET.admissionId(),
              TARGET.inventoryId(),
              1,
              TARGET.scopeRevision(),
              TARGET.scopeCommitment());
      repo.setMode(other, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.fillWindow(other, 1, 128, NONE);
      assertEquals(256, repo.fillWindow(other, 1, 128, NONE).nextOrdinal());
      assertEquals(Mode.ON_DEMAND, repo.readIntent(TARGET, NONE).orElseThrow().mode());
      assertEquals(new Cost(10, 10, 1), repo.readIntent(TARGET, NONE).orElseThrow().reserved());
      assertEquals(1, repo.pageJobs(TARGET, -1, 1, NONE).getFirst().attempts());
    }
  }

  @Test
  void publicRecoveryHelperCannotRunOutsideTheOneStartupTransaction() throws Exception {
    var catalog = catalog();
    try (var store = new FileObjectStore(directory.resolve("store"));
        var connection = catalog.connect()) {
      assertThrows(
          IllegalStateException.class,
          () ->
              SqliteBuildRetentionRepository.recoverInStartupTransaction(
                  connection, store.contentSession()));
      store
          .contentSession()
          .recoverAtStartup(
              () -> {
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        SqliteBuildRetentionRepository.recoverInStartupTransaction(
                            connection, store.contentSession()));
                try {
                  connection.setAutoCommit(false);
                  SqliteBuildRetentionRepository.recoverInStartupTransaction(
                      connection, store.contentSession());
                  connection.commit();
                } catch (java.sql.SQLException failure) {
                  throw new RuntimeException(failure);
                }
              });
      assertThrows(
          IllegalStateException.class,
          () ->
              SqliteBuildRetentionRepository.recoverInStartupTransaction(
                  connection, store.contentSession()));
      store.contentSession().beforeProducerOperation();
      assertThrows(
          IllegalStateException.class,
          () ->
              SqliteBuildRetentionRepository.recoverInStartupTransaction(
                  connection, store.contentSession()));
    }
  }
}
