package io.vibeminer.infrastructure.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDiscoveryHistoryRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final DiscoveryHistoryLimits LIMITS = DiscoveryHistoryLimits.defaults();
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  static final String HEADER =
      "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!STRING:0|ProductConfig!HEX:16|Future!STRING:0\n";
  static final String A = "a".repeat(32), B = "b".repeat(32);

  SqliteCatalog catalog() {
    return new SqliteCatalog(directory.resolve("catalog.sqlite"));
  }

  static DiscoveryEvent event(UUID id, Instant at, String config, int rows) {
    var versions = new StringBuilder(HEADER);
    for (int i = 0; i < rows; i++)
      versions
          .append("us|")
          .append(config)
          .append('|')
          .append(B)
          .append("||100|12.1.test||unknown\n");
    String base = "https://us.version.battle.net/v2/products/wow/";
    return new DiscoveryObservationMapper()
        .map(
            id,
            "wow",
            "us",
            at,
            new SourceDocument(
                base + "versions",
                at.minusSeconds(2),
                versions.toString().getBytes(StandardCharsets.UTF_8)),
            new SourceDocument(
                base + "cdns",
                at.minusSeconds(1),
                ("Name!STRING:0|Path!STRING:0|Hosts!STRING:0|ConfigPath!STRING:0\n"
                        + "us|tpr/wow|cdn.example.invalid|unsupported-but-observed\n")
                    .getBytes(StandardCharsets.UTF_8)));
  }

  static DiscoveryEvent event(String config) {
    return event(UUID.randomUUID(), NOW, config, 1);
  }

  @Test
  void persistsAbaEventsButOnlyTwoCanonicalSnapshotsAndPreservesFirstProof() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var a = event(A);
    var b = event(B);
    var again = event(UUID.randomUUID(), NOW.plusSeconds(30), A, 1);
    assertEquals(a, repo.append(a, LIMITS, NONE));
    repo.append(b, LIMITS, NONE);
    repo.append(again, LIMITS, NONE);
    assertEquals(3, count(c, "discovery_history_events"));
    assertEquals(2, count(c, "snapshots"));
    assertEquals(
        again,
        new SqliteDiscoveryHistoryRepository(catalog()).read(again.eventId(), NONE).orElseThrow());
    try (var db = c.connect()) {
      var first = c.readSnapshot(db, a.builds().getFirst().build().snapshotId()).orElseThrow();
      assertEquals(a.completedAt(), first.observedAt());
      assertEquals(a.versions().source(), first.evidence().documents().getFirst());
    }
    var stored = repo.read(again.eventId(), NONE).orElseThrow();
    assertEquals(again.versions().source().capturedAt(), stored.versions().source().capturedAt());
  }

  @Test
  void uuidIdempotenceAndNewCaptureIdentityAreIndependentOfBodyDedup() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var first = event(A);
    repo.append(first, LIMITS, NONE);
    assertEquals(first, repo.append(first, new DiscoveryHistoryLimits(1, 1, 1, 1), NONE));
    var duplicate = event(UUID.randomUUID(), NOW, A, 1);
    repo.append(duplicate, LIMITS, NONE);
    assertEquals(2, count(c, "source_blobs"));
    assertEquals(2, count(c, "discovery_history_events"));
    var changed = event(first.eventId(), NOW.plusSeconds(1), A, 1);
    assertEquals(
        DiscoveryHistoryException.Code.IMMUTABLE_EVENT_CONFLICT,
        assertThrows(DiscoveryHistoryException.class, () -> repo.append(changed, LIMITS, NONE))
            .code());
  }

  @Test
  void eventDetailSharesTwoSourceInstancesAcross64RepeatedSnapshotRefs() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var input = event(UUID.randomUUID(), NOW, A, 64);
    repo.append(input, LIMITS, NONE);
    var detail = repo.read(input.eventId(), NONE).orElseThrow();
    assertEquals(64, detail.builds().size());
    assertEquals(1, count(c, "snapshots"));
    for (var row : detail.builds()) {
      assertSame(detail.versions().source(), row.build().evidence().documents().get(0));
      assertSame(detail.cdns().source(), row.build().evidence().documents().get(1));
    }
    assertEquals(2, count(c, "discovery_history_documents"));
  }

  @Test
  void quotaFailureAndHalfwaySqlFailureRollBackBodiesSnapshotsAndEvents() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var input = event(A);
    assertEquals(
        DiscoveryHistoryException.Code.QUOTA_EXCEEDED,
        assertThrows(
                DiscoveryHistoryException.class,
                () -> repo.append(input, new DiscoveryHistoryLimits(1, 1, 1, 1), NONE))
            .code());
    assertEquals(0, count(c, "source_blobs"));
    assertEquals(0, count(c, "snapshots"));
    try (var db = c.connect();
        var q = db.createStatement()) {
      q.execute(
          "CREATE TRIGGER refuse_history BEFORE INSERT ON discovery_history_documents BEGIN SELECT"
              + " RAISE(ABORT,'authored failure');END");
    }
    assertThrows(DiscoveryHistoryException.class, () -> repo.append(input, LIMITS, NONE));
    for (var table :
        List.of(
            "source_blobs",
            "snapshots",
            "discovery_history_events",
            "discovery_history_documents",
            "discovery_history_builds")) assertEquals(0, count(c, table));
  }

  @Test
  void zeroNewBodyEventAllowedOverByteQuotaButPositiveAdditionRejected() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var first = event(A);
    repo.append(first, LIMITS, NONE);
    repo.append(
        event(UUID.randomUUID(), NOW.plusSeconds(1), A, 1),
        new DiscoveryHistoryLimits(10, 10, 1, 64),
        NONE);
    assertEquals(
        DiscoveryHistoryException.Code.QUOTA_EXCEEDED,
        assertThrows(
                DiscoveryHistoryException.class,
                () -> repo.append(event(B), new DiscoveryHistoryLimits(10, 10, 1, 64), NONE))
            .code());
    assertEquals(2, count(c, "discovery_history_events"));
  }

  @Test
  void stablePagesIgnoreNewerAppendsAndValidateExactCursorContext() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    for (int i = 0; i < 3; i++)
      repo.append(event(UUID.randomUUID(), NOW.plusSeconds(i), A, 1), LIMITS, NONE);
    var filter = new DiscoveryHistoryFilter("wow", Optional.of("us"), Optional.empty());
    var first = repo.page(filter, Optional.empty(), 1, NONE);
    assertEquals(1, first.events().size());
    repo.append(event(UUID.randomUUID(), NOW.minusSeconds(100), A, 1), LIMITS, NONE);
    var second = repo.page(filter, first.nextCursor(), 1, NONE);
    assertTrue(second.events().getFirst().sequence() < first.events().getFirst().sequence());
    assertEquals(
        DiscoveryHistoryException.Code.INVALID_REQUEST,
        assertThrows(
                DiscoveryHistoryException.class,
                () -> repo.page(filter, first.nextCursor(), 2, NONE))
            .code());
    assertEquals(
        DiscoveryHistoryException.Code.INVALID_REQUEST,
        assertThrows(
                DiscoveryHistoryException.class,
                () ->
                    repo.page(
                        new DiscoveryHistoryFilter("wowf", Optional.empty(), Optional.empty()),
                        first.nextCursor(),
                        1,
                        NONE))
            .code());
  }

  @Test
  void sourceBodyAndMissingReferenceCorruptionNeverProducePartialDetail() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var input = event(A);
    repo.append(input, LIMITS, NONE);
    try (var db = c.connect();
        var q = db.prepareStatement("UPDATE source_blobs SET bytes=? WHERE digest=?")) {
      byte[] bad = input.versions().source().bytes();
      bad[bad.length - 2] ^= 1;
      q.setBytes(1, bad);
      q.setString(2, input.versions().source().sha256());
      q.executeUpdate();
    }
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(DiscoveryHistoryException.class, () -> repo.read(input.eventId(), NONE))
            .code());
    try (var db = c.connect();
        var q = db.prepareStatement("UPDATE source_blobs SET bytes=? WHERE digest=?")) {
      q.setBytes(1, input.versions().source().bytes());
      q.setString(2, input.versions().source().sha256());
      q.executeUpdate();
    }
    try (var db = c.connect();
        var q = db.createStatement()) {
      q.executeUpdate("DELETE FROM discovery_history_builds");
    }
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(DiscoveryHistoryException.class, () -> repo.read(input.eventId(), NONE))
            .code());
  }

  @Test
  void concurrencyCannotOvershootQuotaAndPreCancelledAppendWritesNothing() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    assertThrows(CancellationException.class, () -> repo.append(event(A), LIMITS, () -> true));
    assertEquals(0, count(c, "discovery_history_events"));
    var gate = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var results = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        results.add(
            workers.submit(
                () -> {
                  gate.await();
                  try {
                    new SqliteDiscoveryHistoryRepository(catalog())
                        .append(event(A), new DiscoveryHistoryLimits(1, 10, 100000, 64), NONE);
                    return true;
                  } catch (DiscoveryHistoryException error) {
                    assertEquals(DiscoveryHistoryException.Code.QUOTA_EXCEEDED, error.code());
                    return false;
                  }
                }));
      gate.countDown();
      int successes = 0;
      for (var result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
    }
    assertEquals(1, count(c, "discovery_history_events"));
  }

  @Test
  void lateCancellationRollsBackNewBodiesSnapshotsAndCompleteEvent() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(
        CancellationException.class,
        () -> repo.append(event(A), LIMITS, () -> checks.incrementAndGet() >= 4));
    assertEquals(4, checks.get());
    for (var table :
        List.of(
            "source_blobs",
            "snapshots",
            "discovery_history_events",
            "discovery_history_documents",
            "discovery_history_builds")) assertEquals(0, count(c, table));
  }

  @Test
  void rawInterpretationTamperAndUnsupportedRevisionFailBeforeAnyPublication() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var input = event(A);
    var row = input.builds().getFirst();
    var values = new HashMap<>(row.versionsValues());
    values.put("Future", "forged");
    var forged =
        new DiscoveryEvent(
            input.eventId(),
            input.productId(),
            input.region(),
            input.completedAt(),
            input.parserRevision(),
            input.versions(),
            input.cdns(),
            input.selectedCdn(),
            List.of(
                new DiscoveryEvent.ObservedBuild(row.build(), row.versionsRowOrdinal(), values)));
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(DiscoveryHistoryException.class, () -> repo.append(forged, LIMITS, NONE))
            .code());
    var future =
        new DiscoveryEvent(
            input.eventId(),
            input.productId(),
            input.region(),
            input.completedAt(),
            "future-parser-v999",
            input.versions(),
            input.cdns(),
            input.selectedCdn(),
            input.builds());
    assertEquals(
        DiscoveryHistoryException.Code.UNSUPPORTED_INTERPRETATION,
        assertThrows(DiscoveryHistoryException.class, () -> repo.append(future, LIMITS, NONE))
            .code());
    assertEquals(0, count(c, "source_blobs"));
    repo.append(input, LIMITS, NONE);
    byte[] record = DiscoveryHistoryRecords.event(forged);
    String hash =
        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(record));
    try (var db = c.connect();
        var q =
            db.prepareStatement("UPDATE discovery_history_events SET record=?,record_sha256=?")) {
      q.setBytes(1, record);
      q.setString(2, hash);
      q.executeUpdate();
    }
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(DiscoveryHistoryException.class, () -> repo.read(input.eventId(), NONE))
            .code());
  }

  @Test
  void identicalBodiesAcrossRolesStoredOnceButIndependentReceiptsRemainDistinct() throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    String text =
        HEADER.stripTrailing()
            + "|Name!STRING:0|Path!STRING:0|Hosts!STRING:0\nus|"
            + A
            + "|"
            + B
            + "||100|12.1.test||unknown|us|tpr/wow|cdn.example.invalid\n";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    String base = "https://us.version.battle.net/v2/products/wow/";
    var input =
        new DiscoveryObservationMapper()
            .map(
                UUID.randomUUID(),
                "wow",
                "us",
                NOW,
                new SourceDocument(base + "versions", NOW.minusSeconds(2), bytes),
                new SourceDocument(base + "cdns", NOW.minusSeconds(1), bytes));
    repo.append(input, LIMITS, NONE);
    assertEquals(1, count(c, "source_blobs"));
    assertEquals(2, count(c, "discovery_history_documents"));
    var detail = repo.read(input.eventId(), NONE).orElseThrow();
    assertNotSame(detail.versions().source(), detail.cdns().source());
    assertNotEquals(detail.versions().source().capturedAt(), detail.cdns().source().capturedAt());
  }

  @Test
  void historyDoesNotRewritePreexistingWorkspaceProvenanceOrCreateAvailability() throws Exception {
    var c = catalog();
    var old = event(A);
    var workspaces = new io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository(c);
    var saved =
        workspaces.save(
            Workspace.create(old.builds().getFirst().build(), "enUS", "Original workspace", NOW));
    var observed = event(UUID.randomUUID(), NOW.plusSeconds(90), A, 1);
    var repo = new SqliteDiscoveryHistoryRepository(c);
    repo.append(observed, LIMITS, NONE);
    assertEquals(saved, workspaces.find(saved.id()).orElseThrow());
    var detail = repo.read(observed.eventId(), NONE).orElseThrow();
    assertEquals(observed, detail);
    assertNotEquals(saved.build().observedAt(), detail.builds().getFirst().build().observedAt());
    assertEquals(0, count(c, "source_aliases"));
    assertEquals(0, count(c, "build_object_memberships"));
    assertEquals(0, count(c, "retention_scopes"));
    assertTrue(repo.read(UUID.randomUUID(), NONE).isEmpty());
    var empty =
        repo.page(
            new DiscoveryHistoryFilter("wowf", Optional.empty(), Optional.empty()),
            Optional.empty(),
            25,
            NONE);
    assertTrue(empty.events().isEmpty());
    assertTrue(empty.nextCursor().isEmpty());
  }

  @Test
  void invalidCanonicalObservationTimestampIsTypedCorruptionNotUncheckedParserFailure()
      throws Exception {
    var c = catalog();
    var repo = new SqliteDiscoveryHistoryRepository(c);
    var input = event(A);
    repo.append(input, LIMITS, NONE);
    try (var db = c.connect();
        var q = db.createStatement()) {
      q.executeUpdate("UPDATE snapshots SET observed_at='not-an-instant'");
    }
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(DiscoveryHistoryException.class, () -> repo.read(input.eventId(), NONE))
            .code());
  }

  static long count(SqliteCatalog catalog, String table) throws SQLException {
    try (var c = catalog.connect();
        var q = c.createStatement();
        var r = q.executeQuery("SELECT count(*) FROM " + table)) {
      return r.getLong(1);
    }
  }
}
