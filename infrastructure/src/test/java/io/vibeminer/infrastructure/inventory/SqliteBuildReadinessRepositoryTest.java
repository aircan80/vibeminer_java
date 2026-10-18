package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.application.buildoverview.BuildOverview.*;
import static io.vibeminer.infrastructure.inventory.SqliteBuildInventoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildreadiness.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.SourceDocument;
import io.vibeminer.infrastructure.buildoverview.SqliteBuildOverviewBackend;
import io.vibeminer.infrastructure.buildreadiness.*;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SqliteBuildReadinessRepositoryTest {
  @TempDir Path directory;
  private SqliteBuildOverviewBackendTest helper;
  private SqliteBuildReadinessRepository repository;
  private final AtomicReference<CompletedAssessment> receipt = new AtomicReference<>();

  @BeforeEach
  void setup(TestInfo info) throws Exception {
    helper = new SqliteBuildOverviewBackendTest();
    helper.directory = directory;
    helper.setup(info);
    try (var db = helper.f.sql.connect()) {
      BuildReadinessSchema.install(db);
    }
    repository = new SqliteBuildReadinessRepository(database(), policy());
  }

  @AfterEach
  void close() {
    helper.close();
  }

  private Path database() {
    return directory.resolve("catalog.sqlite");
  }

  private SqliteBuildRetentionInventory.ReservationPolicy policy() {
    return new SqliteBuildRetentionInventory.ReservationPolicy(
        SelectedFileBudgets.defaults(), ArchiveSearchBudgets.defaults());
  }

  private BuildReadiness.Context context(Request request) {
    return new BuildReadiness.Context(
        request.reference(),
        request.locale(),
        request.scopeRevision(),
        SqliteBuildInventory.REVISION);
  }

  private BuildReadiness.Status status(BuildReadiness.Context context) {
    return repository.read(List.of(context), NONE).getFirst();
  }

  private Snapshot assess(Request request) {
    return assess(request, helper.backend, repository, SqliteBuildInventory.REVISION);
  }

  private Snapshot assess(
      Request request,
      BuildOverviewBackend backend,
      BuildReadinessBackend recorder,
      String inventoryScope) {
    var capturing =
        new BuildReadinessBackend() {
          public List<BuildReadiness.Status> read(
              List<BuildReadiness.Context> contexts, CancellationToken token) {
            return recorder.read(contexts, token);
          }

          public void record(CompletedAssessment completed, CancellationToken token) {
            receipt.set(completed);
            recorder.record(completed, token);
          }
        };
    try (var delegate = new DefaultBuildOverviewService(backend, Runnable::run);
        var recording =
            new RecordingBuildOverviewService(delegate, capturing, Runnable::run, inventoryScope)) {
      return recording.read(request).completion().toCompletableFuture().join();
    }
  }

  @Test
  void actualPartialAndFullAreDatedFactsAndSurviveRepositoryReopenWithoutCasOwnership()
      throws Exception {
    var target = helper.target();
    var request = helper.request(Optional.of(target));
    assertEquals(Readiness.PARTIAL, assess(request).readiness());
    var partial = status(context(request));
    assertEquals(BuildReadiness.AdmissionPresence.PRESENT, partial.admission());
    assertEquals(Readiness.PARTIAL, partial.lastChecked().orElseThrow().readiness());
    helper.retain(target);
    var full = assess(request);
    assertEquals(Readiness.FULL, full.readiness());
    var reopened = new SqliteBuildReadinessRepository(database(), policy());
    var observed =
        reopened.read(List.of(context(request)), NONE).getFirst().lastChecked().orElseThrow();
    assertEquals(Readiness.FULL, observed.readiness());
    assertEquals(full.measuredAt(), observed.measuredAt());
    assertEquals(full.revision(), observed.overviewRevision());
    assertEquals(Optional.of(target), observed.target());
    assertEquals(request.reference().eventId(), observed.origin().eventId());
    assertEquals(1, helper.count("build_readiness_observations"));
    // The real FileObjectStore remains exclusively open; this reader neither opens nor owns it.
    assertThrows(
        RuntimeException.class,
        () ->
            new io.vibeminer.infrastructure.storage.FileObjectStore(directory.resolve("objects")));
  }

  @Test
  void absentDatabaseDoesNotCreateDirectoriesAndOldDatabaseReadDoesNotMigrate() throws Exception {
    var context = context(helper.request(Optional.empty()));
    var absent = directory.resolve("absent-parent/catalog.sqlite");
    var missing =
        new SqliteBuildReadinessRepository(absent, policy())
            .read(List.of(context), NONE)
            .getFirst();
    assertEquals(BuildReadiness.AdmissionPresence.ABSENT, missing.admission());
    assertTrue(missing.lastChecked().isEmpty());
    assertFalse(Files.exists(absent.getParent()));
    var old = directory.resolve("old.sqlite");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + old);
        var query = db.createStatement()) {
      query.execute("CREATE TABLE authored_old(id INTEGER)");
      query.execute("PRAGMA user_version=12");
    }
    var prior =
        new SqliteBuildReadinessRepository(old, policy()).read(List.of(context), NONE).getFirst();
    assertEquals(BuildReadiness.AdmissionPresence.ABSENT, prior.admission());
    assertTrue(prior.lastChecked().isEmpty());
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + old);
        var query = db.createStatement()) {
      try (var version = query.executeQuery("PRAGMA user_version")) {
        assertEquals(12, version.getInt(1));
      }
      try (var tables =
          query.executeQuery(
              "SELECT count(*) FROM sqlite_master WHERE name='build_readiness_observations'")) {
        assertEquals(0, tables.getInt(1));
      }
    }
  }

  @Test
  void exactLocaleMetadataInventoryAndReservationPoliciesIsolateHistoricalFacts() throws Exception {
    var request = helper.request(Optional.empty());
    assess(request);
    var ordinary = context(request);
    var contexts =
        List.of(
            new BuildReadiness.Context(
                request.reference(),
                "deDE",
                request.scopeRevision(),
                SqliteBuildInventory.REVISION),
            new BuildReadiness.Context(
                request.reference(),
                request.locale(),
                "other-metadata",
                SqliteBuildInventory.REVISION),
            new BuildReadiness.Context(
                request.reference(), request.locale(), request.scopeRevision(), "other-inventory"));
    for (var status : repository.read(contexts, NONE)) assertTrue(status.lastChecked().isEmpty());
    var changedPolicy =
        new SqliteBuildRetentionInventory.ReservationPolicy(
            SelectedFileBudgets.defaults(),
            new ArchiveSearchBudgets(1, 4096, 1, Duration.ofSeconds(1), 4096));
    assertTrue(
        new SqliteBuildReadinessRepository(database(), changedPolicy)
            .read(List.of(ordinary), NONE)
            .getFirst()
            .lastChecked()
            .isEmpty());
    assertTrue(status(ordinary).lastChecked().isPresent());
  }

  @Test
  void admissionDisappearanceSuppressesStoredHistoryRatherThanClaimingMetadataReady()
      throws Exception {
    var request = helper.request(Optional.empty());
    assess(request);
    try (var db = helper.f.sql.connect();
        var query = db.createStatement()) {
      query.execute("PRAGMA foreign_keys=OFF");
      query.execute("DROP TRIGGER build_open_admissions_no_delete");
      query.executeUpdate("DELETE FROM build_open_admissions");
    }
    var status = status(context(request));
    assertEquals(BuildReadiness.AdmissionPresence.ABSENT, status.admission());
    assertTrue(status.lastChecked().isEmpty());
  }

  @Test
  void checksumAndOversizedStoredRowsAreTypedCorruption() throws Exception {
    var request = helper.request(Optional.empty());
    assess(request);
    try (var db = helper.f.sql.connect();
        var query = db.createStatement()) {
      query.executeUpdate(
          "UPDATE build_readiness_observations SET record_sha256='" + "0".repeat(64) + "'");
    }
    assertEquals(
        BuildReadinessException.Code.CORRUPT_RESULT,
        assertThrows(BuildReadinessException.class, () -> status(context(request))).code());
    try (var db = helper.f.sql.connect();
        var query = db.createStatement()) {
      query.execute("PRAGMA ignore_check_constraints=ON");
      query.executeUpdate("UPDATE build_readiness_observations SET record=zeroblob(16385)");
    }
    assertEquals(
        BuildReadinessException.Code.CORRUPT_RESULT,
        assertThrows(BuildReadinessException.class, () -> status(context(request))).code());
  }

  private BuildLibraryReference refresh(boolean duplicate, boolean unregisteredFirst) {
    var history = new SqliteDiscoveryHistoryRepository(helper.f.sql);
    var first = history.read(helper.f.facts.reference().eventId(), NONE).orElseThrow();
    if (unregisteredFirst)
      history.append(
          new DiscoveryObservationMapper()
              .map(
                  UUID.randomUUID(),
                  "wow",
                  "us",
                  NOW.plusSeconds(1),
                  first.versions().source(),
                  first.cdns().source()),
          DiscoveryHistoryLimits.defaults(),
          NONE);
    var versions = first.versions().source();
    if (duplicate) {
      var raw = new String(versions.bytes(), StandardCharsets.UTF_8);
      raw += raw.lines().filter(line -> line.startsWith("us|")).findFirst().orElseThrow() + "\n";
      versions =
          new SourceDocument(
              versions.sourceUrl(),
              versions.capturedAt().plusSeconds(1),
              raw.getBytes(StandardCharsets.UTF_8));
    }
    var event =
        new DiscoveryObservationMapper()
            .map(
                UUID.randomUUID(),
                "wow",
                "us",
                NOW.plusSeconds(2),
                versions,
                first.cdns().source());
    history.append(event, DiscoveryHistoryLimits.defaults(), NONE);
    var registration =
        new SqliteBuildLibraryRepository(helper.f.sql)
            .recordObservation(
                event.eventId(),
                BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
                Optional.empty(),
                NOW,
                NONE);
    return duplicate ? registration.references().getLast() : registration.references().getFirst();
  }

  @Test
  void newerReceiptForSameGroupReusesDatedFactButPreservesOriginalOrigin() throws Exception {
    var original = helper.request(Optional.empty());
    assess(original);
    var newer = refresh(false, false);
    var current =
        new BuildReadiness.Context(
            newer, original.locale(), original.scopeRevision(), SqliteBuildInventory.REVISION);
    var historical = status(current).lastChecked().orElseThrow();
    assertEquals(original.reference().eventId(), historical.origin().eventId());
    assertNotEquals(newer.eventId(), historical.origin().eventId());
  }

  @Test
  void originalReferenceUsesHistorySequenceAndExactValidDuplicateRowNotRegistrationSequence()
      throws Exception {
    var reference = refresh(true, true);
    assertEquals(1, reference.versionsRowOrdinal());
    try (var db = helper.f.sql.connect();
        var query =
            db.prepareStatement(
                "SELECT r.registration_sequence,h.event_sequence FROM build_library_registrations r"
                    + " JOIN discovery_history_events h ON h.event_id=r.event_id WHERE"
                    + " r.event_id=?")) {
      query.setString(1, reference.eventId().toString());
      try (var row = query.executeQuery()) {
        assertNotEquals(row.getLong(1), row.getLong(2));
      }
    }
    var base = helper.request(Optional.empty());
    var request =
        new Request(
            reference, base.locale(), base.scopeRevision(), base.limits(), Optional.empty());
    assertEquals(Readiness.PARTIAL, assess(request).readiness());
    var observed = status(context(request)).lastChecked().orElseThrow();
    assertEquals(reference.observationSequence(), observed.origin().observationSequence());
    assertEquals(1, observed.origin().versionsRowOrdinal());
  }

  @Test
  void sqlOnlyOverlayDoesNotLoadOrVerifyRawSourceBodies() throws Exception {
    var request = helper.request(Optional.empty());
    assess(request);
    var expected = status(context(request));
    try (var db = helper.f.sql.connect();
        var query = db.createStatement()) {
      query.executeUpdate("UPDATE source_blobs SET bytes=zeroblob(length(bytes))");
    }
    assertEquals(
        expected,
        new SqliteBuildReadinessRepository(database(), policy())
            .read(List.of(context(request)), NONE)
            .getFirst());
  }

  @Test
  void laterCommittedAssessmentReplacesEarlierOneEvenWhenWallClockMovesBackwards()
      throws Exception {
    var target = helper.target();
    var actual = helper.retain(target);
    var request = helper.request(Optional.of(target));
    assertEquals(Readiness.FULL, assess(request).readiness());
    var digest = actual.encoded().object().digest().value();
    Files.delete(
        directory
            .resolve("objects/objects")
            .resolve(digest.substring(0, 2))
            .resolve(digest.substring(2)));
    var earlier = NOW.minusSeconds(100);
    var backend =
        new SqliteBuildOverviewBackend(
            helper.f.sql,
            helper.f.store,
            helper.f.admissions,
            helper.authority,
            helper.publisher,
            SqliteBuildOverviewBackendTest.ENCODED,
            Clock.fixed(earlier, ZoneOffset.UTC));
    var latest = assess(request, backend, repository, SqliteBuildInventory.REVISION);
    assertEquals(Readiness.PARTIAL, latest.readiness());
    var recorded = status(context(request)).lastChecked().orElseThrow();
    assertEquals(Readiness.PARTIAL, recorded.readiness());
    assertEquals(earlier, recorded.measuredAt());
  }

  @Test
  void boundedSummaryQuotaRejectsOnlyNewContextAndKeepsEarlierRecordedFact() throws Exception {
    var request = helper.request(Optional.empty());
    assess(request);
    var original = status(context(request));
    var firstReceipt = receipt.get();
    var limited =
        new SqliteBuildReadinessRepository(
            database(),
            policy(),
            new SqliteBuildReadinessRepository.Limits(1, 1000000, Duration.ofSeconds(10)));
    limited.record(firstReceipt, NONE);
    assertEquals(1, helper.count("build_readiness_observations"));
    var captureOnly =
        new BuildReadinessBackend() {
          public List<BuildReadiness.Status> read(
              List<BuildReadiness.Context> contexts, CancellationToken token) {
            throw new AssertionError();
          }

          public void record(CompletedAssessment assessment, CancellationToken token) {
            /* Capture a real wrapper receipt without publishing this context. */
          }
        };
    assess(request, helper.backend, captureOnly, "different-inventory-scope");
    var error =
        assertThrows(BuildReadinessException.class, () -> limited.record(receipt.get(), NONE));
    assertEquals(BuildReadinessException.Code.LIMIT, error.code());
    assertEquals(1, helper.count("build_readiness_observations"));
    assertEquals(original, status(context(request)));
    var deadline =
        new SqliteBuildReadinessRepository(
            database(),
            policy(),
            new SqliteBuildReadinessRepository.Limits(100000, 1000000, Duration.ofNanos(1)));
    assertEquals(
        BuildReadinessException.Code.LIMIT,
        assertThrows(BuildReadinessException.class, () -> deadline.record(firstReceipt, NONE))
            .code());
    assertEquals(original, status(context(request)));
  }

  @Test
  void boundedContextsCancellationInstructionAndDeadlineRefusalsDoNotMigrateCatalog()
      throws Exception {
    var request = helper.request(Optional.empty());
    assess(request);
    var context = context(request);
    assertThrows(CancellationException.class, () -> repository.read(List.of(context), () -> true));
    assertThrows(CancellationException.class, () -> repository.record(receipt.get(), () -> true));
    assertEquals(
        BuildReadinessException.Code.INVALID_REQUEST,
        assertThrows(
                BuildReadinessException.class,
                () -> repository.read(Collections.nCopies(101, context), NONE))
            .code());
    assertEquals(
        BuildReadinessException.Code.INVALID_REQUEST,
        assertThrows(
                BuildReadinessException.class,
                () -> repository.read(List.of(context, context), NONE))
            .code());
    var contexts = new ArrayList<BuildReadiness.Context>();
    for (int i = 0; i < 100; i++)
      contexts.add(
          new BuildReadiness.Context(
              request.reference(),
              "aa" + (char) ('A' + i / 26) + (char) ('A' + i % 26),
              request.scopeRevision(),
              SqliteBuildInventory.REVISION));
    var instructions =
        new SqliteBuildReadinessRepository(
            database(),
            policy(),
            new SqliteBuildReadinessRepository.Limits(100000, 1000, Duration.ofSeconds(10)));
    assertEquals(
        BuildReadinessException.Code.LIMIT,
        assertThrows(BuildReadinessException.class, () -> instructions.read(contexts, NONE))
            .code());
    var deadline =
        new SqliteBuildReadinessRepository(
            database(),
            policy(),
            new SqliteBuildReadinessRepository.Limits(100000, 1000000, Duration.ofNanos(1)));
    assertEquals(
        BuildReadinessException.Code.LIMIT,
        assertThrows(BuildReadinessException.class, () -> deadline.read(List.of(context), NONE))
            .code());
    assertEquals(1, helper.count("build_readiness_observations"));
    try (var db = helper.f.sql.connect();
        var query = db.createStatement();
        var version = query.executeQuery("PRAGMA user_version")) {
      assertEquals(SqliteCatalog.SCHEMA_VERSION, version.getInt(1));
    }
  }

  private void malformedNumericReadFails(String table, String assignment) throws Exception {
    var target = helper.target();
    helper.retain(target);
    var request = helper.request(Optional.of(target));
    assertEquals(Readiness.FULL, assess(request).readiness());
    try (var db = helper.f.sql.connect()) {
      var names = new ArrayList<String>();
      try (var q =
          db.prepareStatement(
              "SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name=?")) {
        q.setString(1, table);
        try (var rows = q.executeQuery()) {
          while (rows.next()) names.add(rows.getString(1));
        }
      }
      try (var q = db.createStatement()) {
        q.execute("PRAGMA ignore_check_constraints=ON");
        for (var name : names) q.execute("DROP TRIGGER \"" + name + "\"");
        q.executeUpdate("UPDATE " + table + " SET " + assignment);
      }
    }
    var failure = assertThrows(BuildReadinessException.class, () -> status(context(request)));
    assertEquals(BuildReadinessException.Code.CORRUPT_RESULT, failure.code());
  }

  @Test
  void fractionalRegisteredGroupOrdinalCannotRoundIntoDatedFull() throws Exception {
    malformedNumericReadFails(
        "build_library_event_groups", "first_row_ordinal=first_row_ordinal+0.5");
  }

  @Test
  void oversizedRegisteredGroupOrdinalCannotWrapIntoDatedFull() throws Exception {
    malformedNumericReadFails("build_library_event_groups", "first_row_ordinal=4294967296");
  }

  @Test
  void fractionalAdmissionCountCannotRoundIntoDatedFull() throws Exception {
    malformedNumericReadFails("build_open_admissions", "required_count=required_count+0.5");
  }

  @Test
  void fractionalHistoryCountCannotRoundIntoDatedFull() throws Exception {
    malformedNumericReadFails("discovery_history_events", "build_count=build_count+0.5");
  }

  @Test
  void fractionalHistoryPositionCannotRoundIntoDatedFull() throws Exception {
    malformedNumericReadFails("discovery_history_builds", "position=position+0.5");
  }

  @Test
  void fractionalHistoryOrdinalCannotRoundIntoDatedFull() throws Exception {
    malformedNumericReadFails(
        "discovery_history_builds", "versions_row_ordinal=versions_row_ordinal+0.5");
  }

  @Test
  void fractionalBindingProvenanceCannotHideBehindMatchingIssuedTarget() throws Exception {
    malformedNumericReadFails("build_retention_bindings", "row_ordinal=row_ordinal+0.5");
  }
}
