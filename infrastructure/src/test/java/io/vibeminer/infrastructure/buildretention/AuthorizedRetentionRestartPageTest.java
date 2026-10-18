package io.vibeminer.infrastructure.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static io.vibeminer.infrastructure.buildretention.SqliteBuildRetentionRepositoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildretention.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AuthorizedRetentionRestartPageTest {
  @TempDir Path directory;
  final SqliteBuildRetentionRepositoryTest.Inventory inventory =
      new SqliteBuildRetentionRepositoryTest.Inventory();

  Target target(int n) {
    return new Target(
        String.format("%064x", n),
        String.format("%064x", n + 100),
        TARGET.admissionId(),
        String.format("%064x", n + 200),
        n,
        "all-declared-v1",
        String.format("%064x", n + 300));
  }

  @Test
  void pagesOnlyPriorAuthorizedExactScopesAndStoredPolicyWithoutSourceWork() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), inventory);
      var expected = new ArrayList<Intent>();
      for (int n = 1; n <= 17; n++)
        expected.add(
            repo.setMode(
                target(n),
                0,
                Mode.DOWNLOAD_ALL,
                new Policy(100 + n, 10000 + n, 20000 + n, 30000 + n, 3),
                Instant.EPOCH.plusSeconds(n),
                NONE));
      var paused = repo.setMode(target(18), 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      repo.setMode(
          paused.target(), paused.generation(), Mode.ON_DEMAND, POLICY, Instant.EPOCH, NONE);
      int opened = inventory.opened;
      inventory.ready = false;
      expected.sort(Comparator.comparing(i -> i.target().identity()));
      var first = repo.pageAuthorizedIntents("", 16, NONE);
      assertEquals(expected.subList(0, 16), first);
      assertThrows(UnsupportedOperationException.class, () -> first.clear());
      var second = repo.pageAuthorizedIntents(first.getLast().target().identity(), 16, NONE);
      assertEquals(expected.subList(16, 17), second);
      assertTrue(
          repo.pageAuthorizedIntents(second.getLast().target().identity(), 1, NONE).isEmpty());
      assertEquals(
          opened,
          inventory.opened,
          "Listing restart authorization must not open inventory/CAS/transport");
      assertEquals(16, first.size());
    }
  }

  @Test
  void freshCatalogPausedModeAndInvalidBoundsCannotStartWork() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), inventory);
      assertTrue(repo.pageAuthorizedIntents("", 16, NONE).isEmpty());
      for (String cursor : Arrays.asList(null, "relative", "A".repeat(64)))
        assertEquals(
            BuildRetentionException.Code.INVALID_REQUEST,
            assertThrows(
                    BuildRetentionException.class,
                    () -> repo.pageAuthorizedIntents(cursor, 16, NONE))
                .code());
      for (int maximum : new int[] {0, 17})
        assertEquals(
            BuildRetentionException.Code.INVALID_REQUEST,
            assertThrows(
                    BuildRetentionException.class,
                    () -> repo.pageAuthorizedIntents("", maximum, NONE))
                .code());
      assertEquals(0, inventory.opened);
    }
  }

  @Test
  void malformedPolicyAndChangedStoredTargetCommitmentFailWholePage() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), inventory);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      try (var c = catalog.connect();
          var s = c.createStatement()) {
        s.execute("PRAGMA ignore_check_constraints=ON");
        s.execute("UPDATE build_retention_intents SET maximum_wire=10.5");
      }
      assertEquals(
          BuildRetentionException.Code.CORRUPT_STATE,
          assertThrows(
                  BuildRetentionException.class, () -> repo.pageAuthorizedIntents("", 16, NONE))
              .code());
      try (var c = catalog.connect();
          var s = c.createStatement()) {
        s.execute(
            "UPDATE build_retention_intents SET maximum_wire=10000,scope_commitment='"
                + "1".repeat(64)
                + "'");
      }
      assertEquals(
          BuildRetentionException.Code.CORRUPT_STATE,
          assertThrows(
                  BuildRetentionException.class, () -> repo.pageAuthorizedIntents("", 16, NONE))
              .code());
    }
  }

  @Test
  void malformedTimestampIsTypedCorruptionAndCancellationStaysCancellation() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), inventory);
      repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
      try (var c = catalog.connect();
          var s = c.createStatement()) {
        s.execute("UPDATE build_retention_intents SET authorized_at='bad'");
      }
      assertEquals(
          BuildRetentionException.Code.CORRUPT_STATE,
          assertThrows(
                  BuildRetentionException.class, () -> repo.pageAuthorizedIntents("", 16, NONE))
              .code());
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> repo.pageAuthorizedIntents("", 16, () -> true));
    }
  }

  @Test
  void newStoreLifetimeReadsOriginalAuthorizationWithoutReinterpretingScope() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    Intent original;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), inventory);
      original = repo.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY, Instant.EPOCH, NONE);
    }
    inventory.ready = false;
    int sourceWork = inventory.opened;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      SqliteBuildRetentionRepository.recoverAtStartup(catalog, store.contentSession());
      var repo =
          new SqliteBuildRetentionRepository(catalog, store, store.contentSession(), inventory);
      assertEquals(List.of(original), repo.pageAuthorizedIntents("", 16, NONE));
      assertEquals(sourceWork, inventory.opened);
      try (var c = catalog.connect()) {
        try (var q = c.createStatement();
            var plan =
                q.executeQuery(
                    "EXPLAIN QUERY PLAN SELECT target_id FROM build_retention_intents WHERE"
                        + " mode='DOWNLOAD_ALL' AND target_id>'' ORDER BY target_id LIMIT 16")) {
          boolean indexed = false;
          while (plan.next())
            indexed |= plan.getString(4).contains("build_retention_authorized_target");
          assertTrue(indexed, "Restart page uses the bounded mode/target keyset index");
        }
      }
    }
  }
}
