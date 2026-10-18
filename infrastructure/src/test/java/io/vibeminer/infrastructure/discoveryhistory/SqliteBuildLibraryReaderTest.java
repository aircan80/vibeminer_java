package io.vibeminer.infrastructure.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
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

class SqliteBuildLibraryReaderTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = SqliteDiscoveryHistoryRepositoryTest.NOW;

  SqliteCatalog catalog() {
    return new SqliteCatalog(directory.resolve("catalog.sqlite"));
  }

  DiscoveryEvent save(String key, String future) {
    var initial = SqliteDiscoveryHistoryRepositoryTest.event(key);
    return saveChanged(initial, "us", future);
  }

  DiscoveryEvent saveChanged(DiscoveryEvent initial, String region, String future) {
    String base = "https://us.version.battle.net/v2/products/wow/";
    String versions =
        new String(initial.versions().source().bytes(), StandardCharsets.UTF_8)
            .replace("\nus|", "\n" + region + "|")
            .replace("|unknown\n", "|" + future + "\n");
    String cdns =
        new String(initial.cdns().source().bytes(), StandardCharsets.UTF_8)
            .replace("\nus|", "\n" + region + "|");
    var event =
        new DiscoveryObservationMapper()
            .map(
                UUID.randomUUID(),
                "wow",
                region,
                NOW,
                new SourceDocument(
                    base + "versions", NOW, versions.getBytes(StandardCharsets.UTF_8)),
                new SourceDocument(base + "cdns", NOW, cdns.getBytes(StandardCharsets.UTF_8)));
    return new SqliteDiscoveryHistoryRepository(catalog())
        .append(event, DiscoveryHistoryLimits.defaults(), NONE);
  }

  BuildLibraryRegistration observe(DiscoveryEvent event) {
    return new SqliteBuildLibraryRepository(catalog())
        .recordObservation(
            event.eventId(),
            BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
            Optional.empty(),
            NOW,
            NONE);
  }

  void prepare(DiscoveryEvent event) {
    var requirements =
        List.of(
            new ScopeRequirement(
                "BUILD_CONFIG", Optional.empty(), OptionalLong.empty(), OptionalLong.empty()),
            new ScopeRequirement(
                "ROOT_MANIFEST", Optional.empty(), OptionalLong.empty(), OptionalLong.empty()));
    new SqliteRetentionRepository(catalog())
        .pin(
            new RetentionScope(
                event.builds().getFirst().build().snapshotId(),
                ScopeKind.ROOT_METADATA,
                requirements),
            NONE);
  }

  BuildLibraryQuery query(BuildLibraryProjection.Filter filter) {
    return new BuildLibraryQuery("wow", filter);
  }

  @Test
  void fullCatalogLiteralSearchAndRegionPrecedePaging() throws Exception {
    var older = observe(save("a".repeat(32), "older"));
    for (int n = 0; n < 30; n++) {
      var key = String.format("%032x", n + 1);
      var source = SqliteDiscoveryHistoryRepositoryTest.event(key);
      String versions =
          new String(source.versions().source().bytes(), StandardCharsets.UTF_8)
              .replace("12.1.test", "different-" + n)
              .replace("|100|", "|" + (200 + n) + "|");
      var event =
          new DiscoveryObservationMapper()
              .map(
                  UUID.randomUUID(),
                  "wow",
                  "eu",
                  NOW,
                  new SourceDocument(
                      source.versions().source().sourceUrl(),
                      NOW,
                      versions.replace("\nus|", "\neu|").getBytes(StandardCharsets.UTF_8)),
                  new SourceDocument(
                      source.cdns().source().sourceUrl(),
                      NOW,
                      new String(source.cdns().source().bytes(), StandardCharsets.UTF_8)
                          .replace("\nus|", "\neu|")
                          .getBytes(StandardCharsets.UTF_8)));
      observe(
          new SqliteDiscoveryHistoryRepository(catalog())
              .append(event, DiscoveryHistoryLimits.defaults(), NONE));
    }
    var reader = new SqliteBuildLibraryReader(catalog());
    var query =
        new BuildLibraryQuery(
            "wow", BuildLibraryProjection.Filter.ALL, Optional.of("12.1.test"), Optional.of("us"));
    var page = reader.page(query, Optional.empty(), 1, NONE);
    assertEquals(
        older.references().getFirst().key(), page.entries().getFirst().row().group().key());
    assertTrue(page.nextCursor().isEmpty());
    var global =
        new BuildLibraryQuery(
            "wow", BuildLibraryProjection.Filter.ALL, Optional.of("12.1.test"), Optional.empty());
    assertEquals(
        older.references().getFirst().key(),
        reader.page(global, Optional.empty(), 1, NONE).entries().getFirst().row().group().key());
    var newest = observe(save("f".repeat(32), "new-matching"));
    var firstMatch = reader.page(global, Optional.empty(), 1, NONE);
    assertEquals(
        newest.references().getFirst().key(), firstMatch.entries().getFirst().row().group().key());
    var secondMatch = reader.page(global, firstMatch.nextCursor(), 1, NONE);
    assertEquals(
        older.references().getFirst().key(), secondMatch.entries().getFirst().row().group().key());
    assertTrue(secondMatch.nextCursor().isEmpty());
    var byId =
        new BuildLibraryQuery(
            "wow", BuildLibraryProjection.Filter.ALL, Optional.of("100"), Optional.of("us"));
    assertEquals(2, reader.page(byId, Optional.empty(), 25, NONE).entries().size());
    for (String literal : List.of("%", "_", "' OR 1=1 --")) {
      assertTrue(
          reader
              .page(
                  new BuildLibraryQuery(
                      "wow",
                      BuildLibraryProjection.Filter.ALL,
                      Optional.of(literal),
                      Optional.empty()),
                  Optional.empty(),
                  25,
                  NONE)
              .entries()
              .isEmpty());
    }
    assertTrue(
        reader
            .page(
                new BuildLibraryQuery(
                    "wow",
                    BuildLibraryProjection.Filter.ALL,
                    Optional.of("12.1.test"),
                    Optional.of("eu")),
                Optional.empty(),
                25,
                NONE)
            .entries()
            .isEmpty());
    assertEquals(
        BuildLibraryException.Code.LIMIT_EXCEEDED,
        assertThrows(
                BuildLibraryException.class,
                () ->
                    new SqliteBuildLibraryReader(catalog(), 1)
                        .page(query, Optional.empty(), 1, NONE))
            .code());
  }

  @Test
  void selectedRegionCurrentRequiresThatRegionsSuccessfulCheck() {
    var repo = new SqliteBuildLibraryRepository(catalog());
    var euHistorical =
        saveChanged(SqliteDiscoveryHistoryRepositoryTest.event("a".repeat(32)), "eu", "unknown");
    var ref = observe(euHistorical).references().getFirst();
    var us = save("a".repeat(32), "unknown");
    var usTicket = repo.beginCheck(new BuildLibraryScope("wow", "us"), NOW, NONE);
    repo.recordObservation(
        us.eventId(),
        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
        Optional.of(usTicket),
        NOW,
        NONE);
    var reader = new SqliteBuildLibraryReader(catalog());
    var euQuery =
        new BuildLibraryQuery(
            "wow",
            BuildLibraryProjection.Filter.CURRENTLY_ADVERTISED,
            Optional.empty(),
            Optional.of("eu"));
    assertTrue(reader.page(euQuery, Optional.empty(), 25, NONE).entries().isEmpty());
    assertEquals(
        BuildLibraryProjection.Advertisement.UNKNOWN,
        reader.select(ref.key().identity(), NONE).row().regionalAdvertisements().get("eu"));
    var fresh =
        saveChanged(SqliteDiscoveryHistoryRepositoryTest.event("a".repeat(32)), "eu", "unknown");
    var ticket = repo.beginCheck(new BuildLibraryScope("wow", "eu"), NOW, NONE);
    repo.recordObservation(
        fresh.eventId(),
        BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
        Optional.of(ticket),
        NOW,
        NONE);
    assertEquals(1, reader.page(euQuery, Optional.empty(), 25, NONE).entries().size());
    var failed = repo.beginCheck(new BuildLibraryScope("wow", "eu"), NOW, NONE);
    repo.recordFailedScope(failed, BuildLibraryRepository.Failure.FAILED, NOW, NONE);
    assertTrue(reader.page(euQuery, Optional.empty(), 25, NONE).entries().isEmpty());
    var selected = reader.select(ref.key().identity(), NONE);
    assertEquals(
        BuildLibraryProjection.Advertisement.PRESENT_IN_CHECKED_SCOPE,
        selected.row().advertisement());
    assertFalse(euQuery.matches(selected.row()));
  }

  @Test
  void catalogSearchSqlAndElapsedLimitsNeverBecomeEmptySuccess() {
    observe(save("a".repeat(32), "unknown"));
    var query =
        new BuildLibraryQuery(
            "wow", BuildLibraryProjection.Filter.ALL, Optional.of("not-found"), Optional.empty());
    assertEquals(
        BuildLibraryException.Code.LIMIT_EXCEEDED,
        assertThrows(
                BuildLibraryException.class,
                () ->
                    new SqliteBuildLibraryReader(catalog(), 1L << 20, 1, System::nanoTime)
                        .page(query, Optional.empty(), 25, NONE))
            .code());
    var ticks = new java.util.concurrent.atomic.AtomicLong();
    assertEquals(
        BuildLibraryException.Code.LIMIT_EXCEEDED,
        assertThrows(
                BuildLibraryException.class,
                () ->
                    new SqliteBuildLibraryReader(
                            catalog(),
                            1L << 20,
                            20_000_000,
                            () -> ticks.getAndAdd(java.time.Duration.ofSeconds(31).toNanos()))
                        .page(query, Optional.empty(), 25, NONE))
            .code());
  }

  @Test
  void localFilterPrecedesPagingAndRawHistoryAloneIsNeverLocal() throws Exception {
    var a = save("a".repeat(32), "unknown");
    var aRef = observe(a).references().getFirst();
    var b = save("b".repeat(32), "unknown");
    observe(b);
    var reader = new SqliteBuildLibraryReader(catalog());
    assertTrue(
        reader
            .page(query(BuildLibraryProjection.Filter.LOCAL), Optional.empty(), 1, NONE)
            .entries()
            .isEmpty());
    prepare(a);
    var local = reader.page(query(BuildLibraryProjection.Filter.LOCAL), Optional.empty(), 1, NONE);
    assertEquals(aRef.key().identity(), local.entries().getFirst().row().group().key().identity());
    assertTrue(local.entries().getFirst().row().local().hasBuildPreparationContext());
    assertTrue(local.nextCursor().isEmpty());
  }

  @Test
  void currentAdvertisementAndUnknownAreScopedIndependentOfLocal() throws Exception {
    var repo = new SqliteBuildLibraryRepository(catalog());
    var scope = new BuildLibraryScope("wow", "us");
    var a = save("a".repeat(32), "unknown");
    var first = repo.beginCheck(scope, NOW, NONE);
    repo.recordObservation(
        a.eventId(), BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK, Optional.of(first), NOW, NONE);
    var b = save("b".repeat(32), "unknown");
    var second = repo.beginCheck(scope, NOW, NONE);
    var bRef =
        repo.recordObservation(
                b.eventId(),
                BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                Optional.of(second),
                NOW,
                NONE)
            .references()
            .getFirst();
    var reader = new SqliteBuildLibraryReader(catalog());
    var advertised =
        reader.page(
            query(BuildLibraryProjection.Filter.CURRENTLY_ADVERTISED), Optional.empty(), 25, NONE);
    assertEquals(1, advertised.entries().size());
    assertEquals(
        bRef.key().identity(), advertised.entries().getFirst().row().group().key().identity());
    var failed = repo.beginCheck(scope, NOW, NONE);
    repo.recordFailedScope(failed, BuildLibraryRepository.Failure.FAILED, NOW, NONE);
    assertTrue(
        reader
            .page(
                query(BuildLibraryProjection.Filter.CURRENTLY_ADVERTISED),
                Optional.empty(),
                25,
                NONE)
            .entries()
            .isEmpty());
    assertEquals(
        BuildLibraryProjection.Advertisement.UNKNOWN,
        reader.select(bRef.key().identity(), NONE).row().advertisement());
  }

  @Test
  void localAndAcknowledgmentMutationsInvalidateCursorButRestartDoesNot() throws Exception {
    var a = save("a".repeat(32), "unknown");
    var aRef = observe(a).references().getFirst();
    observe(save("b".repeat(32), "unknown"));
    var reader = new SqliteBuildLibraryReader(catalog());
    var query = query(BuildLibraryProjection.Filter.ALL);
    var first = reader.page(query, Optional.empty(), 1, NONE);
    assertEquals(
        aRef.key().identity(),
        new SqliteBuildLibraryReader(catalog())
            .page(query, first.nextCursor(), 1, NONE)
            .entries()
            .getFirst()
            .row()
            .group()
            .key()
            .identity());
    prepare(a);
    assertEquals(
        BuildLibraryException.Code.STALE_CURSOR,
        assertThrows(
                BuildLibraryException.class, () -> reader.page(query, first.nextCursor(), 1, NONE))
            .code());
    var fresh = reader.page(query, Optional.empty(), 1, NONE);
    new SqliteBuildLibraryRepository(catalog()).acknowledgeOpen(aRef, NOW, NONE);
    assertEquals(
        BuildLibraryException.Code.STALE_CURSOR,
        assertThrows(
                BuildLibraryException.class, () -> reader.page(query, fresh.nextCursor(), 1, NONE))
            .code());
  }

  @Test
  void unknownReferenceSplitCannotInheritLegacySnapshotLocalPresence() throws Exception {
    var a = save("a".repeat(32), "first");
    var aRef = observe(a).references().getFirst();
    prepare(a);
    var b = save("a".repeat(32), "different");
    var bRef = observe(b).references().getFirst();
    assertEquals(aRef.snapshotId(), bRef.snapshotId());
    assertNotEquals(aRef.key(), bRef.key());
    var reader = new SqliteBuildLibraryReader(catalog());
    assertTrue(reader.select(aRef.key().identity(), NONE).row().local().isLocal());
    assertFalse(reader.select(bRef.key().identity(), NONE).row().local().isLocal());
    assertEquals(
        1,
        reader
            .page(query(BuildLibraryProjection.Filter.LOCAL), Optional.empty(), 100, NONE)
            .entries()
            .size());
  }

  @Test
  void ambiguousOriginalDocumentNeverEstablishesEitherGroupAsLocal() throws Exception {
    var initial = SqliteDiscoveryHistoryRepositoryTest.event("a".repeat(32));
    String raw = new String(initial.versions().source().bytes(), StandardCharsets.UTF_8);
    String row = raw.substring(raw.indexOf('\n') + 1);
    raw += row.replace("|unknown\n", "|different\n");
    var source =
        new SourceDocument(
            initial.versions().source().sourceUrl(), NOW, raw.getBytes(StandardCharsets.UTF_8));
    var event =
        new DiscoveryObservationMapper()
            .map(UUID.randomUUID(), "wow", "us", NOW, source, initial.cdns().source());
    new SqliteDiscoveryHistoryRepository(catalog())
        .append(event, DiscoveryHistoryLimits.defaults(), NONE);
    var registration = observe(event);
    prepare(event);
    var reader = new SqliteBuildLibraryReader(catalog());
    for (var ref : registration.references())
      assertFalse(reader.select(ref.key().identity(), NONE).row().local().isLocal());
    assertTrue(
        reader
            .page(query(BuildLibraryProjection.Filter.LOCAL), Optional.empty(), 100, NONE)
            .entries()
            .isEmpty());
  }

  @Test
  void bodyFreeProjectionSurvivesBodyDamageButExactOpenAcknowledgmentReplaysAndFails()
      throws Exception {
    var event = save("a".repeat(32), "unknown");
    var ref = observe(event).references().getFirst();
    try (var c = catalog().connect();
        var q = c.prepareStatement("UPDATE source_blobs SET bytes=? WHERE digest=?")) {
      var bad = event.versions().source().bytes();
      bad[bad.length - 2] ^= 1;
      q.setBytes(1, bad);
      q.setString(2, event.versions().source().sha256());
      q.executeUpdate();
    }
    var reader = new SqliteBuildLibraryReader(catalog());
    assertEquals(ref, reader.select(ref.key().identity(), NONE).row().group().preferredReference());
    assertEquals(
        1,
        reader
            .page(query(BuildLibraryProjection.Filter.ALL), Optional.empty(), 25, NONE)
            .entries()
            .size());
    assertEquals(
        BuildLibraryException.Code.CORRUPT_LIBRARY,
        assertThrows(
                BuildLibraryException.class,
                () -> new SqliteBuildLibraryRepository(catalog()).acknowledgeOpen(ref, NOW, NONE))
            .code());
  }

  @Test
  void metadataBudgetRefusesWithoutTruncationAndRegionalPreferencePreservesLatestFacts()
      throws Exception {
    var us = save("a".repeat(32), "unknown");
    var ref = observe(us).references().getFirst();
    var eu = saveChanged(us, "eu", "unknown");
    observe(eu);
    var duplicate =
        SqliteDiscoveryHistoryRepositoryTest.event(UUID.randomUUID(), NOW, "a".repeat(32), 2);
    var later = saveChanged(duplicate, "us", "unknown");
    observe(later);
    var entry = new SqliteBuildLibraryReader(catalog()).select(ref.key().identity(), NONE);
    assertEquals(3, entry.row().group().references().size());
    assertEquals(later.eventId(), entry.row().group().preferredReference().eventId());
    assertEquals("us", entry.row().group().preferredReference().region());
    assertEquals(
        BuildLibraryException.Code.LIMIT_EXCEEDED,
        assertThrows(
                BuildLibraryException.class,
                () ->
                    new SqliteBuildLibraryReader(catalog(), 1)
                        .page(query(BuildLibraryProjection.Filter.ALL), Optional.empty(), 25, NONE))
            .code());
  }

  @Test
  void regionalLimitFailsRatherThanDroppingObservedRegions() throws Exception {
    var original = save("a".repeat(32), "unknown");
    var ref = observe(original).references().getFirst();
    for (int i = 0; i < 64; i++) {
      String region = "" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
      observe(saveChanged(original, region, "unknown"));
    }
    assertEquals(
        BuildLibraryException.Code.LIMIT_EXCEEDED,
        assertThrows(
                BuildLibraryException.class,
                () -> new SqliteBuildLibraryReader(catalog()).select(ref.key().identity(), NONE))
            .code());
    assertEquals(
        65, SqliteDiscoveryHistoryRepositoryTest.count(catalog(), "build_library_registrations"));
  }

  @Test
  void keyRingSplitWithSameLegacySnapshotNeverInheritsAnotherGroupsLocalReference()
      throws Exception {
    var base = SqliteDiscoveryHistoryRepositoryTest.event("a".repeat(32));
    var refs = new ArrayList<BuildLibraryReference>();
    var events = new ArrayList<DiscoveryEvent>();
    for (String ring : List.of("c".repeat(32), "d".repeat(32))) {
      String raw =
          new String(base.versions().source().bytes(), StandardCharsets.UTF_8)
              .replace(
                  "|" + "b".repeat(32) + "||100|", "|" + "b".repeat(32) + "|" + ring + "|100|");
      var event =
          new DiscoveryObservationMapper()
              .map(
                  UUID.randomUUID(),
                  "wow",
                  "us",
                  NOW,
                  new SourceDocument(
                      base.versions().source().sourceUrl(),
                      NOW,
                      raw.getBytes(StandardCharsets.UTF_8)),
                  base.cdns().source());
      new SqliteDiscoveryHistoryRepository(catalog())
          .append(event, DiscoveryHistoryLimits.defaults(), NONE);
      refs.add(observe(event).references().getFirst());
      events.add(event);
    }
    assertEquals(refs.get(0).snapshotId(), refs.get(1).snapshotId());
    assertNotEquals(refs.get(0).key().identity(), refs.get(1).key().identity());
    prepare(events.getFirst());
    var page =
        new SqliteBuildLibraryReader(catalog())
            .page(query(BuildLibraryProjection.Filter.LOCAL), Optional.empty(), 25, NONE);
    assertEquals(1, page.entries().size());
    assertEquals(
        refs.getFirst().key().identity(), page.entries().getFirst().row().group().key().identity());
  }
}
