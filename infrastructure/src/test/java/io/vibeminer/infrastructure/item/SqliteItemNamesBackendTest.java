package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader.CopyDeclaration;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SelectedFileRecords;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteItemNamesBackendTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  static SparseNameLimits limits() {
    return new SparseNameLimits(
        1000000, 10000, 4096, 1000000, 4000000, 10000, 10000, 100, 10, 10000, 1000, 1000, 1000,
        10000, 4000000, 1);
  }

  class Fixture implements AutoCloseable {
    final FileObjectStore store = new FileObjectStore(directory.resolve("store"));
    final SqliteCatalog catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    final SqliteSparseNameRepository repo;

    Fixture() throws Exception {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      repo = new SqliteSparseNameRepository(catalog, store.contentSession());
      seed(SparseNameStorageFixture.prepared());
      seedAssociation(SparseNameStorageFixture.association(false, Instant.EPOCH));
      prepareItem();
    }

    void seed(PreparedSparseNames handle) throws Exception {
      seedAssociation(handle.evidence().association());
    }

    void seedAssociation(io.vibeminer.domain.selectedfile.SelectedFileAssociation a)
        throws Exception {
      var build = a.descriptor().association().build();
      try (var c = catalog.connect()) {
        c.createStatement().execute("PRAGMA foreign_keys=OFF");
        c.setAutoCommit(false);
        catalog.ensureSnapshot(c, build);
        for (var entry :
            Map.of(
                    "BUILD_CONFIG",
                    a.configuration(),
                    "ROOT_MANIFEST",
                    a.root(),
                    a.role(),
                    a.representation().encoded())
                .entrySet()) {
          var alias = entry.getValue();
          var s = alias.source();
          try (var q = c.prepareStatement("INSERT OR IGNORE INTO catalog_objects VALUES(?,?)")) {
            q.setString(1, alias.object().digest().value());
            q.setLong(2, alias.object().length());
            q.executeUpdate();
          }
          try (var q =
              c.prepareStatement("INSERT OR IGNORE INTO source_aliases VALUES(?,?,?,?,?,?)")) {
            q.setString(1, s.namespace());
            q.setString(2, s.immutableSourceKey());
            q.setString(3, s.verificationSemantics());
            q.setString(4, alias.object().digest().value());
            q.setString(5, alias.evidence().sourceUrl());
            q.setString(6, alias.evidence().verifiedAt().toString());
            q.executeUpdate();
          }
          try (var q =
              c.prepareStatement(
                  "INSERT OR IGNORE INTO build_object_memberships VALUES(?,?,?,?,?,?,?)")) {
            q.setString(1, build.snapshotId());
            q.setString(2, entry.getKey());
            q.setString(3, s.namespace());
            q.setString(4, s.immutableSourceKey());
            q.setString(5, s.verificationSemantics());
            q.setString(6, alias.evidence().sourceUrl());
            q.setString(7, alias.evidence().verifiedAt().toString());
            q.executeUpdate();
          }
        }
        try (var q =
            c.prepareStatement(
                "INSERT OR IGNORE INTO selected_file_associations VALUES(?,?,?,?,?)")) {
          q.setString(1, build.snapshotId());
          q.setString(2, a.role());
          q.setString(3, a.descriptor().association().slot().namespace());
          q.setString(4, a.descriptor().association().slot().contentKey());
          q.setBytes(5, SelectedFileRecords.association(a));
          q.executeUpdate();
        }
        c.commit();
      }
    }

    PreparedItemProjection item;
    int itemLeases, sparseLeases, projects, verifies, itemVerifies;
    Runnable beforeComplete = () -> {}, beforeVerify = () -> {};
    RuntimeException itemFailure;
    boolean lateFailure, cancelProjection, missingItem, missingSparse, wrongFinalCoverage;
    final List<DisplayCandidate> names =
        new ArrayList<>(
            List.of(
                SparseNameStorageFixture.candidate(),
                new DisplayCandidate("", 202, 1, 0, 120, 5, 5),
                new DisplayCandidate("orphan", 505, 2, 0, 125, 11, 11)));

    void prepareItem() {
      var a = SparseNameStorageFixture.association(false, Instant.EPOCH);
      var fields =
          List.of(
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.CLASS_ID, 0, 32, ItemArtifactDefinition.Sign.SIGNED),
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.SUBCLASS_ID,
                  1,
                  8,
                  ItemArtifactDefinition.Sign.UNSIGNED),
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.INVENTORY_TYPE,
                  3,
                  8,
                  ItemArtifactDefinition.Sign.SIGNED),
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.ICON_FILE_DATA_ID,
                  6,
                  32,
                  ItemArtifactDefinition.Sign.SIGNED));
      var schema =
          new ItemArtifactDefinition.Schema(
              "WoWDBDefs",
              "b9a63096cdd99b26967f17f9515377ae62cadfd7",
              "definitions/Item.dbd",
              "076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120",
              "7".repeat(64),
              "CC BY-SA 4.0",
              "contributors",
              fields);
      var definition =
          new ItemArtifactDefinition(
              List.of(a.representation().encoded().object()),
              new ItemArtifactDefinition.Interpretation(
                  "item-v1",
                  "item-index-v1",
                  0x50238ec2L,
                  0x996192aaL,
                  0,
                  0,
                  100,
                  schema,
                  "enUS",
                  "physical-only",
                  "window-v1"));
      var evidence =
          new ItemProjectionEvidence(
              a,
              new ItemProjectionEvidence.LocalVerification(
                  a.representation().encoded().object(), Instant.EPOCH, "sha256-v1"),
              List.of(
                  new ItemProjectionEvidence.ChunkAttestation(
                      0, 36, 738308, "6".repeat(32), 0, 3000000, "chunk-v1")),
              List.of(new ItemProjectionEvidence.DecodedWindow(0, 100)),
              Optional.empty());
      var repository = new SqliteItemProjectionRepository(catalog, store.contentSession());
      var header = repository.claim(definition, NONE);
      var stage =
          new SqliteItemProjectionRepository.Stage(definition.artifactId(), header.generation());
      var rows = new ArrayList<ItemPhysicalRow>();
      int ordinal = 0;
      for (long id : new long[] {101, 202, 303, 404, 0xffffffffL}) {
        var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
        for (var binding : fields)
          values.add(
              new ItemPhysicalRow.IntegerCandidate(
                  binding.field(),
                  1,
                  32,
                  ItemPhysicalRow.PhysicalInterpretation.SIGNED_TWOS_COMPLEMENT,
                  1,
                  binding.candidateBits(),
                  binding.sign()));
        rows.add(new ItemPhysicalRow(id, ordinal++, 0, values));
      }
      repository.rows(stage, rows, NONE);
      repository.copies(
          stage, List.of(new ItemProjectionSourceReader.CopyDeclaration(999, 101)), NONE);
      item =
          repository.publish(
              stage, definition, evidence, new PreparedItemProjection.Coverage(0, 5, 1, 48), NONE);
    }

    ItemProjectionSourceReader items() {
      return new ItemProjectionSourceReader() {
        public OpenedProjection open(
            io.vibeminer.application.item.ItemProjectionRequest r, CancellationToken t) {
          throw new AssertionError("No implicit Item preparation");
        }

        public VerificationLease verify(PreparedItemProjection h, CancellationToken t) {
          if (itemFailure != null) throw itemFailure;
          if (missingItem)
            throw new io.vibeminer.application.item.ItemProjectionException(
                io.vibeminer.application.item.ItemProjectionException.Code.MISSING_LOCAL,
                "Missing Item");
          assertEquals(item, h);
          itemVerifies++;
          itemLeases++;
          return () -> itemLeases--;
        }
      };
    }

    io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader sparse() {
      return new io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader() {
        public OpenedNames open(
            io.vibeminer.application.itemnames.ItemNamesRequest r, CancellationToken t) {
          assertEquals(1, itemLeases);
          sparseLeases++;
          return new OpenedNames() {
            public SparseNameArtifactDefinition definition() {
              return SparseNameStorageFixture.definition();
            }

            public List<CopyDeclaration> copies() {
              return List.of(new CopyDeclaration(303, 101));
            }

            public PreparedSparseNames project(RowSink sink, CancellationToken token) {
              projects++;
              for (var row : names) {
                sink.accept(row);
                if (lateFailure)
                  throw new io.vibeminer.application.itemnames.ItemNamesException(
                      io.vibeminer.application.itemnames.ItemNamesException.Code.CORRUPT,
                      "Late malformed record");
                if (cancelProjection) throw new java.util.concurrent.CancellationException();
              }
              beforeComplete.run();
              var base = SparseNameStorageFixture.prepared();
              return new PreparedSparseNames(
                  base.definition(),
                  base.evidence(),
                  new PreparedSparseNames.Coverage(0, wrongFinalCoverage ? 4 : 3, 1, 48, 9));
            }

            public void close() {
              sparseLeases--;
            }
          };
        }

        public VerificationLease verify(PreparedSparseNames h, CancellationToken t) {
          if (missingSparse)
            throw new io.vibeminer.application.itemnames.ItemNamesException(
                io.vibeminer.application.itemnames.ItemNamesException.Code.MISSING_LOCAL,
                "Missing Sparse");
          assertEquals(1, itemLeases);
          verifies++;
          sparseLeases++;
          try {
            beforeVerify.run();
          } catch (RuntimeException | Error failure) {
            sparseLeases--;
            throw failure;
          }
          return () -> sparseLeases--;
        }
      };
    }

    SqliteItemNamesBackend backend() {
      return new SqliteItemNamesBackend(catalog, store.contentSession(), items(), sparse());
    }

    io.vibeminer.application.itemnames.ItemNamesRequest request() {
      return new io.vibeminer.application.itemnames.ItemNamesRequest(
          item,
          SparseNameStorageFixture.prepared().evidence().association().descriptor(),
          "enUS",
          limits());
    }

    long count(String table) throws Exception {
      try (var c = catalog.connect();
          var q = c.createStatement();
          var r = q.executeQuery("SELECT count(*) FROM " + table)) {
        return r.getLong(1);
      }
    }

    public void close() {
      store.close();
    }
  }

  @Test
  void explicitPairUsesExistingItemRowsAndPhysicalEmptyCopyMissingNames() throws Exception {
    try (var f = new Fixture()) {
      var backend = f.backend();
      var pair = backend.prepare(f.request(), NONE);
      assertEquals(f.item, pair.item());
      var first = backend.page(pair, Optional.empty(), 2, NONE);
      assertEquals(
          List.of(101L, 202L), first.rows().stream().map(r -> r.item().recordId()).toList());
      assertEquals("αZ", first.rows().get(0).name().candidate().orElseThrow().value());
      assertEquals("", first.rows().get(1).name().candidate().orElseThrow().value());
      var next = backend.page(pair, first.nextCursor(), 2, NONE);
      assertEquals(ItemNameMatch.Outcome.COPY_UNINDEXED, next.rows().get(0).name().outcome());
      assertEquals(ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, next.rows().get(1).name().outcome());
      assertEquals(
          0xffffffffL,
          backend.page(pair, next.nextCursor(), 2, NONE).rows().getFirst().item().recordId());
      assertEquals(1, f.projects);
      assertEquals(5, f.count("item_projection_rows"));
      assertEquals(3, f.count("sparse_name_rows"));
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
      assertEquals(
          ItemLookupResult.Outcome.COPY_UNINDEXED,
          backend.lookup(pair, 999, NONE).result().item().outcome());
      assertTrue(backend.lookup(pair, 999, NONE).result().name().isEmpty());
      assertEquals(
          ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
          backend.lookup(pair, 505, NONE).result().item().outcome());
    }
  }

  @Test
  void lateProjectionFailureAndCancellationDiscardOnlyUnreadyGeneration() throws Exception {
    try (var f = new Fixture()) {
      f.lateFailure = true;
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.backend().prepare(f.request(), NONE))
              .code());
      assertEquals(0, f.count("sparse_name_artifacts"));
      assertEquals(0, f.count("sparse_name_rows"));
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
      f.lateFailure = false;
      f.cancelProjection = true;
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> f.backend().prepare(f.request(), NONE));
      assertEquals(0, f.count("sparse_name_artifacts"));
      assertEquals(5, f.count("item_projection_rows"));
    }
  }

  @Test
  void repeatedPrepareReusesRowsAndPreservesOriginalItemProof() throws Exception {
    try (var f = new Fixture()) {
      var backend = f.backend();
      var first = backend.prepare(f.request(), NONE);
      var second = backend.prepare(f.request(), NONE);
      assertEquals(first, second);
      assertSame(f.item, second.item());
      assertEquals(1, f.count("sparse_name_artifacts"));
      assertEquals(3, f.count("sparse_name_rows"));
      assertEquals(5, f.count("item_projection_rows"));
      assertEquals(2, f.projects);
      int projects = f.projects;
      backend.lookup(second, 101, NONE);
      backend.page(second, Optional.empty(), 500, NONE);
      assertEquals(projects, f.projects);
    }
  }

  @Test
  void invalidIdsAndCursorAndForgedManifestFailBeforeSourceVerification() throws Exception {
    try (var f = new Fixture()) {
      var backend = f.backend();
      var pair = backend.prepare(f.request(), NONE);
      int before = f.itemVerifies;
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          assertThrows(ItemNamesException.class, () -> backend.lookup(pair, 0, NONE)).code());
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          assertThrows(
                  ItemNamesException.class, () -> backend.page(pair, Optional.empty(), 501, NONE))
              .code());
      var cursor =
          new ItemNamesCursor(
              pair.item().artifactId(),
              pair.names().artifactId(),
              pair.snapshotId(),
              pair.item().sourceRole(),
              pair.names().sourceRole(),
              pair.locale(),
              ItemNamesCursor.QUERY_REVISION,
              ItemNamesCursor.OPTIONS_REVISION,
              101,
              1);
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR,
          assertThrows(
                  ItemNamesException.class, () -> backend.page(pair, Optional.of(cursor), 2, NONE))
              .code());
      var e = pair.names().evidence();
      var changed =
          new ItemProjectionEvidence(
              e.association(),
              e.localVerification(),
              e.chunks(),
              List.of(new ItemProjectionEvidence.DecodedWindow(0, 1300)),
              e.fullDecoded());
      var forged =
          new PreparedItemNames(
              pair.item(),
              new PreparedSparseNames(pair.names().definition(), changed, pair.names().coverage()));
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class, () -> backend.page(forged, Optional.empty(), 2, NONE))
              .code());
      assertEquals(before, f.itemVerifies);
      assertEquals(0, f.verifies);
    }
  }

  @Test
  void eitherMissingSourceRemainsTypedAndLeasesRetire() throws Exception {
    try (var f = new Fixture()) {
      var backend = f.backend();
      var pair = backend.prepare(f.request(), NONE);
      f.missingItem = true;
      assertEquals(
          ItemNamesException.Code.MISSING_LOCAL,
          assertThrows(ItemNamesException.class, () -> backend.lookup(pair, 101, NONE)).code());
      f.missingItem = false;
      f.missingSparse = true;
      assertEquals(
          ItemNamesException.Code.MISSING_LOCAL,
          assertThrows(ItemNamesException.class, () -> backend.lookup(pair, 101, NONE)).code());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void completedSourceCannotPublishAfterItemDependencyChanges() throws Exception {
    try (var f = new Fixture()) {
      f.beforeComplete =
          () -> {
            assertEquals(1, f.itemLeases);
            assertEquals(1, f.sparseLeases);
            mutate(
                f,
                "UPDATE source_aliases SET verified_at='1970-01-01T00:00:01Z' WHERE"
                    + " namespace='root'");
          };
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.backend().prepare(f.request(), NONE))
              .code());
      assertEquals(0, f.count("sparse_name_artifacts"));
      assertEquals(0, f.count("sparse_name_contexts"));
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void bothSourceLeasesSpanFinalSqlDependencyCheck() throws Exception {
    try (var f = new Fixture()) {
      var backend = f.backend();
      var pair = backend.prepare(f.request(), NONE);
      f.beforeVerify =
          () -> {
            assertEquals(1, f.itemLeases);
            assertEquals(1, f.sparseLeases);
            mutate(
                f,
                "UPDATE source_aliases SET verified_at='1970-01-01T00:00:01Z' WHERE"
                    + " namespace='root'");
          };
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class, () -> backend.page(pair, Optional.empty(), 2, NONE))
              .code());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void completingCoverageMismatchAndAtomicReadyFailureLeaveNoPreparedNames() throws Exception {
    try (var f = new Fixture()) {
      f.wrongFinalCoverage = true;
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.backend().prepare(f.request(), NONE))
              .code());
      assertEquals(0, f.count("sparse_name_artifacts"));
      f.wrongFinalCoverage = false;
      mutate(
          f,
          "CREATE TRIGGER reject_name_ready BEFORE UPDATE OF status ON sparse_name_artifacts WHEN"
              + " NEW.status='READY' BEGIN SELECT RAISE(ABORT,'Synthetic ready failure'); END");
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.backend().prepare(f.request(), NONE))
              .code());
      assertEquals(0, f.count("sparse_name_contexts"));
      assertEquals(0, f.count("sparse_name_artifacts"));
      assertEquals(5, f.count("item_projection_rows"));
    }
  }

  @Test
  void cancelledOrMalformedReuseCannotDeleteExistingReadyWinner() throws Exception {
    try (var f = new Fixture()) {
      var backend = f.backend();
      var pair = backend.prepare(f.request(), NONE);
      f.cancelProjection = true;
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> backend.prepare(f.request(), NONE));
      f.cancelProjection = false;
      f.names.set(1, new DisplayCandidate("", 101, 1, 0, 120, 5, 5));
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> backend.prepare(f.request(), NONE)).code());
      assertEquals(1, f.count("sparse_name_artifacts"));
      assertEquals(3, f.count("sparse_name_rows"));
      assertEquals(
          1,
          backend.lookup(pair, 101, NONE).result().name().orElseThrow().candidate().stream()
              .count());
      assertEquals(0, f.itemLeases);
      assertEquals(0, f.sparseLeases);
    }
  }

  @Test
  void sourceBoundaryFailuresKeepUsefulTypedOutcomes() throws Exception {
    try (var f = new Fixture()) {
      var codes =
          Map.of(
              ObjectStoreException.Code.LIMIT_EXCEEDED,
              ItemNamesException.Code.LIMIT_EXCEEDED,
              ObjectStoreException.Code.BUSY,
              ItemNamesException.Code.BUSY,
              ObjectStoreException.Code.CLOSED,
              ItemNamesException.Code.CLOSED,
              ObjectStoreException.Code.MISSING_OBJECT,
              ItemNamesException.Code.MISSING_LOCAL,
              ObjectStoreException.Code.INTEGRITY_FAILURE,
              ItemNamesException.Code.CORRUPT);
      for (var entry : codes.entrySet()) {
        f.itemFailure = new ObjectStoreException(entry.getKey(), "Synthetic source failure");
        assertEquals(
            entry.getValue(),
            assertThrows(ItemNamesException.class, () -> f.backend().prepare(f.request(), NONE))
                .code());
      }
      f.itemFailure =
          new io.vibeminer.application.item.ItemProjectionException(
              io.vibeminer.application.item.ItemProjectionException.Code.UNSUPPORTED,
              "Unsupported Item revision");
      assertEquals(
          ItemNamesException.Code.UNSUPPORTED,
          assertThrows(ItemNamesException.class, () -> f.backend().prepare(f.request(), NONE))
              .code());
      assertEquals(0, f.projects);
      assertEquals(0, f.count("sparse_name_artifacts"));
    }
  }

  private static void mutate(Fixture fixture, String sql) {
    try (var c = fixture.catalog.connect();
        var q = c.createStatement()) {
      q.executeUpdate(sql);
    } catch (SQLException failure) {
      throw new AssertionError(failure);
    }
  }
}
