package io.vibeminer.application.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiscoveryHistoryContractsTest {
  static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
  static final String BUILD_CONFIG = "a".repeat(32), CDN_CONFIG = "b".repeat(32);

  static DiscoveryEvent event(UUID id, Instant observed) {
    var versions =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/versions",
            observed,
            "captured versions fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var cdns =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/cdns",
            observed.minusSeconds(1),
            "captured cdns fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var build =
        new BuildSnapshot(
            "wow",
            "Edition unresolved",
            "Published",
            "us",
            "1.2.3",
            BUILD_CONFIG,
            CDN_CONFIG,
            observed,
            false,
            new BuildEvidence("00042", "", List.of(versions, cdns)));
    var values = new LinkedHashMap<String, String>();
    values.put("Region", "us");
    values.put("BuildConfig", BUILD_CONFIG.toUpperCase(Locale.ROOT));
    values.put("CDNConfig", CDN_CONFIG);
    values.put("VersionsName", "1.2.3");
    values.put("BuildId", "00042");
    values.put("Unknown", "");
    return new DiscoveryEvent(
        id,
        "wow",
        "us",
        observed,
        "fixture-interpretation-v1",
        new DiscoveryEvent.ParsedSource(
            versions, List.copyOf(values.keySet()), Optional.of("0007")),
        new DiscoveryEvent.ParsedSource(
            cdns, List.of("Name", "Path", "Hosts", "Unknown"), Optional.empty()),
        new DiscoveryEvent.CdnRow(
            1, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "example.invalid", "Unknown", "")),
        List.of(new DiscoveryEvent.ObservedBuild(build, 2, values)));
  }

  @Test
  void retrievalIdentityDoesNotReplaceSnapshotOrRawInterpretation() {
    var first = event(UUID.randomUUID(), NOW);
    var again = event(UUID.randomUUID(), NOW.plusSeconds(5));
    assertNotEquals(first.eventId(), again.eventId());
    assertEquals(
        first.builds().getFirst().build().snapshotId(),
        again.builds().getFirst().build().snapshotId());
    assertEquals("0007", first.versions().sequence().orElseThrow());
    assertEquals(
        BUILD_CONFIG.toUpperCase(Locale.ROOT),
        first.builds().getFirst().versionsValues().get("BuildConfig"));
    assertEquals("", first.builds().getFirst().versionsValues().get("Unknown"));
    assertSame(
        first.versions().source(),
        first.builds().getFirst().build().evidence().documents().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> first.builds().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> first.selectedCdn().values().put("x", "y"));
  }

  @Test
  void eventRejectsMismatchedRowsAndKeepsDuplicateSnapshotOrdinals() {
    var e = event(UUID.randomUUID(), NOW);
    var row = e.builds().getFirst();
    var duplicate = new DiscoveryEvent.ObservedBuild(row.build(), 3, row.versionsValues());
    var both =
        new DiscoveryEvent(
            e.eventId(),
            e.productId(),
            e.region(),
            e.completedAt(),
            e.parserRevision(),
            e.versions(),
            e.cdns(),
            e.selectedCdn(),
            List.of(row, duplicate));
    assertEquals(2, both.builds().size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEvent(
                e.eventId(),
                "wow",
                "eu",
                e.completedAt(),
                e.parserRevision(),
                e.versions(),
                e.cdns(),
                e.selectedCdn(),
                e.builds()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                e.versions(),
                e.cdns(),
                e.selectedCdn(),
                List.of(row, row)));
  }

  @Test
  void limitsAndFilterCursorAreBoundedAndExact() {
    assertEquals(10000, DiscoveryHistoryLimits.defaults().maximumEvents());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryHistoryLimits(10001, 100000, 256L << 20, 64));
    var filter = new DiscoveryHistoryFilter("wow", Optional.of("us"), Optional.of("Published"));
    var other = new DiscoveryHistoryFilter("wow", Optional.of("eu"), Optional.of("Published"));
    var cursor = new DiscoveryHistoryCursor(filter.identity(), 10, 5, 25);
    cursor.requireContext(filter, 25);
    assertThrows(IllegalArgumentException.class, () -> cursor.requireContext(other, 25));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryHistoryCursor(filter.identity(), 5, 6, 25));
  }

  @Test
  void offlineSelectionRequiresBothIdentitiesAndActualRowWithoutAvailabilityClaim() {
    var e = event(UUID.randomUUID(), NOW);
    var row = e.builds().getFirst();
    var request =
        new DiscoveryObservationSelection(
            e.eventId(), row.build().snapshotId(), row.versionsRowOrdinal());
    var selected = new DiscoveryHistorySelection(request, e, row);
    assertEquals(
        DiscoveryHistorySelection.RemoteContentAvailability.NOT_CHECKED,
        selected.remoteContentAvailability());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryHistorySelection(
                new DiscoveryObservationSelection(e.eventId(), "f".repeat(64), 2), e, row));
  }

  @Test
  void sixtyFourReferencesShareExactlyTwoEventSourceInstancesAndRejectSixtyFive() {
    var e = event(UUID.randomUUID(), NOW);
    var base = e.builds().getFirst();
    var rows = new ArrayList<DiscoveryEvent.ObservedBuild>();
    for (int i = 0; i < 64; i++)
      rows.add(new DiscoveryEvent.ObservedBuild(base.build(), i, base.versionsValues()));
    var large =
        new DiscoveryEvent(
            e.eventId(),
            e.productId(),
            e.region(),
            e.completedAt(),
            e.parserRevision(),
            e.versions(),
            e.cdns(),
            e.selectedCdn(),
            rows);
    for (var row : large.builds()) {
      assertSame(large.versions().source(), row.build().evidence().documents().get(0));
      assertSame(large.cdns().source(), row.build().evidence().documents().get(1));
    }
    rows.add(new DiscoveryEvent.ObservedBuild(base.build(), 64, base.versionsValues()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                e.versions(),
                e.cdns(),
                e.selectedCdn(),
                rows));
  }

  @Test
  void parsedSourceAndRawFactsAreBoundedWithoutInventingParserAuthority() {
    var e = event(UUID.randomUUID(), NOW);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEvent.ParsedSource(
                e.versions().source(), List.of("Region", "Region"), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEvent.ParsedSource(
                e.versions().source(), List.of("Region"), Optional.of("+1")));
    var empty = new SourceDocument(e.versions().source().sourceUrl(), NOW, new byte[0]);
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryEvent.ParsedSource(empty, List.of("Region"), Optional.empty()));
    var changed = new LinkedHashMap<>(e.builds().getFirst().versionsValues());
    changed.put("Unknown", "x".repeat(16384));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryEvent.ObservedBuild(e.builds().getFirst().build(), 2, changed));
    // These authored bodies are not parsed by DTO construction; infrastructure must replay raw
    // bytes.
    assertEquals("fixture-interpretation-v1", e.parserRevision());
    assertEquals(NOW.minusSeconds(1), e.cdns().source().capturedAt());
  }

  @Test
  void summariesCheckSnapshotFactsAndPagesRejectQueryOrderOrBoundarySubstitution() {
    var e = event(UUID.randomUUID(), NOW);
    var summary = DiscoveryEventSummary.from(e, 10);
    var filter = new DiscoveryHistoryFilter("wow", Optional.of("us"), Optional.of("Published"));
    var cursor = new DiscoveryHistoryCursor(filter.identity(), 10, 10, 25);
    var page = new DiscoveryHistoryPage(filter, List.of(summary), Optional.of(cursor));
    assertEquals(
        e.builds().getFirst().build().snapshotId(),
        page.events().getFirst().builds().getFirst().snapshotId());
    assertEquals(e.versions().source().sha256(), summary.versions().sha256());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryHistoryPage(filter, List.of(summary, summary), Optional.empty()));
    var wrongBoundary = new DiscoveryHistoryCursor(filter.identity(), 10, 9, 25);
    assertThrows(
        IllegalArgumentException.class,
        () -> new DiscoveryHistoryPage(filter, List.of(summary), Optional.of(wrongBoundary)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryHistoryPage(
                new DiscoveryHistoryFilter("wow", Optional.of("eu"), Optional.empty()),
                List.of(summary),
                Optional.empty()));
    var ref = summary.builds().getFirst();
    var changed =
        new DiscoveryEventSummary.BuildReference(
            ref.snapshotId(),
            ref.versionsRowOrdinal(),
            ref.channel(),
            ref.versionLabel(),
            "f".repeat(32),
            ref.cdnConfig(),
            ref.upstreamBuildId(),
            ref.productConfig());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEventSummary(
                summary.eventId(),
                10,
                summary.productId(),
                summary.region(),
                summary.completedAt(),
                summary.parserRevision(),
                summary.versions(),
                summary.cdns(),
                List.of(changed)));
  }

  @Test
  void observationRoleAndInterpretationCannotBeReboundToAnotherEvent() {
    var e = event(UUID.randomUUID(), NOW);
    var row = e.builds().getFirst();
    var wrongRole =
        new DiscoveryEvent.ParsedSource(
            e.cdns().source(), e.versions().columns(), e.versions().sequence());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                wrongRole,
                e.cdns(),
                e.selectedCdn(),
                e.builds()));
    var changed = new LinkedHashMap<>(row.versionsValues());
    changed.put("BuildId", "43");
    var wrongRow = new DiscoveryEvent.ObservedBuild(row.build(), 2, changed);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                e.versions(),
                e.cdns(),
                e.selectedCdn(),
                List.of(wrongRow)));
    var other = event(UUID.randomUUID(), NOW);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DiscoveryHistorySelection(
                new DiscoveryObservationSelection(other.eventId(), row.build().snapshotId(), 2),
                e,
                row));
  }

  @Test
  void observedBuildRejectsMalformedOrUnboundedNestedTextBeforeEventOrSummary() {
    var original = event(UUID.randomUUID(), NOW).builds().getFirst();
    var b = original.build();
    for (var invalid : List.of("bad\ud800", "x".repeat(4097))) {
      var changed =
          List.of(
              new BuildSnapshot(
                  b.productId(),
                  invalid,
                  b.channel(),
                  b.region(),
                  b.versionLabel(),
                  b.buildConfig(),
                  b.cdnConfig(),
                  b.observedAt(),
                  false,
                  b.evidence()),
              new BuildSnapshot(
                  b.productId(),
                  b.editionLabel(),
                  invalid,
                  b.region(),
                  b.versionLabel(),
                  b.buildConfig(),
                  b.cdnConfig(),
                  b.observedAt(),
                  false,
                  b.evidence()),
              new BuildSnapshot(
                  b.productId(),
                  b.editionLabel(),
                  b.channel(),
                  b.region(),
                  invalid,
                  b.buildConfig(),
                  b.cdnConfig(),
                  b.observedAt(),
                  false,
                  b.evidence()));
      for (var build : changed) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new DiscoveryEvent.ObservedBuild(
                    build, original.versionsRowOrdinal(), original.versionsValues()));
      }
    }
  }

  @Test
  void maximumNestedTextRemainsLosslessAndCompatibleWithSummary() {
    var e = event(UUID.randomUUID(), NOW);
    var original = e.builds().getFirst();
    var b = original.build();
    var limit = "x".repeat(4096);
    var raw = new LinkedHashMap<>(original.versionsValues());
    raw.put("VersionsName", limit);
    var build =
        new BuildSnapshot(
            b.productId(),
            limit,
            limit,
            b.region(),
            limit,
            b.buildConfig(),
            b.cdnConfig(),
            b.observedAt(),
            false,
            b.evidence());
    var changed =
        new DiscoveryEvent(
            e.eventId(),
            e.productId(),
            e.region(),
            e.completedAt(),
            e.parserRevision(),
            e.versions(),
            e.cdns(),
            e.selectedCdn(),
            List.of(new DiscoveryEvent.ObservedBuild(build, 2, raw)));
    assertEquals(limit, changed.builds().getFirst().build().editionLabel());
    var summary = DiscoveryEventSummary.from(changed, 1);
    assertEquals(limit, summary.builds().getFirst().channel());
    assertEquals(limit, summary.builds().getFirst().versionLabel());
  }
}
