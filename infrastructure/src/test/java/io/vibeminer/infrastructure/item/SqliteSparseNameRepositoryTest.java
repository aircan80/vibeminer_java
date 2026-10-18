package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.itemnames.ItemNamesException;
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

class SqliteSparseNameRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  class Fixture implements AutoCloseable {
    final FileObjectStore store = new FileObjectStore(directory.resolve("store"));
    final SqliteCatalog catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    final SqliteSparseNameRepository repo;

    Fixture() throws Exception {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      repo = new SqliteSparseNameRepository(catalog, store.contentSession());
      seed(SparseNameStorageFixture.prepared());
    }

    void seed(PreparedSparseNames handle) throws Exception {
      var a = handle.evidence().association();
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

    SqliteSparseNameRepository.Stage stage() {
      var header = repo.claim(SparseNameStorageFixture.definition(), NONE);
      return new SqliteSparseNameRepository.Stage(
          header.definition().artifactId(), header.generation());
    }

    PreparedSparseNames publish() {
      var stage = stage();
      repo.rows(
          stage,
          List.of(
              SparseNameStorageFixture.candidate(), new DisplayCandidate("", 202, 1, 0, 120, 5, 5)),
          NONE);
      repo.copies(stage, List.of(new CopyDeclaration(303, 101)), NONE);
      var h = SparseNameStorageFixture.prepared();
      return repo.publish(stage, h.definition(), h.evidence(), h.coverage(), NONE);
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
  void boundedPublishPersistsOneSharedArtifactAndExactEmptyCandidate() throws Exception {
    try (var f = new Fixture()) {
      var h = f.publish();
      assertEquals(SparseNameStorageFixture.prepared(), h);
      var rows = f.repo.query(h, NONE, c -> f.repo.page(c, h, -1, 3));
      assertEquals(2, rows.size());
      assertEquals("", rows.get(1).value());
      assertEquals(101, f.repo.query(h, NONE, c -> f.repo.copy(c, h, 303)).orElseThrow());
      var header = f.repo.claim(h.definition(), NONE);
      assertTrue(header.ready());
      assertEquals(
          h, f.repo.reuse(header, h.evidence(), List.of(new CopyDeclaration(303, 101)), NONE));
      assertEquals(1, f.count("sparse_name_artifacts"));
      assertEquals(2, f.count("sparse_name_rows"));
      assertEquals(0, f.count("item_projection_rows"));
    }
  }

  @Test
  void sameEpochStagingIsBusyAndDiscardCannotDeleteReadyWinner() throws Exception {
    try (var f = new Fixture()) {
      var stage = f.stage();
      assertEquals(
          ItemNamesException.Code.BUSY,
          assertThrows(ItemNamesException.class, () -> f.stage()).code());
      f.repo.discard(stage);
      assertEquals(0, f.count("sparse_name_artifacts"));
      var h = f.publish();
      f.repo.discard(new SqliteSparseNameRepository.Stage(h.artifactId(), UUID.randomUUID()));
      assertEquals(1, f.count("sparse_name_artifacts"));
    }
  }

  @Test
  void indexedIdentityEditsCannotInventRowsOrCopyDeclarations() throws Exception {
    try (var f = new Fixture()) {
      f.publish();
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        for (var sql :
            List.of(
                "UPDATE sparse_name_rows SET record_id=999 WHERE record_id=101",
                "UPDATE sparse_name_rows SET physical_ordinal=50 WHERE record_id=101",
                "UPDATE sparse_name_copies SET source_id=999",
                "UPDATE sparse_name_copies SET target_id=999"))
          assertThrows(SQLException.class, () -> q.executeUpdate(sql));
      }
    }
  }

  @Test
  void invalidFinalCountsAndCopyOverlapNeverPublishReady() throws Exception {
    try (var f = new Fixture()) {
      var stage = f.stage();
      f.repo.rows(stage, List.of(SparseNameStorageFixture.candidate()), NONE);
      var h = SparseNameStorageFixture.prepared();
      var firstStage = stage;
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class,
                  () ->
                      f.repo.publish(firstStage, h.definition(), h.evidence(), h.coverage(), NONE))
              .code());
      f.repo.discard(stage);
      assertEquals(0, f.count("sparse_name_contexts"));
      stage = f.stage();
      f.repo.rows(
          stage,
          List.of(
              SparseNameStorageFixture.candidate(), new DisplayCandidate("", 202, 1, 0, 120, 5, 5)),
          NONE);
      f.repo.copies(stage, List.of(new CopyDeclaration(101, 202)), NONE);
      var current = stage;
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class,
                  () -> f.repo.publish(current, h.definition(), h.evidence(), h.coverage(), NONE))
              .code());
      f.repo.discard(stage);
    }
  }

  @Test
  void contextKeepsFirstProofAndRejectsSubstitutedHistoricalWindows() throws Exception {
    try (var f = new Fixture()) {
      var h = f.publish();
      var e = h.evidence();
      var refreshed =
          new ItemProjectionEvidence(
              e.association(),
              new ItemProjectionEvidence.LocalVerification(
                  e.localVerification().object(),
                  Instant.ofEpochSecond(99),
                  e.localVerification().verifierRevision()),
              e.chunks(),
              e.windows(),
              e.fullDecoded());
      assertEquals(
          h,
          f.repo.reuse(
              f.repo.claim(h.definition(), NONE),
              refreshed,
              List.of(new CopyDeclaration(303, 101)),
              NONE));
      var replaced =
          new ItemProjectionEvidence(
              e.association(),
              e.localVerification(),
              e.chunks(),
              List.of(new ItemProjectionEvidence.DecodedWindow(0, 1300)),
              e.fullDecoded());
      var forged = new PreparedSparseNames(h.definition(), replaced, h.coverage());
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.repo.query(forged, NONE, c -> null))
              .code());
    }
  }

  @Test
  void migrationFromElevenIsAdditiveAndStageRecoveryNeedsNewEpoch() throws Exception {
    SqliteSparseNameRepository.Stage abandoned;
    try (var f = new Fixture()) {
      abandoned = f.stage();
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.execute("PRAGMA user_version=11");
        q.execute("CREATE TABLE test_preserved(value TEXT)");
        q.execute("INSERT INTO test_preserved VALUES('keep')");
      }
    }
    try (var f = new Fixture()) {
      assertEquals(1, f.count("test_preserved"));
      var current = f.stage();
      assertNotEquals(abandoned.generation(), current.generation());
      f.repo.discard(abandoned);
      assertEquals(1, f.count("sparse_name_artifacts"));
      f.repo.discard(current);
      try (var c = f.catalog.connect();
          var q = c.createStatement();
          var r = q.executeQuery("PRAGMA user_version")) {
        assertEquals(SqliteCatalog.SCHEMA_VERSION, r.getInt(1));
      }
    }
  }

  @Test
  void stageRejectsOutOfScopeCoordinatesAndUnboundedBatches() throws Exception {
    try (var f = new Fixture()) {
      var stage = f.stage();
      var outside = new DisplayCandidate("", 101, 0, 0, 1099, 5, 5);
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.repo.rows(stage, List.of(outside), NONE))
              .code());
      var batch = Collections.nCopies(129, SparseNameStorageFixture.candidate());
      assertEquals(
          ItemNamesException.Code.LIMIT_EXCEEDED,
          assertThrows(ItemNamesException.class, () -> f.repo.rows(stage, batch, NONE)).code());
      assertEquals(0, f.count("sparse_name_rows"));
      f.repo.discard(stage);
    }
  }

  @Test
  void sameSparseArtifactAcrossExactBuildsHasIndependentOriginalContexts() throws Exception {
    try (var f = new Fixture()) {
      var first = f.publish();
      var e = first.evidence();
      var a = e.association();
      var v = a.descriptor().association();
      var b = v.build();
      var otherBuild =
          new BuildSnapshot(
              b.productId(),
              b.editionLabel(),
              b.channel(),
              b.region(),
              b.versionLabel(),
              b.buildConfig(),
              "e".repeat(32),
              b.observedAt(),
              b.fixture(),
              b.evidence());
      var otherVariant =
          new io.vibeminer.domain.selectedfile.FileVariantAssociation(
              otherBuild,
              v.rootSource(),
              v.fileDataId(),
              v.contentKey(),
              v.localeFlags(),
              v.contentFlags(),
              v.rawFlagsFirst(),
              v.rawFlagsSecond(),
              v.rawFlagsThird(),
              v.nameHash());
      var otherDescriptor =
          new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
              otherVariant,
              a.descriptor().actualEncodingKey(),
              a.descriptor().encodedVerifierSemantics());
      var otherAssociation =
          new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
              otherDescriptor, a.configuration(), a.root(), a.proof(), a.representation());
      var otherEvidence =
          new ItemProjectionEvidence(
              otherAssociation, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded());
      var second = new PreparedSparseNames(first.definition(), otherEvidence, first.coverage());
      f.seed(second);
      assertEquals(
          second,
          f.repo.reuse(
              f.repo.claim(first.definition(), NONE),
              otherEvidence,
              List.of(new CopyDeclaration(303, 101)),
              NONE));
      assertEquals(first, f.repo.loadTrusted(first, NONE));
      assertEquals(second, f.repo.loadTrusted(second, NONE));
      assertEquals(1, f.count("sparse_name_artifacts"));
      assertEquals(2, f.count("sparse_name_rows"));
      assertEquals(2, f.count("sparse_name_contexts"));
    }
  }

  @Test
  void changedGlobalReceiptOrProofChecksumCannotAuthorizeTrustedQuery() throws Exception {
    try (var f = new Fixture()) {
      var h = f.publish();
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.executeUpdate(
            "UPDATE source_aliases SET verified_at='1970-01-01T00:00:01Z' WHERE namespace='root'");
      }
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.repo.loadTrusted(h, NONE)).code());
    }
  }

  @Test
  void rowCopyAndContextChecksumsRejectChangedDerivedMetadata() throws Exception {
    try (var f = new Fixture()) {
      var h = f.publish();
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.executeUpdate(
            "UPDATE sparse_name_rows SET record_sha256='"
                + "0".repeat(64)
                + "' WHERE record_id=101");
      }
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class,
                  () -> f.repo.query(h, NONE, c -> f.repo.exact(c, h, 101)))
              .code());
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.executeUpdate("UPDATE sparse_name_copies SET record_sha256='" + "0".repeat(64) + "'");
      }
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class,
                  () -> f.repo.query(h, NONE, c -> f.repo.copy(c, h, 303)))
              .code());
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.executeUpdate("UPDATE sparse_name_contexts SET evidence_sha256='" + "0".repeat(64) + "'");
      }
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(ItemNamesException.class, () -> f.repo.loadTrusted(h, NONE)).code());
    }
  }

  @Test
  void schemaTwelvePreservesExistingAuditPinsAndSourceProofBytes() throws Exception {
    byte[] priorProof;
    try (var f = new Fixture()) {
      var h = f.publish();
      priorProof = SelectedFileRecords.association(h.evidence().association());
      var a = h.evidence().association();
      var scope =
          new io.vibeminer.domain.retention.RetentionScope(
              h.snapshotId(),
              io.vibeminer.domain.retention.ScopeKind.ROOT_METADATA,
              List.of(
                  new io.vibeminer.domain.retention.ScopeRequirement(
                      "BUILD_CONFIG",
                      Optional.of(a.configuration().source()),
                      OptionalLong.empty(),
                      OptionalLong.empty()),
                  new io.vibeminer.domain.retention.ScopeRequirement(
                      "ROOT_MANIFEST",
                      Optional.of(a.root().source()),
                      OptionalLong.empty(),
                      OptionalLong.empty())));
      new io.vibeminer.infrastructure.retention.SqliteRetentionRepository(f.catalog)
          .pin(scope, NONE);
      try (var c = f.catalog.connect();
          var q = c.createStatement()) {
        q.execute("PRAGMA foreign_keys=OFF");
        q.executeUpdate(
            "INSERT INTO metadata_repair_attempts"
                + " VALUES('fixture-attempt','historical-token','historical-epoch','historical-nonce','"
                + h.snapshotId()
                + "','ROOT','1970-01-01T00:00:00Z',x'010203','"
                + SparseNameRecords.sha256(new byte[] {1, 2, 3})
                + "')");
        q.executeUpdate(
            "INSERT INTO metadata_repair_events VALUES('fixture-attempt',1,'UNCERTAIN',x'040506','"
                + SparseNameRecords.sha256(new byte[] {4, 5, 6})
                + "')");
        for (var table :
            List.of(
                "sparse_name_contexts",
                "sparse_name_rows",
                "sparse_name_copies",
                "sparse_name_artifacts")) q.execute("DROP TABLE " + table);
        q.execute("PRAGMA user_version=11");
      }
    }
    try (var f = new Fixture()) {
      assertEquals(0, f.count("sparse_name_artifacts"));
      assertEquals(0, f.count("sparse_name_rows"));
      assertEquals(1, f.count("metadata_repair_attempts"));
      assertEquals(1, f.count("metadata_repair_events"));
      assertEquals(1, f.count("retention_scopes"));
      try (var c = f.catalog.connect();
          var q = c.createStatement();
          var r = q.executeQuery("SELECT record FROM selected_file_associations")) {
        assertArrayEquals(priorProof, r.getBytes(1));
      }
      try (var c = f.catalog.connect();
          var q = c.createStatement();
          var r = q.executeQuery("SELECT record FROM metadata_repair_events")) {
        assertArrayEquals(new byte[] {4, 5, 6}, r.getBytes(1));
      }
    }
  }
}
