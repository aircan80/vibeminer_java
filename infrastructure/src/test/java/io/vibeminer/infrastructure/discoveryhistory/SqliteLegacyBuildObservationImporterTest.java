package io.vibeminer.infrastructure.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildlibrary.LegacyBuildObservationImporter.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteLegacyBuildObservationImporterTest {
  @TempDir Path directory;
  private static final CancellationToken NONE = CancellationToken.none();
  private static final Instant CAPTURED = Instant.parse("2025-01-01T00:00:00Z");
  private static final Instant IMPORTED = Instant.parse("2026-10-18T12:00:00Z");

  private SqliteCatalog catalog() {
    return new SqliteCatalog(directory.resolve("catalog.sqlite"));
  }

  private DiscoveryEvent event(Instant captured, String... configs) {
    var versions = new StringBuilder(SqliteDiscoveryHistoryRepositoryTest.HEADER);
    for (var config : configs)
      versions
          .append("us|")
          .append(config)
          .append('|')
          .append("b".repeat(32))
          .append("||100|12.1.test||unknown\n");
    var base = "https://us.version.battle.net/v2/products/wow/";
    return new DiscoveryObservationMapper()
        .map(
            UUID.randomUUID(),
            "wow",
            "us",
            captured,
            new SourceDocument(
                base + "versions",
                captured.minusSeconds(2),
                versions.toString().getBytes(StandardCharsets.UTF_8)),
            new SourceDocument(
                base + "cdns",
                captured.minusSeconds(1),
                "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\nus|tpr/wow|cdn.example.invalid\n"
                    .getBytes(StandardCharsets.UTF_8)));
  }

  private void seed(SqliteCatalog catalog, DiscoveryEvent event) throws Exception {
    try (var db = catalog.connect()) {
      db.setAutoCommit(false);
      for (var row : event.builds()) catalog.ensureSnapshot(db, row.build());
      db.commit();
    }
  }

  private long count(SqliteCatalog catalog, String table) throws Exception {
    return SqliteDiscoveryHistoryRepositoryTest.count(catalog, table);
  }

  private Batch run(SqliteLegacyBuildObservationImporter importer) {
    return importer.importNext("wow", Optional.empty(), Limits.defaults(), IMPORTED, NONE);
  }

  @Test
  void onlyLegacyCatalogProofBecomesOneNeutralEventWithOriginalReceiptsAndLocalIntent()
      throws Exception {
    var catalog = catalog();
    var old = event(CAPTURED, "a".repeat(32), "c".repeat(32));
    seed(catalog, old);
    var snapshot = old.builds().getFirst().build();
    new SqliteRetentionRepository(catalog)
        .pin(
            new RetentionScope(
                snapshot.snapshotId(),
                ScopeKind.ROOT_METADATA,
                List.of(
                    new ScopeRequirement(
                        "BUILD_CONFIG",
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty()),
                    new ScopeRequirement(
                        "ROOT_MANIFEST",
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty()))),
            NONE);
    assertEquals(0, count(catalog, "discovery_history_events"));
    var importer = new SqliteLegacyBuildObservationImporter(catalog);
    var result = run(importer);
    assertTrue(result.complete());
    assertTrue(result.nextCursor().isEmpty());
    assertEquals(2, result.scannedSnapshots());
    assertEquals(1, result.importedPairs());
    assertEquals(0, result.alreadyImportedPairs());
    assertEquals(1, count(catalog, "discovery_history_events"));
    assertEquals(1, count(catalog, "build_library_registrations"));
    assertEquals(2, count(catalog, "source_blobs"));
    assertEquals(0, count(catalog, "build_library_baselines"));
    assertEquals(0, count(catalog, "build_library_checks"));
    var history =
        new SqliteDiscoveryHistoryRepository(catalog)
            .page(
                new DiscoveryHistoryFilter("wow", Optional.of("us"), Optional.empty()),
                Optional.empty(),
                25,
                NONE);
    var detail =
        new SqliteDiscoveryHistoryRepository(catalog)
            .read(history.events().getFirst().eventId(), NONE)
            .orElseThrow();
    assertEquals(2, detail.builds().size());
    assertEquals(CAPTURED, detail.completedAt());
    assertEquals(old.versions(), detail.versions());
    assertEquals(old.cdns(), detail.cdns());
    var page =
        new SqliteBuildLibraryReader(catalog)
            .page(
                new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.ALL),
                Optional.empty(),
                25,
                NONE);
    assertEquals(2, page.entries().size());
    assertTrue(page.entries().stream().anyMatch(entry -> entry.row().local().isLocal()));
    for (var entry : page.entries()) {
      assertFalse(entry.row().seen().eligibleAsNew());
      assertFalse(entry.row().seen().isNew());
      assertEquals(IMPORTED, entry.row().seen().firstSeenAt());
      assertEquals(BuildLibraryProjection.Advertisement.UNKNOWN, entry.row().advertisement());
    }
    try (var db = catalog.connect()) {
      for (var row : old.builds())
        assertEquals(row.build(), catalog.readSnapshot(db, row.build().snapshotId()).orElseThrow());
      try (var query = db.createStatement();
          var version = query.executeQuery("PRAGMA user_version")) {
        assertEquals(14, version.getInt(1));
      }
    }
    var retry = run(new SqliteLegacyBuildObservationImporter(catalog()));
    assertTrue(retry.complete());
    assertEquals(0, retry.importedPairs());
    assertEquals(1, retry.alreadyImportedPairs());
    assertEquals(1, count(catalog, "discovery_history_events"));
    assertEquals(2, count(catalog, "source_blobs"));
  }

  @Test
  void interruptedAfterHistoryCommitRetriesSameEventAndDoesNotInventFirstSeenTime()
      throws Exception {
    var catalog = catalog();
    seed(catalog, event(CAPTURED, "a".repeat(32)));
    try (var db = catalog.connect();
        var query = db.createStatement()) {
      query.execute(
          "CREATE TRIGGER reject_legacy_registration BEFORE INSERT ON build_library_registrations"
              + " BEGIN SELECT RAISE(ABORT,'authored failure');END");
    }
    var failure =
        assertThrows(
            LegacyBuildImportException.class,
            () -> run(new SqliteLegacyBuildObservationImporter(catalog)));
    assertEquals(LegacyBuildImportException.Code.STORAGE_UNAVAILABLE, failure.code());
    assertEquals(0, failure.partialProgress().importedPairs());
    assertFalse(failure.partialProgress().complete());
    assertEquals(1, count(catalog, "discovery_history_events"));
    assertEquals(0, count(catalog, "build_library_registrations"));
    var history = new SqliteDiscoveryHistoryRepository(catalog);
    var filter = new DiscoveryHistoryFilter("wow", Optional.empty(), Optional.empty());
    var id = history.page(filter, Optional.empty(), 25, NONE).events().getFirst().eventId();
    try (var db = catalog.connect();
        var query = db.createStatement()) {
      query.execute("DROP TRIGGER reject_legacy_registration");
    }
    var result =
        new SqliteLegacyBuildObservationImporter(catalog)
            .importNext("wow", Optional.empty(), Limits.defaults(), IMPORTED.plusSeconds(60), NONE);
    assertEquals(1, result.importedPairs());
    assertEquals(0, result.alreadyImportedPairs());
    assertEquals(
        id, history.page(filter, Optional.empty(), 25, NONE).events().getFirst().eventId());
    assertEquals(1, count(catalog, "discovery_history_events"));
    assertEquals(1, count(catalog, "build_library_registrations"));
  }

  @Test
  void boundedHeadersAndPairsStopAndResumeWithoutDroppingRowsOrDuplicatingPairs() throws Exception {
    var catalog = catalog();
    seed(catalog, event(CAPTURED, "a".repeat(32), "c".repeat(32)));
    seed(catalog, event(CAPTURED.plusSeconds(20), "d".repeat(32)));
    var importer = new SqliteLegacyBuildObservationImporter(catalog);
    Optional<Cursor> cursor = Optional.empty();
    int scanned = 0;
    int imported = 0;
    for (int i = 0; i < 4; i++) {
      var result = importer.importNext("wow", cursor, new Limits(1, 1, 2L << 20), IMPORTED, NONE);
      assertTrue(result.scannedSnapshots() <= 1);
      assertTrue(result.importedPairs() <= 1);
      scanned += result.scannedSnapshots();
      imported += result.importedPairs();
      if (result.complete()) {
        assertTrue(result.nextCursor().isEmpty());
        break;
      }
      assertTrue(result.nextCursor().isPresent());
      assertNotEquals(cursor, result.nextCursor());
      cursor = result.nextCursor();
      assertTrue(i < 3, "bounded continuation must finish");
    }
    assertEquals(3, scanned);
    assertEquals(2, imported);
    assertEquals(2, count(catalog, "discovery_history_events"));
    assertEquals(3, count(catalog, "discovery_history_builds"));
    assertEquals(2, count(catalog, "build_library_registrations"));
  }

  @Test
  void sameBodyBytesWithDifferentCaptureReceiptsRemainTwoPairs() throws Exception {
    var catalog = catalog();
    // Both canonical snapshots must retain the same pair bytes with independent capture receipts.
    var shared = event(CAPTURED, "a".repeat(32), "c".repeat(32));
    var later = event(CAPTURED.plusSeconds(20), "a".repeat(32), "c".repeat(32));
    try (var db = catalog.connect()) {
      db.setAutoCommit(false);
      catalog.ensureSnapshot(db, shared.builds().get(0).build());
      catalog.ensureSnapshot(db, later.builds().get(1).build());
      db.commit();
    }
    var result = run(new SqliteLegacyBuildObservationImporter(catalog));
    assertEquals(2, result.importedPairs());
    assertEquals(2, count(catalog, "source_blobs"));
    assertEquals(2, count(catalog, "discovery_history_events"));
    assertEquals(2, count(catalog, "build_library_registrations"));
  }

  @Test
  void nonfixtureMissingEvidenceAndCorruptSourceFailTypedRatherThanEmptySuccess() throws Exception {
    var catalog = catalog();
    var unrecorded =
        new BuildSnapshot(
            "wow",
            "World of Warcraft",
            "Published",
            "us",
            "legacy",
            "a".repeat(32),
            "b".repeat(32),
            CAPTURED,
            false);
    try (var db = catalog.connect()) {
      db.setAutoCommit(false);
      catalog.ensureSnapshot(db, unrecorded);
      db.commit();
    }
    var incompatible =
        assertThrows(
            LegacyBuildImportException.class,
            () -> run(new SqliteLegacyBuildObservationImporter(catalog)));
    assertEquals(LegacyBuildImportException.Code.INCOMPATIBLE_LEGACY, incompatible.code());
    assertFalse(incompatible.partialProgress().complete());
    assertEquals(0, incompatible.partialProgress().importedPairs());
    assertEquals(0, count(catalog, "discovery_history_events"));
  }

  @Test
  void corruptedSharedBodyFailsBeforePublication() throws Exception {
    var catalog = catalog();
    var old = event(CAPTURED, "a".repeat(32));
    seed(catalog, old);
    try (var db = catalog.connect();
        var query = db.prepareStatement("UPDATE source_blobs SET bytes=? WHERE digest=?")) {
      var bad = old.versions().source().bytes();
      bad[bad.length - 2] ^= 1;
      query.setBytes(1, bad);
      query.setString(2, old.versions().source().sha256());
      query.executeUpdate();
    }
    var corrupt =
        assertThrows(
            LegacyBuildImportException.class,
            () -> run(new SqliteLegacyBuildObservationImporter(catalog)));
    assertEquals(LegacyBuildImportException.Code.CORRUPT_LEGACY, corrupt.code());
    assertFalse(corrupt.partialProgress().complete());
    assertEquals(0, count(catalog, "discovery_history_events"));
  }

  @Test
  void samePairWithInconsistentCanonicalObservationTimesIsIncompatible() throws Exception {
    var catalog = catalog();
    var old = event(CAPTURED, "a".repeat(32), "c".repeat(32));
    seed(catalog, old);
    try (var db = catalog.connect();
        var query = db.prepareStatement("UPDATE snapshots SET observed_at=? WHERE id=?")) {
      query.setString(1, CAPTURED.plusSeconds(1).toString());
      query.setString(2, old.builds().getLast().build().snapshotId());
      query.executeUpdate();
    }
    var failure =
        assertThrows(
            LegacyBuildImportException.class,
            () -> run(new SqliteLegacyBuildObservationImporter(catalog)));
    assertEquals(LegacyBuildImportException.Code.INCOMPATIBLE_LEGACY, failure.code());
    assertFalse(failure.partialProgress().complete());
    assertEquals(0, failure.partialProgress().importedPairs());
    assertEquals(0, count(catalog, "discovery_history_events"));
    assertEquals(0, count(catalog, "build_library_registrations"));
  }

  @Test
  void fixturesAndGenuinelyEmptyCatalogAreNotFabricatedOfficialObservations() throws Exception {
    var catalog = catalog();
    var empty = run(new SqliteLegacyBuildObservationImporter(catalog));
    assertTrue(empty.complete());
    assertEquals(0, empty.scannedSnapshots());
    var fixture =
        new BuildSnapshot(
            "wow",
            "Fixture",
            "Published",
            "us",
            "authored",
            "a".repeat(32),
            "b".repeat(32),
            CAPTURED,
            true);
    try (var db = catalog.connect()) {
      db.setAutoCommit(false);
      catalog.ensureSnapshot(db, fixture);
      db.commit();
    }
    var ignored = run(new SqliteLegacyBuildObservationImporter(catalog));
    assertTrue(ignored.complete());
    assertEquals(1, ignored.scannedSnapshots());
    assertEquals(0, ignored.importedPairs());
    assertEquals(0, ignored.alreadyImportedPairs());
    assertEquals(0, count(catalog, "discovery_history_events"));
    assertEquals(0, count(catalog, "build_library_registrations"));
  }

  @Test
  void cancellationAfterCommittedPairReportsSafeProgressAndResumes() throws Exception {
    var catalog = catalog();
    seed(catalog, event(CAPTURED, "a".repeat(32)));
    seed(catalog, event(CAPTURED.plusSeconds(20), "c".repeat(32)));
    CancellationToken stopAfterCommit =
        () -> {
          try {
            return count(catalog, "build_library_registrations") > 0;
          } catch (Exception failure) {
            throw new AssertionError(failure);
          }
        };
    var cancelled =
        assertThrows(
            LegacyBuildImportCancelled.class,
            () ->
                new SqliteLegacyBuildObservationImporter(catalog)
                    .importNext(
                        "wow", Optional.empty(), Limits.defaults(), IMPORTED, stopAfterCommit));
    assertEquals(1, cancelled.partialProgress().importedPairs());
    assertTrue(cancelled.partialProgress().nextCursor().isPresent());
    assertEquals(1, count(catalog, "build_library_registrations"));
    var rest =
        new SqliteLegacyBuildObservationImporter(catalog)
            .importNext(
                "wow", cancelled.partialProgress().nextCursor(), Limits.defaults(), IMPORTED, NONE);
    assertTrue(rest.complete());
    assertEquals(1, rest.importedPairs());
    assertEquals(2, count(catalog, "build_library_registrations"));
  }

  @Test
  void alreadyCancelledAndWrongProductCursorPreserveZeroProgress() throws Exception {
    var catalog = catalog();
    seed(catalog, event(CAPTURED, "a".repeat(32)));
    var importer = new SqliteLegacyBuildObservationImporter(catalog);
    var cancelled =
        assertThrows(
            LegacyBuildImportCancelled.class,
            () ->
                importer.importNext(
                    "wow", Optional.empty(), Limits.defaults(), IMPORTED, () -> true));
    assertEquals(0, cancelled.partialProgress().importedPairs());
    assertFalse(cancelled.partialProgress().complete());
    var invalid =
        assertThrows(
            LegacyBuildImportException.class,
            () ->
                importer.importNext(
                    "wow",
                    Optional.of(new Cursor("wowf", "a".repeat(64))),
                    Limits.defaults(),
                    IMPORTED,
                    NONE));
    assertEquals(LegacyBuildImportException.Code.INVALID_REQUEST, invalid.code());
    assertEquals(0, count(catalog, "discovery_history_events"));
  }

  @Test
  void laterPairFailureReportsCommittedProgressAndCanResumeAtLastSafeSnapshot() throws Exception {
    var catalog = catalog();
    seed(catalog, event(CAPTURED, "a".repeat(32)));
    seed(catalog, event(CAPTURED.plusSeconds(20), "c".repeat(32)));
    try (var db = catalog.connect();
        var query = db.createStatement()) {
      query.execute(
          "CREATE TRIGGER reject_second_legacy BEFORE INSERT ON build_library_registrations WHEN"
              + " (SELECT count(*) FROM build_library_registrations)>0 BEGIN SELECT"
              + " RAISE(ABORT,'authored second failure');END");
    }
    var failure =
        assertThrows(
            LegacyBuildImportException.class,
            () -> run(new SqliteLegacyBuildObservationImporter(catalog)));
    assertEquals(1, failure.partialProgress().importedPairs());
    assertTrue(failure.partialProgress().nextCursor().isPresent());
    assertEquals(1, count(catalog, "build_library_registrations"));
    assertEquals(2, count(catalog, "discovery_history_events"));
    try (var db = catalog.connect();
        var query = db.createStatement()) {
      query.execute("DROP TRIGGER reject_second_legacy");
    }
    var retry =
        new SqliteLegacyBuildObservationImporter(catalog)
            .importNext(
                "wow", failure.partialProgress().nextCursor(), Limits.defaults(), IMPORTED, NONE);
    assertEquals(1, retry.importedPairs());
    assertTrue(retry.complete());
    assertEquals(2, count(catalog, "build_library_registrations"));
    assertEquals(2, count(catalog, "discovery_history_events"));
  }
}
