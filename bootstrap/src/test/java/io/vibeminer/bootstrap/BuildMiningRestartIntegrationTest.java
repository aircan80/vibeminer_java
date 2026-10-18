package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildoverview.BuildOverview;
import io.vibeminer.application.buildretention.BuildRetention;
import io.vibeminer.application.inventory.BuildInventoryPreparationResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real content runtime, manifests, SQLite authority, source transport and CAS across restarts. */
class BuildMiningRestartIntegrationTest {
  @TempDir Path directory;

  @Test
  void interruptedAuthorizedAllResumesAutomaticallyAndCompletedObjectsRemainOffline()
      throws Exception {
    var data = new BuildOpeningReviewData();
    try (var metadata = MetadataRuntime.open(directory, data::newClient, gateway -> gateway)) {
      var discovery =
          metadata
              .library()
              .refreshProduct("wow")
              .completion()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      var reference = discovery.registrations().getFirst().references().getFirst();
      BuildMetadataAdmission admission;
      BuildInventoryPreparationResult prepared;
      BuildRetention.Intent authorized;
      data.holdGame();
      try (var content = ContentRuntime.open(directory, data::newClient)) {
        admission =
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
        var accepted =
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
        authorized = accepted.intent().orElseThrow();
        assertEquals(1, authorized.generation());
        assertTrue(data.awaitGame(Duration.ofSeconds(10)), "Actual game response was not held");
        assertEquals(
            BuildOverview.Readiness.PARTIAL, read(content, admission, prepared).readiness());
        // Graceful runtime shutdown withdraws only active consumers, preserving saved All intent.
      }
      assertTrue(
          data.awaitGameCancellation(Duration.ofSeconds(5)), "Actual response did not cancel");
      int retainedRequests = data.requests();
      int interruptedGameRequests = data.gameObjectRequests();
      data.holdGame();
      try (var restarted = ContentRuntime.open(directory, data::newClient)) {
        // No explicit resume, Open, prepare or mode action may be needed to restart saved work.
        assertTrue(
            data.awaitGame(Duration.ofSeconds(10)), "Stored All did not resume at runtime startup");
        var mode =
            restarted
                .retentionMode()
                .read(prepared.target())
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, mode.mode());
        var intent = mode.intent().orElseThrow();
        assertEquals(authorized.generation(), intent.generation());
        assertEquals(authorized.policy(), intent.policy());
        assertEquals(authorized.authorizedAt(), intent.authorizedAt());
        assertEquals(authorized.target(), intent.target());
        assertEquals(
            data.gameObjectRequests() - interruptedGameRequests,
            data.requests() - retainedRequests,
            "Completed configuration and metadata objects must not be downloaded again");
        var reopened =
            restarted
                .buildOpening()
                .open(reference, "enUS")
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(admission, reopened);
        var scope =
            restarted
                .inventoryPreparation()
                .prepare(reopened)
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(prepared.target(), scope.target());
        data.releaseGame();
        var complete = awaitFull(restarted, reopened, scope);
        assertEquals(1, complete.measurements().retainedUnits().orElseThrow());
        assertEquals(
            complete.measurements().plannedEncodedBytes(),
            complete.measurements().retainedScopeEncodedBytes());
      } finally {
        data.releaseGame();
      }
      int completedRequests = data.requests();
      int completedGameRequests = data.gameObjectRequests();
      data.offline();
      try (var offline = ContentRuntime.open(directory, data::newClient)) {
        var reopened =
            offline
                .buildOpening()
                .open(reference, "enUS")
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        var scope =
            offline
                .inventoryPreparation()
                .prepare(reopened)
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(BuildOverview.Readiness.FULL, read(offline, reopened, scope).readiness());
        var mode =
            offline
                .retentionMode()
                .read(scope.target())
                .completion()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, mode.mode());
        assertEquals(authorized.generation(), mode.intent().orElseThrow().generation());
        assertEquals(authorized.policy(), mode.intent().orElseThrow().policy());
      }
      assertEquals(completedRequests, data.requests());
      assertEquals(completedGameRequests, data.gameObjectRequests());
      assertEquals(
          0, data.forbiddenOfflineRequests(), "Even failed background HTTP attempts are forbidden");
    } finally {
      data.releaseGame();
    }
  }

  private static BuildOverview.Snapshot awaitFull(
      ContentRuntime content,
      BuildMetadataAdmission admission,
      BuildInventoryPreparationResult prepared)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    for (; ; ) {
      var snapshot = read(content, admission, prepared);
      if (snapshot.readiness() == BuildOverview.Readiness.FULL) return snapshot;
      assertTrue(System.nanoTime() < deadline, snapshot.toString());
    }
  }

  private static BuildOverview.Snapshot read(
      ContentRuntime content,
      BuildMetadataAdmission admission,
      BuildInventoryPreparationResult prepared)
      throws Exception {
    return content
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
}
