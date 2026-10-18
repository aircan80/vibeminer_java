package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.infrastructure.lookup.MetadataRepairWorkerCompositionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.ContentWorkerFinishOutcome;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.retention.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Authored sources, real protocol/CAS/SQL, and independently cancellable identical consent. */
class MetadataRepairSharingIntegrationTest {
  @TempDir Path directory;

  private enum Cancellation {
    NONE,
    FIRST,
    ALL
  }

  @Test
  void twoSurvivorsShareOneTransferAndAuditWithoutDuplicatingPhysicalContent() throws Exception {
    verifySharing(Cancellation.NONE);
  }

  @Test
  void cancelingOneConsumerPreservesTheSurvivorsOriginalRepairAudit() throws Exception {
    verifySharing(Cancellation.FIRST);
  }

  @Test
  void cancelingAllConsumersKeepsActualSqlOwnershipUntilHeldIoRetires() throws Exception {
    verifySharing(Cancellation.ALL);
  }

  private void verifySharing(Cancellation cancellation) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workers = new Workers();
    try (var fixture = new Fixture(directory, 1)) {
      var oldBuild = fixture.wire.build("us.cdn.blizzard.com", "repair-retained-other");
      var builds = new SqliteBuildObjectCatalog(fixture.sql, fixture.store);
      builds.registerSnapshot(oldBuild);
      var configuration = fixture.selection.plan().configuration();
      var originalAlias = fixture.original.originalAlias();
      builds.attachVerifiedObject(
          oldBuild.snapshotId(),
          "BUILD_CONFIG",
          configuration.source(),
          configuration.object(),
          configuration.evidence(),
          NONE);
      builds.attachVerifiedObject(
          oldBuild.snapshotId(),
          "ROOT_MANIFEST",
          originalAlias.source(),
          originalAlias.object(),
          originalAlias.evidence(),
          NONE);
      var oldScope =
          new RetentionScope(
              oldBuild.snapshotId(),
              ScopeKind.ROOT_METADATA,
              List.of(
                  new ScopeRequirement(
                      "BUILD_CONFIG",
                      Optional.of(configuration.source()),
                      OptionalLong.of(configuration.object().length()),
                      OptionalLong.empty()),
                  new ScopeRequirement(
                      "ROOT_MANIFEST",
                      Optional.of(originalAlias.source()),
                      OptionalLong.of(originalAlias.object().length()),
                      OptionalLong.empty())));
      new SqliteRetentionRepository(fixture.sql).pin(oldScope, NONE);
      var oldReferences = references(fixture.sql, oldBuild.snapshotId(), oldScope.scopeId());
      long memberships = count(fixture.sql, "build_object_memberships");
      long objects = physicalObjects(fixture);

      var held =
          new LookupTransport() {
            public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
              throw new AssertionError("Repair cannot acquire configuration");
            }

            public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
              entered.countDown();
              while (true)
                try {
                  assertTrue(release.await(10, TimeUnit.SECONDS), "Held source was not released");
                  break;
                } catch (InterruptedException ignored) {
                  // Deliberately ignore cancellation until the test releases actual source IO.
                }
              return fixture.wire.range(request);
            }
          };
      // policy() installs a real SQL trigger rejecting attachments with an active publisher nonce.
      try (var policy = policy(fixture, held, workers, new AtomicLong())) {
        try {
          Files.delete(fixture.objectPath());
          var plan =
              policy
                  .service()
                  .prepareRoot(fixture.selection)
                  .toCompletableFuture()
                  .get(10, TimeUnit.SECONDS);
          int payloadRequests = fixture.wire.requests.size();
          var first = policy.service().execute(plan).toCompletableFuture();
          assertTrue(entered.await(10, TimeUnit.SECONDS));
          var second = policy.service().execute(plan).toCompletableFuture();
          assertFalse(second.isDone());
          assertEquals(2, workers.submitted.get(), "One preparation and one shared actual job");
          if (cancellation != Cancellation.NONE) assertTrue(first.cancel(true));
          if (cancellation == Cancellation.ALL) assertTrue(second.cancel(true));
          assertEquals(
              ContentClaimException.Code.BUSY,
              assertThrows(
                      ContentClaimException.class,
                      () -> fixture.claims.claimWorker(plan.reservationToken(), NONE))
                  .code());
          String alternate =
              plan.request().requestedEncodingKey().equals("f".repeat(32))
                  ? "e".repeat(32)
                  : "f".repeat(32);
          assertThrows(
              ContentClaimException.class,
              () ->
                  fixture.claims.reserve(
                      plan.request().slot(),
                      alternate,
                      plan.request().requestedEncodedBytes(),
                      NONE));
          assertEquals(0, count(fixture.sql, "metadata_build_associations"));
          assertEquals(1, workers.sourceRetired.getCount());
          release.countDown();

          if (cancellation == Cancellation.NONE) {
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(a.completedAudit(), b.completedAudit());
            assertEquals(a.association(), b.association());
          } else if (cancellation == Cancellation.FIRST) {
            assertTrue(first.isCancelled());
            assertTrue(second.get(10, TimeUnit.SECONDS).completedAudit().isPresent());
          }
          assertTrue(workers.sourceRetired.await(10, TimeUnit.SECONDS));
          assertEquals(1, count(fixture.sql, "metadata_repair_attempts"));
          if (cancellation != Cancellation.ALL) {
            assertEquals(1, count(fixture.sql, "metadata_build_associations"));
            assertEquals(2, count(fixture.sql, "retention_scopes"));
            assertEquals(memberships + 1, count(fixture.sql, "build_object_memberships"));
            assertEquals(objects, physicalObjects(fixture));
            var audit = fixture.completedAudit.get();
            assertNotNull(audit);
            var observation = audit.events().getLast().observation().orElseThrow();
            assertEquals(plan.originalProof(), observation.originalProof());
            assertEquals(
                observation.payloadRequestCount(), fixture.wire.requests.size() - payloadRequests);
            var recovered =
                (MetadataContentProof)
                    fixture
                        .claims
                        .lookup(fixture.claim.slot(), NONE)
                        .orElseThrow()
                        .proof()
                        .orElseThrow();
            assertEquals(originalAlias, recovered.originalAlias());
          } else {
            assertTrue(first.isCancelled());
            assertTrue(second.isCancelled());
            assertEquals(0, count(fixture.sql, "metadata_build_associations"));
            assertEquals(1, count(fixture.sql, "retention_scopes"));
            assertEquals(memberships, count(fixture.sql, "build_object_memberships"));
            assertEquals(objects - 1, physicalObjects(fixture));
            assertFalse(Files.exists(fixture.objectPath()));
            assertNull(fixture.completedAudit.get());
            assertEquals(
                fixture.claim, fixture.claims.lookup(fixture.claim.slot(), NONE).orElseThrow());
            assertEquals(
                List.of(List.of("CONCLUSIVELY_UNPUBLISHED")),
                rows(
                    fixture.sql,
                    "SELECT e.kind FROM metadata_repair_events e JOIN metadata_repair_attempts a ON"
                        + " a.attempt_id=e.attempt_id WHERE a.canonical_token=? ORDER BY"
                        + " e.sequence",
                    plan.reservationToken().toString()));
          }
          assertEquals(
              oldReferences, references(fixture.sql, oldBuild.snapshotId(), oldScope.scopeId()));
          assertThrows(
              ContentClaimException.class,
              () ->
                  fixture.claims.reserve(
                      plan.request().slot(),
                      alternate,
                      plan.request().requestedEncodedBytes(),
                      NONE));
          var next = fixture.claims.claimWorker(plan.reservationToken(), NONE);
          fixture.claims.finishWorker(next, ContentWorkerFinishOutcome.UNCERTAIN, NONE);
        } finally {
          release.countDown();
          workers.close();
        }
      }
    } finally {
      release.countDown();
      workers.close();
    }
  }

  private record References(
      List<List<String>> memberships, List<List<String>> scope, List<List<String>> requirements) {}

  private static References references(SqliteCatalog sql, String snapshot, String scope)
      throws Exception {
    return new References(
        rows(
            sql,
            "SELECT * FROM build_object_memberships WHERE snapshot_id=? ORDER BY role",
            snapshot),
        rows(sql, "SELECT * FROM retention_scopes WHERE id=?", scope),
        rows(
            sql,
            "SELECT * FROM retention_scope_requirements WHERE scope_id=? ORDER BY role",
            scope));
  }

  private static List<List<String>> rows(SqliteCatalog sql, String statement, String value)
      throws Exception {
    var result = new ArrayList<List<String>>();
    try (var connection = sql.connect();
        var query = connection.prepareStatement(statement)) {
      query.setString(1, value);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          var values = new ArrayList<String>();
          for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
            values.add(rows.getString(column));
          result.add(values);
        }
      }
    }
    return result;
  }

  private static long physicalObjects(Fixture fixture) throws Exception {
    try (var paths = Files.walk(fixture.path.resolve("store/objects"))) {
      return paths.filter(Files::isRegularFile).count();
    }
  }

  private static final class Workers implements Executor, AutoCloseable {
    final AtomicInteger submitted = new AtomicInteger();
    final CountDownLatch sourceRetired = new CountDownLatch(1);
    final ThreadPoolExecutor pool =
        new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));

    public void execute(Runnable work) {
      int ordinal = submitted.incrementAndGet();
      pool.execute(
          () -> {
            try {
              work.run();
            } finally {
              // This test submits one preparation, followed by exactly one shared actual flight.
              if (ordinal == 2) sourceRetired.countDown();
            }
          });
    }

    public void close() throws InterruptedException {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Actual test workers did not retire");
    }
  }
}
