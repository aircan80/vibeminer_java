package io.vibeminer.application.buildlibrary;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildLibraryPolicyTest {
  static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

  static DiscoveryEvent event(String region, String buildConfig, String unknown, Instant time) {
    var versions =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/versions", time, new byte[] {1});
    var cdns =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/cdns", time, new byte[] {2});
    var b =
        new BuildSnapshot(
            "wow",
            "label",
            "Published",
            region,
            "1.2.3",
            buildConfig,
            "b".repeat(32),
            time,
            false,
            new BuildEvidence("42", "", List.of(versions, cdns)));
    var values =
        Map.of(
            "Region",
            region,
            "BuildConfig",
            buildConfig,
            "CDNConfig",
            "b".repeat(32),
            "VersionsName",
            "1.2.3",
            "BuildId",
            "42",
            "KeyRing",
            unknown);
    return new DiscoveryEvent(
        UUID.randomUUID(),
        "wow",
        region,
        time,
        "fixture-v1",
        new DiscoveryEvent.ParsedSource(
            versions,
            List.of("Region", "BuildConfig", "CDNConfig", "VersionsName", "BuildId", "KeyRing"),
            Optional.empty()),
        new DiscoveryEvent.ParsedSource(cdns, List.of("Name", "Path", "Hosts"), Optional.empty()),
        new DiscoveryEvent.CdnRow(
            0, Map.of("Name", region, "Path", "tpr/wow", "Hosts", region + ".example.invalid")),
        List.of(new DiscoveryEvent.ObservedBuild(b, 0, values)));
  }

  static BuildLibraryReference reference(String region, long sequence) {
    return BuildLibraryReference.from(event(region, "a".repeat(32), "", NOW), sequence).getFirst();
  }

  @Test
  void groupsOnlyCompleteReferencesPreservingRegionReceipts() {
    var us = reference("us", 1);
    var eu = reference("eu", 2);
    var groups = BuildLibraryGrouping.group(List.of(eu, us), 100);
    assertEquals(1, groups.size());
    assertEquals(2, groups.getFirst().references().size());
    assertEquals("us", groups.getFirst().preferredReference().region());
    assertNotEquals(us.snapshotId(), eu.snapshotId());
    assertEquals(us.key(), eu.key());
    var different = BuildLibraryReference.from(event("eu", "c".repeat(32), "", NOW), 3).getFirst();
    var unknown =
        BuildLibraryReference.from(event("eu", "a".repeat(32), "changed", NOW), 4).getFirst();
    assertEquals(3, BuildLibraryGrouping.group(List.of(us, eu, different, unknown), 100).size());
  }

  @Test
  void rawHexCaseAndReceiptTimesDoNotChangeKeyButUnknownFieldsDo() {
    var first = reference("us", 1);
    var later =
        BuildLibraryReference.from(event("us", "A".repeat(32), "", NOW.minusSeconds(10)), 2)
            .getFirst();
    assertEquals(first.key(), later.key());
    assertEquals(
        later,
        BuildLibraryGrouping.group(List.of(first, later), 100).getFirst().preferredReference());
    assertEquals(
        2, BuildLibraryGrouping.group(List.of(first, later), 100).getFirst().references().size());
    assertThrows(
        IllegalArgumentException.class, () -> BuildLibraryGrouping.group(List.of(first, later), 1));
  }

  @Test
  void firstSuccessfulRegionalBaselineIsNeutralAndOpenAloneAcknowledges() {
    var ref = reference("us", 1);
    var initial = BuildLibrarySeenPolicy.observe(Optional.empty(), ref, false, NOW);
    assertFalse(initial.isNew());
    var discovered = BuildLibrarySeenPolicy.observe(Optional.empty(), ref, true, NOW);
    assertTrue(discovered.isNew());
    assertSame(
        discovered,
        BuildLibrarySeenPolicy.observe(
            Optional.of(discovered), reference("eu", 2), false, NOW.plusSeconds(1)));
    var opened = BuildLibrarySeenPolicy.acknowledgeOpen(discovered, ref, NOW.plusSeconds(2));
    assertFalse(opened.isNew());
    assertEquals(NOW, opened.firstSeenAt());
    assertEquals(
        opened,
        BuildLibrarySeenPolicy.observe(
            Optional.of(opened), reference("us", 3), true, NOW.plusSeconds(3)));
    assertSame(
        opened,
        BuildLibrarySeenPolicy.acknowledgeOpen(opened, reference("eu", 4), NOW.plusSeconds(4)));
  }

  @Test
  void eachKnownIdentityFieldAndUnknownPresenceRemainSeparate() {
    var key = reference("us", 1).key();
    var variants =
        List.of(
            new BuildReferenceGroupKey(
                "wowt",
                key.channel(),
                key.versionLabel(),
                key.buildConfig(),
                key.cdnConfig(),
                key.upstreamBuildId(),
                key.productConfig(),
                key.additionalReferences()),
            new BuildReferenceGroupKey(
                key.productId(),
                "Other",
                key.versionLabel(),
                key.buildConfig(),
                key.cdnConfig(),
                key.upstreamBuildId(),
                key.productConfig(),
                key.additionalReferences()),
            new BuildReferenceGroupKey(
                key.productId(),
                key.channel(),
                "2",
                key.buildConfig(),
                key.cdnConfig(),
                key.upstreamBuildId(),
                key.productConfig(),
                key.additionalReferences()),
            new BuildReferenceGroupKey(
                key.productId(),
                key.channel(),
                key.versionLabel(),
                key.buildConfig(),
                "c".repeat(32),
                key.upstreamBuildId(),
                key.productConfig(),
                key.additionalReferences()),
            new BuildReferenceGroupKey(
                key.productId(),
                key.channel(),
                key.versionLabel(),
                key.buildConfig(),
                key.cdnConfig(),
                "43",
                key.productConfig(),
                key.additionalReferences()),
            new BuildReferenceGroupKey(
                key.productId(),
                key.channel(),
                key.versionLabel(),
                key.buildConfig(),
                key.cdnConfig(),
                key.upstreamBuildId(),
                "c".repeat(32),
                key.additionalReferences()),
            new BuildReferenceGroupKey(
                key.productId(),
                key.channel(),
                key.versionLabel(),
                key.buildConfig(),
                key.cdnConfig(),
                key.upstreamBuildId(),
                key.productConfig(),
                Map.of()));
    for (var changed : variants) assertNotEquals(key.identity(), changed.identity());
    assertThrows(UnsupportedOperationException.class, () -> key.additionalReferences().clear());
  }

  @Test
  void failedRegionMakesAbsenceUnknownAndRememberedPublicationIsNotLocal() {
    var ref = reference("us", 1);
    var other = BuildLibraryReference.from(event("us", "c".repeat(32), "", NOW), 2).getFirst();
    var checked =
        new BuildLibraryRegionalState(
            new BuildLibraryScope("wow", "us"),
            BuildLibraryRegionalState.CheckState.SUCCESS,
            Optional.of(other.eventId()),
            Set.of(other.key().identity()),
            Optional.of(NOW));
    var failed =
        new BuildLibraryRegionalState(
            new BuildLibraryScope("wow", "eu"),
            BuildLibraryRegionalState.CheckState.FAILED,
            Optional.of(ref.eventId()),
            Set.of(ref.key().identity()),
            Optional.of(NOW));
    assertEquals(
        BuildLibraryProjection.Advertisement.ABSENT_FROM_CHECKED_SCOPE,
        BuildLibraryProjection.advertisement(ref.key(), List.of(checked)));
    assertEquals(
        BuildLibraryProjection.Advertisement.UNKNOWN,
        BuildLibraryProjection.advertisement(ref.key(), List.of(checked, failed)));
    var row =
        new BuildLibraryProjection.Row(
            BuildLibraryGrouping.group(List.of(ref), 10).getFirst(),
            BuildLibrarySeenPolicy.observe(Optional.empty(), ref, false, NOW),
            BuildLibraryLocalFact.observationOnly(),
            BuildLibraryProjection.Advertisement.UNKNOWN);
    assertTrue(row.matches(BuildLibraryProjection.Filter.ALL));
    assertFalse(row.matches(BuildLibraryProjection.Filter.LOCAL));
    assertFalse(row.matches(BuildLibraryProjection.Filter.CURRENTLY_ADVERTISED));
    assertTrue(new BuildLibraryLocalFact(true, false).isLocal());
    assertTrue(new BuildLibraryLocalFact(false, true).isLocal());
  }

  @Test
  void seenPolicyRejectsWrongGroupAndDoesNotUseWallClockForNewOrAck() {
    var ref = reference("us", 1);
    var state = BuildLibrarySeenPolicy.observe(Optional.empty(), ref, true, NOW);
    var other = BuildLibraryReference.from(event("us", "c".repeat(32), "", NOW), 2).getFirst();
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildLibrarySeenPolicy.acknowledgeOpen(state, other, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildLibrarySeenPolicy.observe(Optional.of(state), other, true, NOW));
    var acknowledged = BuildLibrarySeenPolicy.acknowledgeOpen(state, ref, NOW.minusSeconds(5));
    assertFalse(acknowledged.isNew());
    assertEquals(NOW, acknowledged.firstSeenAt());
    assertEquals(NOW.minusSeconds(5), acknowledged.acknowledgedAt().orElseThrow());
  }

  @Test
  void baselineIsRegionalAndRegistrationKindCannotManufactureCheckAuthority() {
    var us = reference("us", 1);
    var eu = reference("eu", 2);
    var baseline =
        BuildLibrarySeenPolicy.establishFirstSuccessfulBaseline(Optional.empty(), us, NOW);
    assertSame(
        baseline,
        BuildLibrarySeenPolicy.establishFirstSuccessfulBaseline(
            Optional.of(baseline), reference("us", 3), NOW.plusSeconds(3)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BuildLibrarySeenPolicy.establishFirstSuccessfulBaseline(
                Optional.of(baseline), eu, NOW));
    var ticket =
        new BuildLibraryCheckTicket(UUID.randomUUID(), new BuildLibraryScope("wow", "us"), 1, NOW);
    var registration =
        new BuildLibraryRegistration(
            us.eventId(),
            BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
            Optional.of(ticket),
            NOW,
            List.of(us));
    assertEquals(us, registration.references().getFirst());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildLibraryRegistration(
                us.eventId(),
                BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
                Optional.of(ticket),
                NOW,
                List.of(us)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildLibraryRegistration(
                us.eventId(),
                BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                Optional.empty(),
                NOW,
                List.of(us)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildLibraryRegistration(
                eu.eventId(),
                BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                Optional.of(ticket),
                NOW,
                List.of(eu)));
  }

  @Test
  void registrationCannotMixEventSharedReceiptsTimeOrRouteAcrossRows() {
    var first = reference("us", 1);
    var changedVersions =
        new DiscoveryEventSummary.DocumentReceipt(
            first.versions().sourceUrl(),
            first.versions().capturedAt(),
            "f".repeat(64),
            first.versions().length());
    var changedCdns =
        new DiscoveryEventSummary.DocumentReceipt(
            first.cdns().sourceUrl(),
            first.cdns().capturedAt().plusSeconds(1),
            first.cdns().sha256(),
            first.cdns().length());
    var changedRoute =
        new DiscoveryEvent.CdnRow(
            1, Map.of("Name", "us", "Path", "other/path", "Hosts", "different.invalid"));
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (int variant = 0; variant < 4; variant++) {
      final int which = variant;
      checks.add(
          () -> {
            var other =
                new BuildLibraryReference(
                    first.key(),
                    first.eventId(),
                    first.observationSequence(),
                    first.snapshotId(),
                    1,
                    first.region(),
                    which == 0 ? first.observedAt().plusSeconds(1) : first.observedAt(),
                    which == 1 ? changedVersions : first.versions(),
                    which == 2 ? changedCdns : first.cdns(),
                    which == 3 ? changedRoute : first.selectedCdn());
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new BuildLibraryRegistration(
                        first.eventId(),
                        BuildLibraryRegistration.Kind.HISTORICAL_IMPORT,
                        Optional.empty(),
                        NOW,
                        List.of(first, other)));
          });
    }
    assertAll(checks);
  }
}
