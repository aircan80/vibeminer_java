package io.vibeminer.infrastructure.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBuildLibraryRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = SqliteDiscoveryHistoryRepositoryTest.NOW;

  SqliteCatalog catalog() {
    return new SqliteCatalog(directory.resolve("catalog.sqlite"));
  }

  DiscoveryEvent save(String key) throws Exception {
    var event = SqliteDiscoveryHistoryRepositoryTest.event(key);
    new SqliteDiscoveryHistoryRepository(catalog())
        .append(event, DiscoveryHistoryLimits.defaults(), NONE);
    return event;
  }

  BuildLibraryRegistration success(
      SqliteBuildLibraryRepository repo, DiscoveryEvent event, Instant when) {
    var ticket =
        repo.beginCheck(new BuildLibraryScope(event.productId(), event.region()), when, NONE);
    return repo.recordObservation(
        event.eventId(),
        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
        Optional.of(ticket),
        when,
        NONE);
  }

  @Test
  void firstSuccessfulScopeIsNeutralLaterGroupNewUntilExactOpenAndAbaDoesNotRebadge()
      throws Exception {
    var repo = new SqliteBuildLibraryRepository(catalog());
    var a = save("a".repeat(32));
    var first = success(repo, a, NOW);
    var aRef = first.references().getFirst();
    assertFalse(repo.findSeen(aRef.key().identity(), NONE).orElseThrow().isNew());
    var b = save("b".repeat(32));
    var second = success(repo, b, NOW.plusSeconds(10));
    var bRef = second.references().getFirst();
    var seen = repo.findSeen(bRef.key().identity(), NONE).orElseThrow();
    assertTrue(seen.isNew());
    assertEquals(
        seen,
        new SqliteBuildLibraryRepository(catalog())
            .findSeen(bRef.key().identity(), NONE)
            .orElseThrow());
    var acknowledged = repo.acknowledgeOpen(bRef, NOW.plusSeconds(20), NONE);
    assertFalse(acknowledged.isNew());
    success(repo, save("a".repeat(32)), NOW.plusSeconds(30));
    success(repo, save("b".repeat(32)), NOW.plusSeconds(40));
    assertEquals(acknowledged, repo.findSeen(bRef.key().identity(), NONE).orElseThrow());
  }

  @Test
  void historicalImportsStayNeutralCannotBecomeCurrentOrBaseline() throws Exception {
    var repo = new SqliteBuildLibraryRepository(catalog());
    var event = save("a".repeat(32));
    var registration =
        repo.recordObservation(
            event.eventId(),
            BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
            Optional.empty(),
            NOW,
            NONE);
    assertTrue(repo.baseline(new BuildLibraryScope("wow", "us"), NONE).isEmpty());
    assertEquals(
        BuildLibraryRegionalState.CheckState.NOT_CHECKED,
        repo.regionalState(new BuildLibraryScope("wow", "us"), NONE).checkState());
    assertEquals(
        registration,
        repo.recordObservation(
            event.eventId(),
            BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
            Optional.empty(),
            NOW.plusSeconds(30),
            NONE));
    var ticket = repo.beginCheck(new BuildLibraryScope("wow", "us"), NOW, NONE);
    assertEquals(
        BuildLibraryException.Code.IMMUTABLE_REGISTRATION_CONFLICT,
        assertThrows(
                BuildLibraryException.class,
                () ->
                    repo.recordObservation(
                        event.eventId(),
                        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                        Optional.of(ticket),
                        NOW,
                        NONE))
            .code());
  }

  @Test
  void staleSuccessRegistersNovelNewGroupButCannotReplaceNewerScopeOutcome() throws Exception {
    var repo = new SqliteBuildLibraryRepository(catalog());
    var scope = new BuildLibraryScope("wow", "us");
    var a = save("a".repeat(32));
    var b = save("b".repeat(32));
    var older = repo.beginCheck(scope, NOW, NONE);
    var newer = repo.beginCheck(scope, NOW.minusSeconds(100), NONE);
    var winner =
        repo.recordObservation(
            b.eventId(),
            BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
            Optional.of(newer),
            NOW,
            NONE);
    var stale =
        repo.recordObservation(
            a.eventId(),
            BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
            Optional.of(older),
            NOW.minusSeconds(200),
            NONE);
    assertTrue(
        repo.findSeen(stale.references().getFirst().key().identity(), NONE).orElseThrow().isNew());
    assertEquals(
        b.eventId(), repo.regionalState(scope, NONE).latestSuccessfulEventId().orElseThrow());
    assertEquals(b.eventId(), repo.baseline(scope, NONE).orElseThrow().eventId());
  }

  @Test
  void failedScopesNeverSeedAndStaleFailureCannotOverwriteNewerSuccess() throws Exception {
    var repo = new SqliteBuildLibraryRepository(catalog());
    var scope = new BuildLibraryScope("wow", "us");
    var older = repo.beginCheck(scope, NOW, NONE);
    var newer = repo.beginCheck(scope, NOW, NONE);
    var event = save("a".repeat(32));
    repo.recordObservation(
        event.eventId(),
        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
        Optional.of(newer),
        NOW,
        NONE);
    var state =
        repo.recordFailedScope(
            older, BuildLibraryRepository.Failure.CANCELLED, NOW.plusSeconds(10), NONE);
    assertEquals(BuildLibraryRegionalState.CheckState.SUCCESS, state.checkState());
    var unseen = new BuildLibraryScope("wow", "eu");
    var eu = repo.beginCheck(unseen, NOW, NONE);
    repo.recordFailedScope(eu, BuildLibraryRepository.Failure.FAILED, NOW, NONE);
    assertTrue(repo.baseline(unseen, NONE).isEmpty());
  }

  @Test
  void checkTicketsFinishOnceAndNamedCapRefusesInsteadOfPruning() throws Exception {
    var repo = new SqliteBuildLibraryRepository(catalog(), 2);
    var scope = new BuildLibraryScope("wow", "us");
    var ticket = repo.beginCheck(scope, NOW, NONE);
    var event = save("a".repeat(32));
    var stored =
        repo.recordObservation(
            event.eventId(),
            BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
            Optional.of(ticket),
            NOW,
            NONE);
    assertEquals(
        stored,
        repo.recordObservation(
            event.eventId(),
            BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
            Optional.of(ticket),
            NOW.plusSeconds(100),
            NONE));
    assertEquals(
        BuildLibraryException.Code.INVALID_CHECK_STATE,
        assertThrows(
                BuildLibraryException.class,
                () ->
                    repo.recordFailedScope(
                        ticket, BuildLibraryRepository.Failure.FAILED, NOW, NONE))
            .code());
    repo.beginCheck(scope, NOW, NONE);
    assertEquals(
        BuildLibraryException.Code.LIMIT_EXCEEDED,
        assertThrows(BuildLibraryException.class, () -> repo.beginCheck(scope, NOW, NONE)).code());
  }

  @Test
  void lateCancellationRollsBackProjectionButKeepsCommittedHistoryAndPendingTicket()
      throws Exception {
    var c = catalog();
    var repo = new SqliteBuildLibraryRepository(c);
    var event = save("a".repeat(32));
    var ticket = repo.beginCheck(new BuildLibraryScope("wow", "us"), NOW, NONE);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            repo.recordObservation(
                event.eventId(),
                BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                Optional.of(ticket),
                NOW,
                () -> calls.incrementAndGet() >= 4));
    assertEquals(0, SqliteDiscoveryHistoryRepositoryTest.count(c, "build_library_registrations"));
    assertEquals(0, SqliteDiscoveryHistoryRepositoryTest.count(c, "build_library_groups"));
    assertTrue(repo.baseline(ticket.scope(), NONE).isEmpty());
    assertTrue(new SqliteDiscoveryHistoryRepository(c).read(event.eventId(), NONE).isPresent());
    assertNotNull(
        repo.recordObservation(
            event.eventId(),
            BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
            Optional.of(ticket),
            NOW,
            NONE));
  }

  @Test
  void concurrentFirstChecksEstablishOneBaselineAndOneLaterNewGroup() throws Exception {
    var c = catalog();
    var repo = new SqliteBuildLibraryRepository(c);
    var a = save("a".repeat(32));
    var b = save("b".repeat(32));
    var scope = new BuildLibraryScope("wow", "us");
    var first = repo.beginCheck(scope, NOW, NONE);
    var second = repo.beginCheck(scope, NOW, NONE);
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var one =
          workers.submit(
              () -> {
                gate.await();
                return new SqliteBuildLibraryRepository(c)
                    .recordObservation(
                        a.eventId(),
                        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                        Optional.of(first),
                        NOW,
                        NONE);
              });
      var two =
          workers.submit(
              () -> {
                gate.await();
                return new SqliteBuildLibraryRepository(c)
                    .recordObservation(
                        b.eventId(),
                        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                        Optional.of(second),
                        NOW,
                        NONE);
              });
      gate.countDown();
      var oneRef = one.get(10, java.util.concurrent.TimeUnit.SECONDS).references().getFirst();
      var twoRef = two.get(10, java.util.concurrent.TimeUnit.SECONDS).references().getFirst();
      assertNotEquals(
          repo.findSeen(oneRef.key().identity(), NONE).orElseThrow().isNew(),
          repo.findSeen(twoRef.key().identity(), NONE).orElseThrow().isNew());
    }
    assertEquals(
        b.eventId(), repo.regionalState(scope, NONE).latestSuccessfulEventId().orElseThrow());
  }

  @Test
  void metadataTamperAndForgedOpenCannotAcknowledgeOrPolluteFacts() throws Exception {
    var c = catalog();
    var repo = new SqliteBuildLibraryRepository(c);
    var a = save("a".repeat(32));
    var ref = success(repo, a, NOW).references().getFirst();
    var forged =
        new BuildLibraryReference(
            ref.key(),
            ref.eventId(),
            ref.observationSequence(),
            ref.snapshotId(),
            ref.versionsRowOrdinal(),
            ref.region(),
            NOW.plusSeconds(1),
            ref.versions(),
            ref.cdns(),
            ref.selectedCdn());
    assertEquals(
        BuildLibraryException.Code.INVALID_REQUEST,
        assertThrows(BuildLibraryException.class, () -> repo.acknowledgeOpen(forged, NOW, NONE))
            .code());
    assertTrue(repo.findSeen(ref.key().identity(), NONE).orElseThrow().acknowledgedAt().isEmpty());
    try (var db = c.connect();
        var q = db.createStatement()) {
      q.executeUpdate("UPDATE build_library_groups SET seen_sha256='" + "0".repeat(64) + "'");
    }
    assertEquals(
        BuildLibraryException.Code.CORRUPT_LIBRARY,
        assertThrows(BuildLibraryException.class, () -> repo.findSeen(ref.key().identity(), NONE))
            .code());
  }

  @Test
  void halfwayProjectionFailureLeavesNoBaselineOrHalfGroup() throws Exception {
    var c = catalog();
    var repo = new SqliteBuildLibraryRepository(c);
    var event = save("a".repeat(32));
    var ticket = repo.beginCheck(new BuildLibraryScope("wow", "us"), NOW, NONE);
    try (var db = c.connect();
        var q = db.createStatement()) {
      q.execute(
          "CREATE TRIGGER reject_library_link BEFORE INSERT ON build_library_event_groups BEGIN"
              + " SELECT RAISE(ABORT,'authored failure');END");
    }
    assertThrows(
        BuildLibraryException.class,
        () ->
            repo.recordObservation(
                event.eventId(),
                BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                Optional.of(ticket),
                NOW,
                NONE));
    for (var table :
        List.of(
            "build_library_groups",
            "build_library_registrations",
            "build_library_baselines",
            "build_library_event_groups"))
      assertEquals(0, SqliteDiscoveryHistoryRepositoryTest.count(c, table));
    try (var db = c.connect();
        var q = db.createStatement();
        var row = q.executeQuery("SELECT status FROM build_library_checks")) {
      assertEquals("PENDING", row.getString(1));
    }
  }

  @Test
  void schema13UpgradePreservesHistoryAndDoesNotInventBaselines() throws Exception {
    var c = catalog();
    var event = save("a".repeat(32));
    try (var db = c.connect();
        var q = db.createStatement()) {
      var triggers = new ArrayList<String>();
      try (var rows =
          q.executeQuery(
              "SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'library_%'")) {
        while (rows.next()) triggers.add(rows.getString(1));
      }
      for (var trigger : triggers) q.execute("DROP TRIGGER " + trigger);
      for (var table :
          List.of(
              "build_library_snapshot_groups",
              "build_library_revision",
              "build_library_region_states",
              "build_library_baselines",
              "build_library_event_groups",
              "build_library_groups",
              "build_library_registrations",
              "build_library_checks",
              "build_library_product_checks",
              "build_library_admission_counter")) q.execute("DROP TABLE " + table);
      q.execute("PRAGMA user_version=13");
    }
    var repo = new SqliteBuildLibraryRepository(c);
    assertTrue(repo.baseline(new BuildLibraryScope("wow", "us"), NONE).isEmpty());
    assertEquals(
        event, new SqliteDiscoveryHistoryRepository(c).read(event.eventId(), NONE).orElseThrow());
    assertEquals(0, SqliteDiscoveryHistoryRepositoryTest.count(c, "build_library_groups"));
  }

  @Test
  void productAdmissionPrecedesUnknownRegionDerivationAndSharesGlobalOrder() throws Exception {
    var c = catalog();
    var repo = new SqliteBuildLibraryRepository(c);
    var scope = new BuildLibraryScope("wow", "us");
    var standalone = repo.beginCheck(scope, NOW, NONE);
    var a = repo.beginProductCheck("wow", NOW, NONE);
    var b = repo.beginProductCheck("wow", NOW.minusSeconds(100), NONE);
    assertTrue(standalone.ordinal() < a.ordinal());
    assertTrue(a.ordinal() < b.ordinal());
    var late = repo.deriveRegionalCheck(a, "us", NONE);
    assertEquals(a.ordinal(), late.ordinal());
    assertEquals(late, repo.deriveRegionalCheck(a, "us", NONE));
    var fresh = repo.deriveRegionalCheck(b, "us", NONE);
    var freshEvent = save("b".repeat(32));
    repo.recordObservation(
        freshEvent.eventId(),
        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
        Optional.of(fresh),
        NOW,
        NONE);
    var olderEvent = save("a".repeat(32));
    var stored =
        repo.recordObservation(
            olderEvent.eventId(),
            BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
            Optional.of(late),
            NOW,
            NONE);
    assertTrue(
        repo.findSeen(stored.references().getFirst().key().identity(), NONE).orElseThrow().isNew());
    assertEquals(
        freshEvent.eventId(),
        repo.regionalState(scope, NONE).latestSuccessfulEventId().orElseThrow());
    var eu = repo.deriveRegionalCheck(a, "eu", NONE);
    repo.recordFailedScope(eu, BuildLibraryRepository.Failure.FAILED, NOW, NONE);
    assertEquals(
        BuildLibraryRegionalState.CheckState.NOT_CHECKED,
        repo.regionalState(eu.scope(), NONE).checkState());
    assertEquals(List.of(new BuildLibraryScope("wow", "eu"), scope), repo.knownScopes("wow", NONE));
  }

  @Test
  void simultaneousProductAndRegionalAdmissionUseOneDurableOrdinalAuthority() throws Exception {
    var c = catalog();
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var product =
          workers.submit(
              () -> {
                gate.await();
                return new SqliteBuildLibraryRepository(c)
                    .beginProductCheck("wow", NOW, NONE)
                    .ordinal();
              });
      var region =
          workers.submit(
              () -> {
                gate.await();
                return new SqliteBuildLibraryRepository(c)
                    .beginCheck(new BuildLibraryScope("wow", "us"), NOW, NONE)
                    .ordinal();
              });
      gate.countDown();
      assertEquals(
          Set.of(1L, 2L),
          Set.of(
              product.get(10, java.util.concurrent.TimeUnit.SECONDS),
              region.get(10, java.util.concurrent.TimeUnit.SECONDS)));
    }
    try (var db = c.connect();
        var q = db.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () -> q.executeUpdate("UPDATE build_library_admission_counter SET ordinal=0"));
    }
  }
}
