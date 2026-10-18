package io.vibeminer.infrastructure.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static io.vibeminer.infrastructure.buildretention.SqliteBuildRetentionRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RetentionAuthorityTransactionTest {
  @TempDir Path directory;

  @Test
  void authorityRecheckedAfterWorkAndFailureRollsBackModeAtomically() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var controlled = new SqliteBuildRetentionRepositoryTest.Inventory();
    int[] calls = {0};
    var authority =
        new SqliteBuildRetentionRepository.TransactionalInventory() {
          public Opened openSealed(Target t, CancellationToken token) {
            return controlled.openSealed(t, token);
          }

          public void validateInTransaction(Connection c, Opened opened, CancellationToken token) {
            try {
              assertFalse(c.getAutoCommit());
            } catch (SQLException e) {
              throw new AssertionError(e);
            }
            if (++calls[0] == 2)
              throw new BuildRetentionException(
                  BuildRetentionException.Code.STALE_GENERATION, "Revoked at commit gate");
          }
        };
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), authority);
      assertThrows(
          BuildRetentionException.class,
          () -> repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE));
      assertEquals(2, calls[0]);
      assertTrue(repo.readIntent(TARGET, NONE).isEmpty());
      assertEquals(controlled.opened, controlled.closed);
    }
  }

  @Test
  void pendingProjectionIncludesActualRunningUntilFinallyButNotFailedHistory() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var authority = new SqliteBuildRetentionRepositoryTest.Inventory();
    authority.count = 1;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), authority);
      var intent = repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      assertFalse(repo.hasPending(TARGET, NONE));
      repo.fillWindow(TARGET, intent.generation(), 128, NONE);
      assertTrue(repo.hasPending(TARGET, NONE));
      var running = repo.claimNext(TARGET, intent.generation(), NONE).orElseThrow();
      repo.setMode(TARGET, intent.generation(), Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
      assertTrue(repo.hasPending(TARGET, NONE), "Paused demand must not retire actual producer");
      repo.finish(
          running.lease().orElseThrow(),
          new Finish(
              State.FAILED,
              Optional.empty(),
              Optional.of(new Failure(FailureCode.CANCELLED, "Retired"))),
          NONE);
      assertFalse(repo.hasPending(TARGET, NONE));
    }
  }
}
