package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildOverviewPaneTest {
  private static BuildOverviewPane.Identity identity() {
    return new BuildOverviewPane.Identity(
        "exact-context",
        "World of Warcraft · Retail",
        "wow",
        "Published",
        "us",
        "12.1.5.70448",
        "enUS");
  }

  private static BuildOverviewPane.Scope scope() {
    return new BuildOverviewPane.Scope("scope-v1", "All declared languages and platforms");
  }

  private static BuildOverviewPane.Metrics metrics(
      OptionalLong total, OptionalLong retained, boolean complete) {
    return new BuildOverviewPane.Metrics(
        "scope-v1",
        Instant.EPOCH,
        OptionalLong.empty(),
        OptionalLong.empty(),
        total,
        retained,
        complete,
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty());
  }

  private static BuildOverviewPane.ViewData data(
      BuildOverviewPane.Readiness readiness,
      BuildOverviewPane.MetadataState metadata,
      BuildOverviewPane.Metrics metrics,
      BuildOverviewPane.DownloadMode mode,
      boolean allowed,
      boolean busy) {
    return new BuildOverviewPane.ViewData(
        identity(),
        scope(),
        readiness,
        metadata,
        metrics,
        mode,
        "",
        "",
        List.of(),
        new BuildOverviewPane.Capabilities(
            allowed, busy, false, false, "Download all is unavailable for this build."));
  }

  @Test
  void unknownTotalsNeverInventPercentageAndMeasuredZeroIsNotUnknown() {
    var unknown = metrics(OptionalLong.empty(), OptionalLong.of(0), false);
    assertEquals("Unknown", BuildOverviewPane.retainedPercentage(unknown));
    assertEquals("0 B", BuildOverviewPane.bytes(OptionalLong.of(0)));
    assertEquals("Not measured", BuildOverviewPane.bytes(OptionalLong.empty()));
    assertEquals(
        "No inventory or storage measurements yet.",
        BuildOverviewPane.measurementSummary(
            metrics(OptionalLong.empty(), OptionalLong.empty(), false)));
    assertTrue(BuildOverviewPane.measurementSummary(unknown).startsWith("Measured: "));
  }

  @Test
  void incompleteDenominatorCannotExposePercentageEvenWithKnownSubtotal() {
    assertEquals(
        "Unknown",
        BuildOverviewPane.retainedPercentage(
            metrics(OptionalLong.of(11840), OptionalLong.of(1860), false)));
  }

  @Test
  void retainedPercentageUsesSameScopeEncodedBytesAndSafeLargeIntegerArithmetic() {
    assertEquals(
        "15.71%",
        BuildOverviewPane.retainedPercentage(
            metrics(OptionalLong.of(11840), OptionalLong.of(1860), true)));
    assertEquals(
        "100%",
        BuildOverviewPane.retainedPercentage(
            metrics(OptionalLong.of(Long.MAX_VALUE), OptionalLong.of(Long.MAX_VALUE), true)));
    assertEquals(
        "Not applicable",
        BuildOverviewPane.retainedPercentage(
            metrics(OptionalLong.of(0), OptionalLong.of(0), true)));
  }

  @Test
  void inconsistentRetentionAndPhysicalSharedSubsetAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> metrics(OptionalLong.of(1), OptionalLong.of(2), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildOverviewPane.Metrics(
                "scope-v1",
                Instant.EPOCH,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                false,
                OptionalLong.of(4),
                OptionalLong.of(5),
                OptionalLong.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> metrics(OptionalLong.of(-1), OptionalLong.empty(), false));
  }

  @Test
  void readinessIsSuppliedAndFullDoesNotDependOnDownloadMode() {
    var complete = metrics(OptionalLong.of(100), OptionalLong.of(100), true);
    var demand =
        data(
            BuildOverviewPane.Readiness.FULL,
            BuildOverviewPane.MetadataState.READY,
            complete,
            BuildOverviewPane.DownloadMode.ON_DEMAND,
            true,
            false);
    assertEquals(BuildOverviewPane.Readiness.FULL, demand.readiness());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            data(
                BuildOverviewPane.Readiness.FULL,
                BuildOverviewPane.MetadataState.NEEDS_ATTENTION,
                complete,
                BuildOverviewPane.DownloadMode.DOWNLOAD_ALL,
                true,
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            data(
                BuildOverviewPane.Readiness.FULL,
                BuildOverviewPane.MetadataState.READY,
                metrics(OptionalLong.empty(), OptionalLong.of(100), false),
                BuildOverviewPane.DownloadMode.DOWNLOAD_ALL,
                true,
                false));
  }

  @Test
  void metricRevisionCannotBorrowAnotherScope() {
    var other = new BuildOverviewPane.Scope("scope-v2", "All declared variants");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildOverviewPane.ViewData(
                identity(),
                other,
                BuildOverviewPane.Readiness.PARTIAL,
                BuildOverviewPane.MetadataState.READY,
                metrics(OptionalLong.empty(), OptionalLong.empty(), false),
                BuildOverviewPane.DownloadMode.ON_DEMAND,
                "",
                "",
                List.of(),
                new BuildOverviewPane.Capabilities(false, false, false, false, "Unavailable")));
  }

  @Test
  void modeRequestPreservesExactContextAndScopeWithoutMutatingReadinessOrMetrics() {
    var value =
        data(
            BuildOverviewPane.Readiness.PARTIAL,
            BuildOverviewPane.MetadataState.READY,
            metrics(OptionalLong.of(100), OptionalLong.of(4), true),
            BuildOverviewPane.DownloadMode.ON_DEMAND,
            true,
            false);
    var requests = new ArrayList<BuildOverviewPane.ModeRequest>();
    BuildOverviewPane.requestMode(
        value, BuildOverviewPane.DownloadMode.DOWNLOAD_ALL, requests::add);
    assertEquals(
        List.of(
            new BuildOverviewPane.ModeRequest(
                "exact-context", "scope-v1", BuildOverviewPane.DownloadMode.DOWNLOAD_ALL)),
        requests);
    assertEquals(4, value.metrics().retainedScopeEncodedBytes().orElseThrow());
    assertEquals(BuildOverviewPane.DownloadMode.ON_DEMAND, value.mode());
  }

  @Test
  void unavailableBusyAndSameModeControlsCannotPretendToStartDownloader() {
    var requests = new ArrayList<BuildOverviewPane.ModeRequest>();
    for (boolean enabled : List.of(false, true))
      for (boolean busy : List.of(false, true)) {
        var value =
            data(
                BuildOverviewPane.Readiness.PARTIAL,
                BuildOverviewPane.MetadataState.READY,
                metrics(OptionalLong.empty(), OptionalLong.of(0), false),
                BuildOverviewPane.DownloadMode.ON_DEMAND,
                enabled,
                busy);
        BuildOverviewPane.requestMode(
            value, BuildOverviewPane.DownloadMode.ON_DEMAND, requests::add);
        if (!enabled || busy)
          BuildOverviewPane.requestMode(
              value, BuildOverviewPane.DownloadMode.DOWNLOAD_ALL, requests::add);
      }
    assertTrue(requests.isEmpty());
  }
}
