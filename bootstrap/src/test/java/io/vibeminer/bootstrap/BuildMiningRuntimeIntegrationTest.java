package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildoverview.BuildOverview;
import io.vibeminer.application.buildreadiness.BuildReadiness;
import io.vibeminer.application.buildretention.BuildRetention;
import io.vibeminer.application.inventory.BuildInventoryPreparationResult;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildMiningRuntimeIntegrationTest {
  @TempDir Path directory;

  @Test
  void preparesAnActualOpenedBuildWithoutFetchingGamePayload() throws Exception {
    var data = new BuildOpeningReviewData();
    try (var metadata = MetadataRuntime.open(directory, data::newClient, gateway -> gateway)) {
      var discovery =
          metadata
              .library()
              .refreshProduct("wow")
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertTrue(discovery.failures().isEmpty());
      var reference = discovery.registrations().getFirst().references().getFirst();
      try (var content = ContentRuntime.open(directory, data::newClient)) {
        var admission =
            content
                .buildOpening()
                .open(reference, "enUS")
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        int requests = data.requests();
        var prepared =
            content
                .inventoryPreparation()
                .prepare(admission)
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(admission, prepared.admission());
        assertTrue(prepared.downloadPolicy().isPresent());
        assertEquals(reference.key().identity(), prepared.target().groupId());
        var restored =
            content
                .retentionMode()
                .resume(prepared.target())
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(BuildRetention.Mode.ON_DEMAND, restored.mode());
        assertTrue(restored.intent().isEmpty());
        assertEquals(requests, data.requests());
        assertEquals(0, data.gameObjectRequests());
      }
    }
  }

  @Test
  void downloadAllRetainsContentAndOnDemandSurvivesOfflineReopen() throws Exception {
    var data = new BuildOpeningReviewData();
    try (var metadata = MetadataRuntime.open(directory, data::newClient, gateway -> gateway)) {
      var found =
          metadata
              .library()
              .refreshProduct("wow")
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      var reference = found.registrations().getFirst().references().getFirst();
      BuildInventoryPreparationResult prepared;
      int retainedRequests;
      try (var content = ContentRuntime.open(directory, data::newClient)) {
        var admission =
            content
                .buildOpening()
                .open(reference, "enUS")
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        prepared =
            content
                .inventoryPreparation()
                .prepare(admission)
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var initial = read(content, admission, prepared);
        assertEquals(BuildOverview.Readiness.PARTIAL, initial.readiness());
        assertEquals(0, initial.measurements().retainedUnits().orElseThrow());
        assertEquals(0, data.gameObjectRequests());
        var recordedPartial = lastChecked(metadata, admission, prepared);
        assertEquals(BuildOverview.Readiness.PARTIAL, recordedPartial.readiness());
        var enabled =
            content
                .retentionMode()
                .setMode(
                    prepared.target(),
                    0,
                    BuildRetention.Mode.DOWNLOAD_ALL,
                    prepared.downloadPolicy().orElseThrow())
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, enabled.mode());
        assertTrue(enabled.activationFailure().isEmpty());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        BuildOverview.Snapshot complete;
        do {
          complete = read(content, admission, prepared);
          if (complete.readiness() == BuildOverview.Readiness.FULL) break;
          assertTrue(System.nanoTime() < deadline, complete.toString());
          Thread.sleep(20);
        } while (true);
        assertEquals(1, complete.measurements().retainedUnits().orElseThrow());
        var recordedFull = lastChecked(metadata, admission, prepared);
        assertEquals(BuildOverview.Readiness.FULL, recordedFull.readiness());
        assertEquals(complete.measuredAt(), recordedFull.measuredAt());
        assertEquals(
            complete.measurements().plannedEncodedBytes(),
            complete.measurements().retainedScopeEncodedBytes());
        assertTrue(data.gameObjectRequests() > 0);
        var paused =
            content
                .retentionMode()
                .setMode(
                    prepared.target(),
                    enabled.intent().orElseThrow().generation(),
                    BuildRetention.Mode.ON_DEMAND,
                    prepared.downloadPolicy().orElseThrow())
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(BuildRetention.Mode.ON_DEMAND, paused.mode());
        assertEquals(2, paused.intent().orElseThrow().generation());
        assertEquals(BuildOverview.Readiness.FULL, read(content, admission, prepared).readiness());
        retainedRequests = data.requests();
      }
      data.offline();
      try (var content = ContentRuntime.open(directory, data::newClient)) {
        var admission =
            content
                .buildOpening()
                .open(reference, "enUS")
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var reopened =
            content
                .inventoryPreparation()
                .prepare(admission)
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(prepared.target(), reopened.target());
        var mode =
            content
                .retentionMode()
                .resume(reopened.target())
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(BuildRetention.Mode.ON_DEMAND, mode.mode());
        assertEquals(2, mode.intent().orElseThrow().generation());
        assertEquals(BuildOverview.Readiness.FULL, read(content, admission, reopened).readiness());
        assertEquals(retainedRequests, data.requests());
      }
    }
  }

  private static BuildOverview.Snapshot read(
      ContentRuntime runtime,
      BuildMetadataAdmission admission,
      BuildInventoryPreparationResult prepared)
      throws Exception {
    return runtime
        .buildOverview()
        .read(
            new BuildOverview.Request(
                admission.reference(),
                admission.locale(),
                admission.scopeRevision(),
                BuildMiningRuntime.overviewLimits(),
                Optional.of(prepared.target())))
        .completion()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  private static BuildReadiness.Observation lastChecked(
      MetadataRuntime metadata,
      BuildMetadataAdmission admission,
      BuildInventoryPreparationResult prepared)
      throws Exception {
    var context =
        new BuildReadiness.Context(
            admission.reference(),
            admission.locale(),
            admission.scopeRevision(),
            prepared.target().scopeRevision());
    var statuses =
        metadata
            .readiness()
            .read(java.util.List.of(context))
            .completion()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals(1, statuses.size());
    assertEquals(BuildReadiness.AdmissionPresence.PRESENT, statuses.getFirst().admission());
    return statuses.getFirst().lastChecked().orElseThrow();
  }
}
