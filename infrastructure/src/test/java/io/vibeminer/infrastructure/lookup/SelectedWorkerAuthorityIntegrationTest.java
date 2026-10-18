package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent application coalescers share actual SQLite authority and synthetic CDN protocol. */
class SelectedWorkerAuthorityIntegrationTest {
  @TempDir Path directory;

  @Test
  void independentSelectedProducersCannotRetireHeldWorkersAndOriginalCanComplete()
      throws Exception {
    exercise(false);
  }

  @Test
  void lastFacadeCancellationCannotReleaseSqlWorkerBeforeActualSourceExit() throws Exception {
    exercise(true);
  }

  private void exercise(boolean cancel) throws Exception {
    try (var setup =
            new OfficialSelectedFileGatewayTest.Setup(directory.resolve("objects"), false);
        var jobs = Executors.newFixedThreadPool(3);
        var sources = Executors.newFixedThreadPool(2)) {
      var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      var objects = new SqliteBuildObjectCatalog(sql, setup.store);
      objects.registerSnapshot(setup.build);
      attach(objects, setup, "BUILD_CONFIG", setup.config);
      attach(objects, setup, "ROOT_MANIFEST", setup.root);
      // This test isolates the nonce fence using the compatibility session constructor.
      // Exclusive store startup capability/recovery is tested independently.
      var session = UUID.randomUUID();
      var firstFiles = new SqliteSelectedFileCatalog(sql, setup.store, session);
      var secondFiles = new SqliteSelectedFileCatalog(sql, setup.store, session);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var retired = new CountDownLatch(1);
      var calls = new AtomicInteger();
      var gateway =
          (SelectedFileGateway)
              Proxy.newProxyInstance(
                  SelectedFileGateway.class.getClassLoader(),
                  new Class<?>[] {SelectedFileGateway.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("acquireEncoded") && calls.incrementAndGet() == 1) {
                      entered.countDown();
                      hold(release);
                      if (cancel)
                        throw new EncodedAcquisitionFailure(
                            EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                            new SelectedFileException(
                                SelectedFileException.Code.CANCELLED, "Synthetic retired source"));
                    }
                    try {
                      return method.invoke(setup.gateway, arguments);
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
      Executor acquisition =
          task ->
              sources.execute(
                  () -> {
                    try {
                      task.run();
                    } finally {
                      retired.countDown();
                    }
                  });
      try (var first =
              new DefaultSelectedFileService(
                  gateway,
                  objects,
                  firstFiles,
                  new SqliteRetentionRepository(sql),
                  jobs,
                  acquisition);
          var second =
              new DefaultSelectedFileService(
                  gateway,
                  objects,
                  secondFiles,
                  new SqliteRetentionRepository(sql),
                  jobs,
                  sources)) {
        var budgets = SelectedFileBudgets.defaults();
        var a =
            first
                .planCandidates(setup.build, setup.variant, budgets)
                .toCompletableFuture()
                .get(3, TimeUnit.SECONDS);
        var b =
            second
                .planCandidates(setup.build, setup.variant, budgets)
                .toCompletableFuture()
                .get(3, TimeUnit.SECONDS);
        var selected = setup.fixture.selectedEKey;
        var aChoice = OfficialSelectedFileGatewayTest.choice(a, selected);
        var bChoice = OfficialSelectedFileGatewayTest.choice(b, selected);
        var aPayload =
            first
                .probeLoose(aChoice)
                .toCompletableFuture()
                .get(3, TimeUnit.SECONDS)
                .plan()
                .orElseThrow();
        var bPayload =
            second
                .probeLoose(bChoice)
                .toCompletableFuture()
                .get(3, TimeUnit.SECONDS)
                .plan()
                .orElseThrow();
        var primary = first.retain(aPayload).toCompletableFuture();
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          var before = header(sql);
          assertNotNull(before.get(2));
          assertFailure(second.retain(bPayload), SelectedFileException.Code.BUSY);
          assertEquals(before, header(sql));
          assertEquals(1, calls.get());
          if (cancel) {
            assertTrue(primary.cancel(true));
            assertFailure(second.retain(bPayload), SelectedFileException.Code.BUSY);
            assertEquals(before, header(sql));
            assertFalse(retired.await(0, TimeUnit.SECONDS));
          }
          release.countDown();
          assertTrue(retired.await(3, TimeUnit.SECONDS));
          if (cancel) {
            assertTrue(header(sql).isEmpty());
            var restored = second.retain(bPayload).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(selected, restored.descriptor().actualEncodingKey());
            assertEquals(2, calls.get());
          } else {
            var original = primary.get(3, TimeUnit.SECONDS);
            var reused = second.retain(bPayload).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(original.representation(), reused.representation());
            assertEquals(1, calls.get());
          }
          var after = header(sql);
          assertEquals("RETAINED", after.get(1));
          assertNull(after.get(2));
        } finally {
          release.countDown();
        }
      }
    }
  }

  private static List<String> header(SqliteCatalog sql) throws Exception {
    try (var connection = sql.connect();
        var rows =
            connection
                .createStatement()
                .executeQuery("SELECT token,status,worker_nonce FROM canonical_content_slots")) {
      if (!rows.next()) return List.of();
      return Arrays.asList(rows.getString(1), rows.getString(2), rows.getString(3));
    }
  }

  private static void attach(
      SqliteBuildObjectCatalog objects,
      OfficialSelectedFileGatewayTest.Setup setup,
      String role,
      io.vibeminer.domain.VerifiedSourceAlias alias) {
    objects.attachVerifiedObject(
        setup.build.snapshotId(),
        role,
        alias.source(),
        alias.object(),
        alias.evidence(),
        CancellationToken.none());
  }

  private static void assertFailure(CompletionStage<?> job, SelectedFileException.Code expected) {
    var failure =
        assertThrows(
            ExecutionException.class, () -> job.toCompletableFuture().get(3, TimeUnit.SECONDS));
    assertEquals(
        expected, assertInstanceOf(SelectedFileException.class, failure.getCause()).code());
  }

  private static void hold(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }
}
