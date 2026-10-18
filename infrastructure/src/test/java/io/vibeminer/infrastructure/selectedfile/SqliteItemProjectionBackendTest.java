package io.vibeminer.infrastructure.selectedfile;

import static io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalogTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.item.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteItemProjectionBackendTest {
  @TempDir Path directory;
  static final ItemProjectionLimits LIMITS =
      new ItemProjectionLimits(10000, 4096, 1000, 20, 4096, 0);

  class Fixture implements AutoCloseable {
    final SqliteCatalog catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    final FileObjectStore store = new FileObjectStore(directory.resolve("store"));
    final FileObjectStore.StoreSession session = store.contentSession();
    final Map<String, SelectedFileAssociation> associations = new HashMap<>();
    final AtomicInteger projects = new AtomicInteger(),
        opens = new AtomicInteger(),
        verifies = new AtomicInteger(),
        leases = new AtomicInteger();
    boolean lateFailure;
    Instant verificationTime = Instant.EPOCH;
    String decoder = "decoder-v1";
    ProjectionAction action;
    List<ItemProjectionSourceReader.CopyDeclaration> copyDeclarations =
        List.of(new ItemProjectionSourceReader.CopyDeclaration(99, 2));

    @FunctionalInterface
    interface ProjectionAction {
      PreparedItemProjection.Coverage run(
          ItemProjectionSourceReader.RowSink sink, CancellationToken token);
    }

    Fixture() throws Exception {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, session);
      var objects = new SqliteBuildObjectCatalog(catalog, store);
      var files = new SqliteSelectedFileCatalog(catalog, store, session);
      byte[] bytes = "fixture".getBytes();
      var reservation = files.reserve(slot(), A, bytes.length, NONE);
      var worker = files.claimWorker(reservation.token(), NONE);
      files.markPublishing(worker, NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      files.recordVerifiedCompletion(worker, proof, NONE);
      publish(store, bytes);
      files.completePublication(worker, proof, NONE);
      files.finishWorker(
          worker, io.vibeminer.domain.content.ContentWorkerFinishOutcome.RETAINED, NONE);
      for (var version : List.of("one", "two")) {
        var build = build(version);
        objects.registerSnapshot(build);
        var config = dependency(objects, store, build, "BUILD_CONFIG", "cfg");
        var root = dependency(objects, store, build, "ROOT_MANIFEST", "root");
        var association = association(build, config, root, proof);
        files.attachAssociation(association, scope(association), NONE);
        associations.put(version, association);
      }
    }

    final ItemProjectionSourceReader reader =
        new ItemProjectionSourceReader() {
          public OpenedProjection open(ItemProjectionRequest request, CancellationToken token) {
            opens.incrementAndGet();
            var association =
                associations.values().stream()
                    .filter(value -> value.role().equals(request.source().role()))
                    .findFirst()
                    .orElseThrow();
            var lease =
                store.openRead(association.representation().encoded().object().digest(), token);
            leases.incrementAndGet();
            return new OpenedProjection() {
              public ItemArtifactDefinition definition() {
                var base = definitionFor(association, request.locale());
                var i = base.interpretation();
                return new ItemArtifactDefinition(
                    base.sources(),
                    new ItemArtifactDefinition.Interpretation(
                        decoder,
                        i.indexRevision(),
                        i.tableHash(),
                        i.layoutHash(),
                        i.sectionOrdinal(),
                        i.sectionDecodedOffset(),
                        i.sectionDecodedBytes(),
                        i.schema(),
                        i.locale(),
                        i.optionsRevision(),
                        i.proofSemantics()));
              }

              public ItemProjectionEvidence evidence() {
                var value = evidenceFor(association);
                return new ItemProjectionEvidence(
                    value.association(),
                    new ItemProjectionEvidence.LocalVerification(
                        value.localVerification().object(),
                        verificationTime,
                        value.localVerification().verifierRevision()),
                    value.chunks(),
                    value.windows(),
                    value.fullDecoded());
              }

              public List<CopyDeclaration> copies() {
                return copyDeclarations;
              }

              public PreparedItemProjection.Coverage project(
                  RowSink sink, CancellationToken cancellation) {
                projects.incrementAndGet();
                if (action != null) return action.run(sink, cancellation);
                sink.accept(row(9, 0));
                sink.accept(row(2, 1));
                sink.accept(row(0xffffffffL, 2));
                if (lateFailure)
                  throw new ItemProjectionException(
                      ItemProjectionException.Code.CORRUPT, "late section failure");
                return new PreparedItemProjection.Coverage(0, 3, 1, 2);
              }

              public void close() {
                lease.close();
                leases.decrementAndGet();
              }
            };
          }

          public VerificationLease verify(PreparedItemProjection handle, CancellationToken token) {
            verifies.incrementAndGet();
            var lease =
                store.openRead(handle.evidence().localVerification().object().digest(), token);
            leases.incrementAndGet();
            return () -> {
              lease.close();
              leases.decrementAndGet();
            };
          }
        };

    ItemProjectionRequest request(String version) {
      return new ItemProjectionRequest(associations.get(version).descriptor(), "enUS", LIMITS);
    }

    public void close() {
      store.close();
    }
  }

  @Test
  void sharedArtifactHasOneRowSetAndDistinctConsumerContextWithStableNumericPages()
      throws Exception {
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var first = backend.prepare(fixture.request("one"), NONE);
      var second = backend.prepare(fixture.request("two"), NONE);
      assertEquals(first.artifactId(), second.artifactId());
      assertNotEquals(first.snapshotId(), second.snapshotId());
      assertEquals(1, fixture.projects.get());
      var page = backend.page(first, Optional.empty(), 2, NONE);
      assertEquals(List.of(2L, 9L), page.rows().stream().map(ItemPhysicalRow::recordId).toList());
      var last = backend.page(first, page.nextCursor(), 2, NONE);
      assertEquals(
          List.of(0xffffffffL), last.rows().stream().map(ItemPhysicalRow::recordId).toList());
      assertTrue(last.nextCursor().isEmpty());
      assertEquals(
          ItemLookupResult.Outcome.PHYSICAL_MATCH, backend.lookup(first, 9, NONE).outcome());
      assertEquals(
          ItemLookupResult.Outcome.COPY_UNINDEXED, backend.lookup(first, 99, NONE).outcome());
      assertEquals(
          ItemLookupResult.Outcome.NO_PHYSICAL_MATCH, backend.lookup(first, 100, NONE).outcome());
      assertEquals(0, fixture.leases.get());
      assertEquals(
          ItemProjectionException.Code.STALE_CURSOR,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.page(second, page.nextCursor(), 2, NONE))
              .code());
    }
  }

  @Test
  void lateSourceFailureNeverPublishesPartialRowsOrContext() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.lateFailure = true;
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT count(*) FROM item_projection_artifacts")) {
        assertEquals(0, rows.getInt(1));
      }
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void stagingBatchesStayInvisibleAndLateFailureDiscardsEveryCommittedBatch() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.action =
          (sink, token) -> {
            for (int index = 0; index < 129; index++) sink.accept(row(1000 + index, index));
            try (var connection = fixture.catalog.connect();
                var rows =
                    connection
                        .createStatement()
                        .executeQuery(
                            "SELECT (SELECT count(*) FROM item_projection_rows),(SELECT count(*)"
                                + " FROM item_projection_contexts),status FROM"
                                + " item_projection_artifacts")) {
              assertEquals(128, rows.getInt(1));
              assertEquals(0, rows.getInt(2));
              assertEquals("STAGING", rows.getString(3));
            } catch (java.sql.SQLException failure) {
              throw new AssertionError(failure);
            }
            throw new ItemProjectionException(
                ItemProjectionException.Code.CORRUPT, "late after staged batch");
          };
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertCounts(fixture, 0, 0, 0);
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void duplicatePhysicalIdsCopyOverlapAndCancelledProjectionNeverBecomeReady() throws Exception {
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      fixture.action =
          (sink, token) -> {
            sink.accept(row(7, 0));
            sink.accept(row(7, 1));
            return new PreparedItemProjection.Coverage(0, 2, 1, 0);
          };
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertCounts(fixture, 0, 0, 0);
      fixture.action =
          (sink, token) -> {
            sink.accept(row(99, 0));
            return new PreparedItemProjection.Coverage(0, 1, 1, 0);
          };
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertCounts(fixture, 0, 0, 0);
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      fixture.action =
          (sink, token) -> {
            sink.accept(row(7, 0));
            cancelled.set(true);
            return new PreparedItemProjection.Coverage(0, 1, 1, 0);
          };
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> backend.prepare(fixture.request("one"), cancelled::get));
      assertCounts(fixture, 0, 0, 0);
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void activeGenerationRemainsBusyAfterCancellationUntilActualProjectionRetires() throws Exception {
    try (var fixture = new Fixture();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var entered = new java.util.concurrent.CountDownLatch(1);
      var exit = new java.util.concurrent.CountDownLatch(1);
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      fixture.action =
          (sink, token) -> {
            entered.countDown();
            try {
              exit.await();
            } catch (InterruptedException failure) {
              throw new AssertionError(failure);
            }
            token.throwIfCancellationRequested();
            sink.accept(row(7, 0));
            return new PreparedItemProjection.Coverage(0, 1, 1, 0);
          };
      var first = new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var second =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var future = executor.submit(() -> first.prepare(fixture.request("one"), cancelled::get));
      try {
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        cancelled.set(true);
        assertEquals(
            ItemProjectionException.Code.BUSY,
            assertThrows(
                    ItemProjectionException.class,
                    () -> second.prepare(fixture.request("one"), NONE))
                .code());
        assertFalse(future.isDone());
        var association = fixture.associations.get("one");
        var provisional =
            new PreparedItemProjection(
                definitionFor(association, "enUS"),
                evidenceFor(association),
                new PreparedItemProjection.Coverage(0, 1, 1, 0));
        assertEquals(
            ItemProjectionException.Code.NOT_READY,
            assertThrows(
                    ItemProjectionException.class,
                    () -> second.page(provisional, Optional.empty(), 2, NONE))
                .code());
      } finally {
        exit.countDown();
      }
      assertInstanceOf(
          java.util.concurrent.CancellationException.class,
          assertThrows(java.util.concurrent.ExecutionException.class, future::get).getCause());
      assertCounts(fixture, 0, 0, 0);
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void generationFenceBlocksStalePublicationAndNewEpochReclaimsOnlyOnExplicitPrepare()
      throws Exception {
    String artifact, generation = UUID.randomUUID().toString();
    try (var fixture = new Fixture()) {
      artifact = definitionFor(fixture.associations.get("one"), "enUS").artifactId();
      fixture.action =
          (sink, token) -> {
            try (var connection = fixture.catalog.connect()) {
              connection.setAutoCommit(false);
              try (var delete =
                  connection.prepareStatement(
                      "DELETE FROM item_projection_copies WHERE artifact_id=?")) {
                delete.setString(1, artifact);
                delete.executeUpdate();
              }
              try (var update =
                  connection.prepareStatement(
                      "UPDATE item_projection_artifacts SET generation=? WHERE artifact_id=?")) {
                update.setString(1, generation);
                update.setString(2, artifact);
                update.executeUpdate();
              }
              connection.commit();
            } catch (java.sql.SQLException failure) {
              throw new AssertionError(failure);
            }
            sink.accept(row(7, 0));
            return new PreparedItemProjection.Coverage(0, 1, 1, 0);
          };
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      assertEquals(
          ItemProjectionException.Code.BUSY,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertCounts(fixture, 1, 0, 0);
      assertEquals(
          ItemProjectionException.Code.BUSY,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
    }
    try (var fixture = new Fixture()) {
      assertCounts(fixture, 1, 0, 0);
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT generation,status FROM item_projection_artifacts")) {
        assertEquals(generation, rows.getString(1));
        assertEquals("STAGING", rows.getString(2));
      }
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var handle = backend.prepare(fixture.request("one"), NONE);
      assertEquals(artifact, handle.artifactId());
      assertCounts(fixture, 1, 3, 1);
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT generation FROM item_projection_artifacts")) {
        assertNotEquals(generation, rows.getString(1));
      }
    }
  }

  @Test
  void readyRestartPreservesFirstEvidenceAndChangedInterpretationGetsDifferentArtifact()
      throws Exception {
    PreparedItemProjection first;
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      first = backend.prepare(fixture.request("one"), NONE);
      fixture.verificationTime = Instant.ofEpochSecond(100);
      assertEquals(first, backend.prepare(fixture.request("one"), NONE));
      assertEquals(1, fixture.projects.get());
    }
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      assertEquals(first, backend.prepare(fixture.request("one"), NONE));
      assertEquals(0, fixture.projects.get());
      assertEquals(3, backend.page(first, Optional.empty(), 10, NONE).rows().size());
      fixture.decoder = "decoder-v2";
      var changed = backend.prepare(fixture.request("one"), NONE);
      assertNotEquals(first.artifactId(), changed.artifactId());
      assertCounts(fixture, 2, 6, 2);
    }
  }

  @Test
  void corruptDerivedRowsAndMissingPhysicalSourceFailBeforeResultsWithoutLeakingLeases()
      throws Exception {
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var handle = backend.prepare(fixture.request("one"), NONE);
      var changedValues = new ArrayList<>(row(9, 0).values());
      changedValues.set(
          0,
          new ItemPhysicalRow.IntegerCandidate(
              ItemArtifactDefinition.Field.CLASS_ID,
              8,
              32,
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              8,
              32,
              ItemArtifactDefinition.Sign.UNSIGNED));
      var changed =
          io.vibeminer.infrastructure.item.ItemProjectionRecords.row(
              new ItemPhysicalRow(9, 0, 0, changedValues));
      try (var connection = fixture.catalog.connect();
          var update =
              connection.prepareStatement(
                  "UPDATE item_projection_rows SET row=? WHERE record_id=9")) {
        update.setBytes(1, changed);
        update.executeUpdate();
      }
      int before = fixture.verifies.get();
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(ItemProjectionException.class, () -> backend.lookup(handle, 9, NONE))
              .code());
      assertEquals(before + 1, fixture.verifies.get());
      assertEquals(0, fixture.leases.get());
      var digest = handle.evidence().localVerification().object().digest().value();
      java.nio.file.Files.delete(
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2)));
      assertEquals(
          ItemProjectionException.Code.MISSING_LOCAL,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.page(handle, Optional.empty(), 2, NONE))
              .code());
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void finalReadyFailureRollsBackContextAndAllStagingButPreservesRetainedInputs() throws Exception {
    try (var fixture = new Fixture()) {
      try (var connection = fixture.catalog.connect();
          var statement = connection.createStatement()) {
        statement.execute(
            "CREATE TRIGGER fail_item_ready BEFORE UPDATE OF status ON item_projection_artifacts"
                + " WHEN NEW.status='READY' BEGIN SELECT RAISE(ABORT,'late ready failure'); END");
      }
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertCounts(fixture, 0, 0, 0);
      assertEquals(0, fixture.leases.get());
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT (SELECT count(*) FROM build_object_memberships),(SELECT count(*) FROM"
                          + " retention_scopes WHERE pinned=1)")) {
        assertEquals(6, rows.getInt(1));
        assertEquals(2, rows.getInt(2));
      }
    }
  }

  @Test
  void schemaSevenUpgradeAddsOnlyDerivedTablesAndPreservesCanonicalWorkerProofAndPins()
      throws Exception {
    try (var fixture = new Fixture()) {
      var files = new SqliteSelectedFileCatalog(fixture.catalog, fixture.store, fixture.session);
      var claim = files.lookup(slot(), NONE).orElseThrow();
      var worker = files.claimWorker(claim.token(), NONE);
      byte[] original;
      String owner;
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT representation,owner_session FROM canonical_content_slots")) {
        original = rows.getBytes(1);
        owner = rows.getString(2);
      }
      try (var connection = fixture.catalog.connect();
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE item_projection_contexts");
        statement.execute("DROP TABLE item_projection_rows");
        statement.execute("DROP TABLE item_projection_copies");
        statement.execute("DROP TABLE item_projection_artifacts");
        statement.execute("PRAGMA user_version=7");
      }
      var upgraded = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      try (var connection = upgraded.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT token,owner_session,status,worker_nonce,representation FROM"
                          + " canonical_content_slots")) {
        assertEquals(claim.token().toString(), rows.getString(1));
        assertEquals(owner, rows.getString(2));
        assertEquals("RETAINED", rows.getString(3));
        assertEquals(worker.workerNonce().toString(), rows.getString(4));
        assertArrayEquals(original, rows.getBytes(5));
      }
      try (var connection = upgraded.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT (SELECT count(*) FROM retention_scopes WHERE pinned=1),(SELECT"
                          + " count(*) FROM build_object_memberships),(SELECT count(*) FROM"
                          + " canonical_legacy_blocks),(SELECT count(*) FROM"
                          + " item_projection_artifacts)")) {
        assertEquals(2, rows.getInt(1));
        assertEquals(6, rows.getInt(2));
        assertEquals(0, rows.getInt(3));
        assertEquals(0, rows.getInt(4));
      }
      files.finishWorker(
          worker, io.vibeminer.domain.content.ContentWorkerFinishOutcome.RETAINED, NONE);
    }
  }

  @Test
  void dependencyRemovalAfterSourceVerificationBlocksPublicationAndLaterQueries() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.action =
          (sink, token) -> {
            sink.accept(row(7, 0));
            removeConfiguration(fixture);
            return new PreparedItemProjection.Coverage(0, 1, 1, 0);
          };
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      assertEquals(
          ItemProjectionException.Code.MISSING_LOCAL,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertCounts(fixture, 0, 0, 0);
      assertEquals(0, fixture.leases.get());
    }
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var handle = backend.prepare(fixture.request("one"), NONE);
      removeConfiguration(fixture);
      assertEquals(
          ItemProjectionException.Code.MISSING_LOCAL,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.page(handle, Optional.empty(), 2, NONE))
              .code());
      assertEquals(0, fixture.leases.get());
    }
  }

  static void removeConfiguration(Fixture fixture) {
    try (var connection = fixture.catalog.connect();
        var query =
            connection.prepareStatement(
                "DELETE FROM build_object_memberships WHERE snapshot_id=? AND"
                    + " role='BUILD_CONFIG'")) {
      query.setString(1, fixture.associations.get("one").snapshotId());
      query.executeUpdate();
    } catch (java.sql.SQLException failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  void editedCopySourceCannotInventAnAuthenticatedCopyDeclaration() throws Exception {
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var handle = backend.prepare(fixture.request("one"), NONE);
      assertEquals(2, backend.lookup(handle, 99, NONE).copySourceId().orElseThrow());
      for (var mutation :
          List.of(
              "UPDATE item_projection_copies SET source_id=9 WHERE target_id=99",
              "UPDATE item_projection_copies SET target_id=100 WHERE target_id=99",
              "UPDATE item_projection_rows SET record_id=8 WHERE record_id=9",
              "UPDATE item_projection_rows SET physical_ordinal=99 WHERE record_id=9")) {
        assertThrows(java.sql.SQLException.class, () -> executeMutation(fixture, mutation));
      }
      assertEquals(2, backend.lookup(handle, 99, NONE).copySourceId().orElseThrow());
      assertEquals(9, backend.lookup(handle, 9, NONE).row().orElseThrow().recordId());
      executeMutation(
          fixture,
          "UPDATE item_projection_copies SET copy_sha256='"
              + "0".repeat(64)
              + "' WHERE target_id=99");
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertEquals(1, fixture.projects.get());
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(ItemProjectionException.class, () -> backend.lookup(handle, 99, NONE))
              .code());
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void schemaEightCopyFactsStayUnprovenUntilExplicitVerifiedPrepareRebuildsDerivedOnly()
      throws Exception {
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var first = backend.prepare(fixture.request("one"), NONE);
      backend.prepare(fixture.request("two"), NONE);
      String oldGeneration;
      byte[] originalCanonical;
      var files = new SqliteSelectedFileCatalog(fixture.catalog, fixture.store, fixture.session);
      var claim = files.lookup(slot(), NONE).orElseThrow();
      var worker = files.claimWorker(claim.token(), NONE);
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT generation FROM item_projection_artifacts")) {
        oldGeneration = rows.getString(1);
      }
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT representation FROM canonical_content_slots")) {
        originalCanonical = rows.getBytes(1);
      }
      downgradeToEight(fixture, true);
      var migrated = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      try (var connection = migrated.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT copy_record,copy_sha256 FROM item_projection_copies")) {
        assertNull(rows.getObject(1));
        assertNull(rows.getObject(2));
      }
      try (var connection = migrated.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT worker_nonce,representation FROM canonical_content_slots")) {
        assertEquals(worker.workerNonce().toString(), rows.getString(1));
        assertArrayEquals(originalCanonical, rows.getBytes(2));
      }
      assertCounts(fixture, 1, 3, 2);
      backend = new SqliteItemProjectionBackend(migrated, fixture.session, fixture.reader);
      var current = backend;
      assertEquals(
          ItemProjectionException.Code.NOT_READY,
          assertThrows(
                  ItemProjectionException.class,
                  () -> current.page(first, Optional.empty(), 2, NONE))
              .code());
      assertEquals(
          ItemProjectionException.Code.NOT_READY,
          assertThrows(ItemProjectionException.class, () -> current.lookup(first, 99, NONE))
              .code());
      assertEquals(1, fixture.projects.get());
      assertCounts(fixture, 1, 3, 2);
      var rebuilt = backend.prepare(fixture.request("one"), NONE);
      assertEquals(first.artifactId(), rebuilt.artifactId());
      assertEquals(2, fixture.projects.get());
      assertCounts(fixture, 1, 3, 1);
      assertEquals(2, backend.lookup(rebuilt, 99, NONE).copySourceId().orElseThrow());
      try (var connection = migrated.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT generation,copy_proof_version FROM item_projection_artifacts")) {
        assertNotEquals(oldGeneration, rows.getString(1));
        assertEquals(1, rows.getInt(2));
      }
      try (var connection = migrated.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery(
                      "SELECT (SELECT count(*) FROM retention_scopes WHERE pinned=1),(SELECT"
                          + " count(*) FROM build_object_memberships)")) {
        assertEquals(2, rows.getInt(1));
        assertEquals(6, rows.getInt(2));
      }
      files.finishWorker(
          worker, io.vibeminer.domain.content.ContentWorkerFinishOutcome.RETAINED, NONE);
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void schemaEightZeroCopyReadyArtifactRemainsUsableWithoutRebuilding() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.copyDeclarations = List.of();
      fixture.action =
          (sink, token) -> {
            sink.accept(row(7, 0));
            return new PreparedItemProjection.Coverage(0, 1, 0, 0);
          };
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var handle = backend.prepare(fixture.request("one"), NONE);
      downgradeToEight(fixture);
      var migrated = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      try (var ignored = migrated.connect()) {}
      backend = new SqliteItemProjectionBackend(migrated, fixture.session, fixture.reader);
      assertEquals(1, backend.page(handle, Optional.empty(), 2, NONE).rows().size());
      assertEquals(handle, backend.prepare(fixture.request("one"), NONE));
      assertEquals(1, fixture.projects.get());
    }
  }

  static void downgradeToEight(Fixture fixture) throws Exception {
    downgradeToEight(fixture, false);
  }

  static void downgradeToEight(Fixture fixture, boolean corruptOldSource) throws Exception {
    try (var connection = fixture.catalog.connect();
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE item_projection_copies DROP COLUMN copy_sha256");
      statement.execute("ALTER TABLE item_projection_copies DROP COLUMN copy_record");
      statement.execute("ALTER TABLE item_projection_artifacts DROP COLUMN copy_proof_version");
      statement.execute("DROP TRIGGER item_projection_rows_identity_insert");
      statement.execute("DROP TRIGGER item_projection_rows_identity_update");
      if (corruptOldSource)
        statement.execute("UPDATE item_projection_copies SET source_id=9 WHERE target_id=99");
      statement.execute("PRAGMA user_version=8");
    }
  }

  @Test
  void invalidExactItemIdsFailBeforeLocalSourceIo() throws Exception {
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var handle = backend.prepare(fixture.request("one"), NONE);
      for (long id : new long[] {-1, 0, 0x100000000L})
        assertEquals(
            ItemProjectionException.Code.INVALID_REQUEST,
            assertThrows(ItemProjectionException.class, () -> backend.lookup(handle, id, NONE))
                .code());
      assertEquals(0, fixture.verifies.get());
      assertEquals(0, fixture.leases.get());
    }
  }

  @Test
  void currentMissingOrDamagedCopyProofIsCorruptionAndNeverTriggersAutomaticRebuild()
      throws Exception {
    try (var fixture = new Fixture()) {
      var backend =
          new SqliteItemProjectionBackend(fixture.catalog, fixture.session, fixture.reader);
      var handle = backend.prepare(fixture.request("one"), NONE);
      byte[] record;
      try (var connection = fixture.catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT copy_record FROM item_projection_copies")) {
        record = rows.getBytes(1);
      }
      try (var connection = fixture.catalog.connect();
          var statement = connection.createStatement()) {
        statement.execute("UPDATE item_projection_copies SET copy_record=NULL,copy_sha256=NULL");
      }
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(ItemProjectionException.class, () -> backend.lookup(handle, 99, NONE))
              .code());
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertEquals(1, fixture.projects.get());
      var sha = "0".repeat(64);
      try (var connection = fixture.catalog.connect();
          var query =
              connection.prepareStatement(
                  "UPDATE item_projection_copies SET copy_record=?,copy_sha256=?")) {
        query.setBytes(1, record);
        query.setString(2, sha);
        query.executeUpdate();
      }
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(ItemProjectionException.class, () -> backend.lookup(handle, 99, NONE))
              .code());
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> backend.prepare(fixture.request("one"), NONE))
              .code());
      assertEquals(1, fixture.projects.get());
      assertEquals(0, fixture.leases.get());
    }
  }

  static void executeMutation(Fixture fixture, String sql) throws java.sql.SQLException {
    try (var connection = fixture.catalog.connect();
        var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  static void assertCounts(Fixture fixture, int artifacts, int rows, int contexts)
      throws Exception {
    try (var connection = fixture.catalog.connect();
        var result =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT (SELECT count(*) FROM item_projection_artifacts),(SELECT count(*) FROM"
                        + " item_projection_rows),(SELECT count(*) FROM"
                        + " item_projection_contexts)")) {
      assertEquals(artifacts, result.getInt(1));
      assertEquals(rows, result.getInt(2));
      assertEquals(contexts, result.getInt(3));
    }
  }

  static ItemArtifactDefinition definitionFor(SelectedFileAssociation association, String locale) {
    var fields = new ArrayList<ItemArtifactDefinition.FieldBinding>();
    for (var field : ItemArtifactDefinition.Field.values())
      fields.add(
          new ItemArtifactDefinition.FieldBinding(
              field, field.ordinal(), 32, ItemArtifactDefinition.Sign.UNSIGNED));
    var schema =
        new ItemArtifactDefinition.Schema(
            "fixture",
            "revision",
            "definitions/Item.dbd",
            "a".repeat(64),
            "b".repeat(64),
            "CC BY-SA 4.0",
            "contributors",
            fields);
    return new ItemArtifactDefinition(
        List.of(association.representation().encoded().object()),
        new ItemArtifactDefinition.Interpretation(
            "decoder-v1", "index-v1", 1, 2, 0, 0, 64, schema, locale, "physical-v1", "window-v1"));
  }

  static ItemProjectionEvidence evidenceFor(SelectedFileAssociation association) {
    var object = association.representation().encoded().object();
    return new ItemProjectionEvidence(
        association,
        new ItemProjectionEvidence.LocalVerification(object, Instant.EPOCH, "sha256-v1"),
        List.of(
            new ItemProjectionEvidence.ChunkAttestation(
                0, 0, object.length(), "a".repeat(32), 0, 64, "chunk-v1")),
        List.of(new ItemProjectionEvidence.DecodedWindow(0, 64)),
        Optional.empty());
  }

  static ItemPhysicalRow row(long id, int ordinal) {
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var field : ItemArtifactDefinition.Field.values())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              field,
              field.ordinal() + 1,
              32,
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              field.ordinal() + 1,
              32,
              ItemArtifactDefinition.Sign.UNSIGNED));
    return new ItemPhysicalRow(id, ordinal, 0, values);
  }
}
