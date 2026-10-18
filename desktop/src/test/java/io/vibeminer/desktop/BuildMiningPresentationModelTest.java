package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.inventory.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BuildMiningPresentationModelTest {
  static final BuildOverview.Limits LIMITS =
      new BuildOverview.Limits(100, 10000, 25, 10000, Duration.ofSeconds(10));

  static class Preparation implements BuildInventoryPreparationService {
    final List<Prepare> jobs = new ArrayList<>();
    int closes;

    public Operation prepare(BuildMetadataAdmission admission) {
      var job = new Prepare();
      jobs.add(job);
      return job;
    }

    public void close() {
      closes++;
    }
  }

  static class Prepare implements BuildInventoryPreparationService.Operation {
    final CompletableFuture<BuildInventoryPreparationResult> done = new CompletableFuture<>();
    int canceled;
    BuildInventoryPreparationProgress progress = BuildInventoryPreparationProgress.initial();

    public CompletionStage<BuildInventoryPreparationResult> completion() {
      return done;
    }

    public BuildInventoryPreparationProgress progress() {
      return progress;
    }

    public void requestCancellation() {
      canceled++;
    }
  }

  static class Overview implements BuildOverviewService {
    final List<Read> jobs = new ArrayList<>();
    int closes;

    public Operation read(BuildOverview.Request request) {
      var job = new Read(request);
      jobs.add(job);
      return job;
    }

    public void close() {
      closes++;
    }
  }

  static class Read implements BuildOverviewService.Operation {
    final BuildOverview.Request request;
    final CompletableFuture<BuildOverview.Snapshot> done = new CompletableFuture<>();
    int canceled;
    BuildOverview.Progress progress = BuildOverview.Progress.initial();

    Read(BuildOverview.Request request) {
      this.request = request;
    }

    public CompletionStage<BuildOverview.Snapshot> completion() {
      return done;
    }

    public BuildOverview.Progress progress() {
      return progress;
    }

    public void requestCancellation() {
      canceled++;
    }
  }

  static class Modes implements BuildRetentionModeService {
    final List<ModeJob> jobs = new ArrayList<>();
    int closes, sets, resumes;
    long expected;
    BuildRetention.Policy policy;
    BuildRetention.Mode requested;

    public Operation read(BuildRetention.Target target) {
      var job = new ModeJob(target);
      jobs.add(job);
      return job;
    }

    public Operation resume(BuildRetention.Target target) {
      resumes++;
      var job = new ModeJob(target);
      jobs.add(job);
      return job;
    }

    public Operation setMode(
        BuildRetention.Target target,
        long generation,
        BuildRetention.Mode mode,
        BuildRetention.Policy policy) {
      sets++;
      expected = generation;
      requested = mode;
      this.policy = policy;
      var job = new ModeJob(target);
      jobs.add(job);
      return job;
    }

    public void close() {
      closes++;
    }
  }

  static class ModeJob implements BuildRetentionModeService.Operation {
    final BuildRetention.Target target;
    final CompletableFuture<BuildRetentionModeService.View> done = new CompletableFuture<>();
    int canceled;

    ModeJob(BuildRetention.Target target) {
      this.target = target;
    }

    public CompletionStage<BuildRetentionModeService.View> completion() {
      return done;
    }

    public void requestCancellation() {
      canceled++;
    }
  }

  static class Harness {
    final Preparation prep = new Preparation();
    final Overview overview = new Overview();
    final Modes modes = new Modes();
    final long[] nanos = {0};
    final BuildMiningPresentationModel model;
    final BuildMetadataAdmission admission =
        BuildOpenPresentationModelTest.admission(BuildOpenPresentationModelTest.reference("a"));
    final BuildRetention.Policy policy = new BuildRetention.Policy(1000, 10000, 10000, 100, 2);
    final BuildRetention.Target target =
        new BuildRetention.Target(
            admission.reference().key().identity(),
            admission.reference().snapshotId(),
            "c".repeat(64),
            "d".repeat(64),
            1,
            "all-declared-v1",
            "e".repeat(64));

    Harness() {
      this(Runnable::run);
    }

    Harness(java.util.concurrent.Executor executor) {
      model =
          new BuildMiningPresentationModel(
              prep, overview, modes, LIMITS, executor, s -> {}, () -> nanos[0]);
    }

    void prepare() {
      model.start(admission);
      prep.jobs
          .getLast()
          .done
          .complete(new BuildInventoryPreparationResult(admission, target, Optional.of(policy)));
    }

    void resume() {
      prepare();
      modes
          .jobs
          .getLast()
          .done
          .complete(new BuildRetentionModeService.View(target, Optional.empty(), Optional.empty()));
    }

    BuildRetention.Intent intent(BuildRetention.Mode mode, long generation) {
      return new BuildRetention.Intent(
          target, mode, generation, policy, Instant.EPOCH, 1, 1, new BuildRetention.Cost(0, 0, 0));
    }

    BuildOverview.Snapshot snapshot(boolean full, long queued) {
      return new BuildOverview.Snapshot(
          admission.reference(),
          admission.locale(),
          admission.scopeRevision(),
          "f".repeat(64),
          Instant.EPOCH,
          BuildOverview.MetadataState.READY,
          BuildOverview.InventoryState.SEALED,
          full ? BuildOverview.Readiness.FULL : BuildOverview.Readiness.PARTIAL,
          Optional.of(target),
          new BuildOverview.Measurements(
              OptionalLong.of(1),
              OptionalLong.of(2),
              OptionalLong.of(1),
              OptionalLong.of(100),
              OptionalLong.of(full ? 1 : 0),
              OptionalLong.of(full ? 100 : 0),
              true,
              true,
              OptionalLong.empty(),
              OptionalLong.empty(),
              OptionalLong.empty()),
          Optional.of(
              new BuildOverview.Retention(
                  BuildRetention.Mode.DOWNLOAD_ALL,
                  1,
                  OptionalLong.of(queued),
                  OptionalLong.of(0),
                  OptionalLong.of(full ? 1 : 0),
                  OptionalLong.of(0))),
          List.of());
    }
  }

  @Test
  void actualPreparationOnceThenResumeDoesNotInventConsentOrPayload() {
    var h = new Harness();
    h.model.start(h.admission);
    h.model.start(h.admission);
    assertEquals(1, h.prep.jobs.size());
    assertTrue(h.model.state().preparing());
    assertTrue(h.overview.jobs.isEmpty());
    assertEquals(0, h.modes.sets);
    h.prep
        .jobs
        .getFirst()
        .done
        .complete(
            new BuildInventoryPreparationResult(h.admission, h.target, Optional.of(h.policy)));
    assertEquals(1, h.modes.resumes);
    assertEquals(0, h.modes.sets);
    h.modes
        .jobs
        .getFirst()
        .done
        .complete(new BuildRetentionModeService.View(h.target, Optional.empty(), Optional.empty()));
    assertEquals(h.target, h.overview.jobs.getFirst().request.target().orElseThrow());
    assertTrue(h.model.state().snapshot() == null);
    assertEquals(0, h.modes.sets);
  }

  @Test
  void explicitSwitchUsesActualPolicyAndExpectedGenerationAndPreservesSavedFailure() {
    var h = new Harness();
    h.resume();
    h.overview.jobs.getFirst().done.complete(h.snapshot(false, 0));
    h.model.requestMode(BuildRetention.Mode.DOWNLOAD_ALL);
    assertEquals(1, h.modes.expected);
    assertSame(h.policy, h.modes.policy);
    h.modes
        .jobs
        .getLast()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 2)),
                Optional.of(
                    new BuildRetentionModeService.ActivationFailure(
                        BuildRetentionException.Code.STORAGE_UNAVAILABLE, "Activation failed"))));
    assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, h.model.state().modeView().mode());
    assertFalse(h.model.state().error().isBlank());
    h.model.requestMode(BuildRetention.Mode.ON_DEMAND);
    assertEquals(2, h.modes.expected);
  }

  @Test
  void retirementCancelsObservationsNeverWritesOnDemandAndLateSuccessIsInert() {
    Queue<Runnable> ui = new ArrayDeque<>();
    var h = new Harness(ui::add);
    h.model.start(h.admission);
    h.prep
        .jobs
        .getFirst()
        .done
        .complete(new BuildInventoryPreparationResult(h.admission, h.target));
    h.model.retire();
    ui.remove().run();
    assertNull(h.model.state().admission());
    assertEquals(0, h.modes.resumes);
    assertEquals(0, h.modes.sets);
    h.model.close();
    assertEquals(0, h.prep.closes + h.overview.closes + h.modes.closes);
  }

  @Test
  void settingsPreservesSessionAndBackgroundIntentWithoutRestartingPreparation() {
    var h = new Harness();
    h.resume();
    h.model.setVisible(false);
    h.model.start(h.admission);
    h.model.setVisible(true);
    assertEquals(1, h.prep.jobs.size());
    assertEquals(0, h.modes.sets);
    assertEquals(1, h.overview.jobs.size());
  }

  @Test
  void repeatedTicksDoNotRescanWhileReadPendingAndRefreshStopsAfterFull() {
    var h = new Harness();
    h.resume();
    for (int i = 0; i < 20; i++) h.model.tick();
    assertEquals(1, h.overview.jobs.size());
    h.overview.jobs.getFirst().done.complete(h.snapshot(false, 1));
    h.nanos[0] = Duration.ofSeconds(5).toNanos();
    h.model.tick();
    assertEquals(2, h.overview.jobs.size());
    h.overview.jobs.getLast().done.complete(h.snapshot(true, 0));
    h.nanos[0] += Duration.ofMinutes(1).toNanos();
    h.model.tick();
    assertEquals(2, h.overview.jobs.size());
  }

  @Test
  void modeCompletionDuringAnOlderReadQueuesExactlyOneFreshVerification() {
    var h = new Harness();
    h.resume();
    h.model.requestMode(BuildRetention.Mode.DOWNLOAD_ALL);
    h.modes
        .jobs
        .getLast()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 1)),
                Optional.empty()));
    assertEquals(1, h.overview.jobs.size());
    h.overview.jobs.getFirst().done.complete(h.snapshot(false, 0));
    assertEquals(2, h.overview.jobs.size());
    h.overview.jobs.getLast().done.complete(h.snapshot(true, 0));
    assertEquals(2, h.overview.jobs.size());
    assertEquals(BuildOverview.Readiness.FULL, h.model.state().snapshot().readiness());
  }

  @Test
  void wrongScopeAndVerificationFailureNeverLeaveAnOldFullClaim() {
    var h = new Harness();
    h.resume();
    h.overview.jobs.getFirst().done.complete(h.snapshot(true, 0));
    h.model.requestMode(BuildRetention.Mode.DOWNLOAD_ALL);
    h.modes
        .jobs
        .getLast()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 1)),
                Optional.empty()));
    h.overview.jobs.getLast().done.completeExceptionally(new IllegalStateException("No proof"));
    assertNull(h.model.state().snapshot());
    assertFalse(h.model.state().error().isBlank());
  }

  @Test
  void retiringModeObservationNeverTurnsOffAnAlreadyCommittedDownloadAllIntent() {
    var h = new Harness();
    h.resume();
    h.overview.jobs.getFirst().done.complete(h.snapshot(false, 0));
    h.model.requestMode(BuildRetention.Mode.DOWNLOAD_ALL);
    var operation = h.modes.jobs.getLast();
    h.model.retire();
    operation.done.complete(
        new BuildRetentionModeService.View(
            h.target,
            Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 1)),
            Optional.empty()));
    assertEquals(1, operation.canceled);
    assertEquals(1, h.modes.sets);
    assertNull(h.model.state().admission());
  }

  @Test
  void noOfferedOrPersistedPolicyCannotInventDownloadAllAuthority() {
    var h = new Harness();
    h.model.start(h.admission);
    h.prep
        .jobs
        .getFirst()
        .done
        .complete(new BuildInventoryPreparationResult(h.admission, h.target));
    h.modes
        .jobs
        .getFirst()
        .done
        .complete(new BuildRetentionModeService.View(h.target, Optional.empty(), Optional.empty()));
    h.model.requestMode(BuildRetention.Mode.DOWNLOAD_ALL);
    assertEquals(0, h.modes.sets);
    assertFalse(h.model.state().error().isBlank());
  }

  @Test
  void projectionUsesActualInventoryAndVerifiedCoverageWithoutLedgerFullInference() {
    var h = new Harness();
    h.model.start(h.admission);
    var baseline = AtlasApplication.admittedOverview(h.admission, Instant.EPOCH);
    assertEquals(
        BuildOverviewPane.InventoryState.PREPARING, h.model.viewData(baseline).inventory());
    assertEquals(BuildOverviewPane.Readiness.PARTIAL, h.model.viewData(baseline).readiness());
    h.prep
        .jobs
        .getFirst()
        .done
        .complete(
            new BuildInventoryPreparationResult(h.admission, h.target, Optional.of(h.policy)));
    h.modes
        .jobs
        .getFirst()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 1)),
                Optional.empty()));
    h.overview.jobs.getFirst().done.complete(h.snapshot(false, 1));
    var view = h.model.viewData(baseline);
    assertEquals(BuildOverviewPane.InventoryState.SEALED, view.inventory());
    assertEquals(h.target.scopeCommitment(), view.scope().revision());
    assertEquals(view.scope().revision(), view.metrics().scopeRevision());
    assertEquals(BuildOverviewPane.Readiness.PARTIAL, view.readiness());
    assertEquals(0, view.metrics().retainedScopeEncodedBytes().orElseThrow());
    assertTrue(view.capabilities().modeAvailable());
    assertTrue(view.refreshCoverageAvailable());
    h.nanos[0] = Duration.ofSeconds(5).toNanos();
    h.model.tick();
    h.overview.jobs.getLast().done.complete(h.snapshot(true, 0));
    assertEquals(BuildOverviewPane.Readiness.FULL, h.model.viewData(baseline).readiness());
  }

  @Test
  void failedPreparationIsRetryableAndDoesNotPretendMetadataIsMissing() {
    var h = new Harness();
    h.model.start(h.admission);
    h.prep
        .jobs
        .getFirst()
        .done
        .completeExceptionally(new IllegalStateException("Bounded preparation failed"));
    var view = h.model.viewData(AtlasApplication.admittedOverview(h.admission, Instant.EPOCH));
    assertEquals(BuildOverviewPane.MetadataState.READY, view.metadata());
    assertEquals(BuildOverviewPane.InventoryState.NEEDS_ATTENTION, view.inventory());
    assertTrue(view.retryPreparationAvailable());
    h.model.retryPreparation();
    assertEquals(2, h.prep.jobs.size());
    assertEquals(0, h.modes.sets);
  }

  @Test
  void preparationServiceReentrancyAndCloseCannotResurrectReturnedJobs() {
    var operation = new Prepare();
    var holder = new BuildMiningPresentationModel[1];
    var prep =
        new Preparation() {
          public Operation prepare(BuildMetadataAdmission admission) {
            holder[0].retire();
            return operation;
          }
        };
    holder[0] =
        new BuildMiningPresentationModel(
            prep, new Overview(), new Modes(), LIMITS, Runnable::run, s -> {}, () -> 0);
    holder[0].start(
        BuildOpenPresentationModelTest.admission(BuildOpenPresentationModelTest.reference("a")));
    assertNull(holder[0].state().admission());
    assertEquals(1, operation.canceled);
  }

  @Test
  void restoredAllUsesPersistedAuthorityWithoutNewConsentAndCanSwitchOffWithoutOfferedBudget() {
    var h = new Harness();
    h.model.start(h.admission);
    h.prep
        .jobs
        .getFirst()
        .done
        .complete(new BuildInventoryPreparationResult(h.admission, h.target));
    h.modes
        .jobs
        .getFirst()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 5)),
                Optional.empty()));
    assertEquals(0, h.modes.sets);
    assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, h.model.state().modeView().mode());
    assertTrue(
        h.model
            .viewData(AtlasApplication.admittedOverview(h.admission, Instant.EPOCH))
            .capabilities()
            .modeAvailable());
    h.model.requestMode(BuildRetention.Mode.ON_DEMAND);
    assertEquals(5, h.modes.expected);
    assertSame(h.policy, h.modes.policy);
  }

  @Test
  void wrongAdmissionScopeCannotBorrowValidMeasurements() {
    var h = new Harness();
    h.resume();
    var source = h.snapshot(true, 0);
    var wrong =
        new BuildOverview.Snapshot(
            source.reference(),
            source.locale(),
            "another-policy",
            source.revision(),
            source.measuredAt(),
            source.metadata(),
            source.inventory(),
            source.readiness(),
            source.target(),
            source.measurements(),
            source.retention(),
            source.problems());
    h.overview.jobs.getFirst().done.complete(wrong);
    assertNull(h.model.state().snapshot());
    assertEquals(
        BuildOverviewPane.Readiness.PARTIAL,
        h.model
            .viewData(AtlasApplication.admittedOverview(h.admission, Instant.EPOCH))
            .readiness());
    assertFalse(h.model.state().error().isBlank());
  }

  @Test
  void hiddenSettingsDoesNotPollOrStartRecurringVerification() {
    var h = new Harness();
    h.resume();
    h.overview.jobs.getFirst().done.complete(h.snapshot(false, 1));
    h.model.setVisible(false);
    h.nanos[0] = Duration.ofMinutes(1).toNanos();
    h.model.tick();
    assertEquals(1, h.overview.jobs.size());
    assertEquals(1, h.prep.jobs.size());
    h.model.setVisible(true);
    h.model.tick();
    assertEquals(2, h.overview.jobs.size());
  }

  @Test
  void sourceMetadataCorruptionFromActualSnapshotShowsNotReady() {
    var h = new Harness();
    h.resume();
    var source = h.snapshot(false, 0);
    h.overview
        .jobs
        .getFirst()
        .done
        .complete(
            new BuildOverview.Snapshot(
                source.reference(),
                source.locale(),
                source.scopeRevision(),
                source.revision(),
                source.measuredAt(),
                BuildOverview.MetadataState.NEEDS_ATTENTION,
                source.inventory(),
                BuildOverview.Readiness.NOT_READY,
                source.target(),
                source.measurements(),
                source.retention(),
                List.of(
                    new BuildOverview.Problem(
                        BuildOverview.ProblemCode.METADATA_CORRUPT,
                        "Required metadata failed verification"))));
    var view = h.model.viewData(AtlasApplication.admittedOverview(h.admission, Instant.EPOCH));
    assertEquals(BuildOverviewPane.Readiness.NOT_READY, view.readiness());
    assertEquals(BuildOverviewPane.MetadataState.NEEDS_ATTENTION, view.metadata());
    assertTrue(view.problems().stream().anyMatch(s -> s.contains("METADATA_CORRUPT")));
  }

  @Test
  void mismatchedBaselineCannotRelabelCurrentMeasurements() {
    var h = new Harness();
    h.resume();
    h.overview.jobs.getFirst().done.complete(h.snapshot(true, 0));
    var other =
        BuildOpenPresentationModelTest.admission(BuildOpenPresentationModelTest.reference("b"));
    var baseline = AtlasApplication.admittedOverview(other, Instant.EPOCH);
    assertThrows(IllegalArgumentException.class, () -> h.model.viewData(baseline));
  }

  @Test
  void incompleteAuthoritativeEnumerationKeepsBoundedWatcherBeforeFirstQueuedRow() {
    var h = new Harness();
    h.resume();
    var source = h.snapshot(false, 0);
    var pending =
        new BuildOverview.Snapshot(
            source.reference(),
            source.locale(),
            source.scopeRevision(),
            source.revision(),
            source.measuredAt(),
            source.metadata(),
            source.inventory(),
            source.readiness(),
            source.target(),
            source.measurements(),
            Optional.of(
                new BuildOverview.Retention(
                    BuildRetention.Mode.DOWNLOAD_ALL,
                    1,
                    OptionalLong.of(0),
                    OptionalLong.of(0),
                    OptionalLong.of(0),
                    OptionalLong.of(0),
                    false)),
            source.problems());
    h.overview.jobs.getFirst().done.complete(pending);
    assertEquals(
        "Preparing download queue",
        h.model.viewData(AtlasApplication.admittedOverview(h.admission, Instant.EPOCH)).activity());
    h.nanos[0] = Duration.ofSeconds(5).toNanos();
    h.model.tick();
    assertEquals(2, h.overview.jobs.size());
    h.overview.jobs.getLast().done.complete(source);
    h.nanos[0] += Duration.ofSeconds(5).toNanos();
    h.model.tick();
    assertEquals(2, h.overview.jobs.size());
  }

  @Test
  void activationFailureNeedsExplicitResumeAndReadOnlyRefreshDoesNotPretendItRecovered() {
    var h = new Harness();
    h.prepare();
    var failure =
        new BuildRetentionModeService.ActivationFailure(
            BuildRetentionException.Code.STORAGE_UNAVAILABLE, "Activation failed");
    h.modes
        .jobs
        .getLast()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 2)),
                Optional.of(failure)));
    h.overview.jobs.getLast().done.complete(h.snapshot(false, 0));
    h.model.refresh();
    h.modes
        .jobs
        .getLast()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 2)),
                Optional.empty()));
    assertTrue(h.model.state().modeView().activationFailure().isPresent());
    assertTrue(
        h.model
            .viewData(AtlasApplication.admittedOverview(h.admission, Instant.EPOCH))
            .capabilities()
            .retryRetention());
    int resumes = h.modes.resumes;
    h.model.retryActivation();
    assertEquals(resumes + 1, h.modes.resumes);
    assertEquals(0, h.modes.sets);
    h.modes
        .jobs
        .getLast()
        .done
        .complete(
            new BuildRetentionModeService.View(
                h.target,
                Optional.of(h.intent(BuildRetention.Mode.DOWNLOAD_ALL, 2)),
                Optional.empty()));
    assertTrue(h.model.state().modeView().activationFailure().isEmpty());
  }

  @Test
  void retirementObserverSelectingNewBuildWinsOverOuterStartWithoutLeakingAnotherJob() {
    var prep = new Preparation();
    var holder = new BuildMiningPresentationModel[1];
    var replacement =
        BuildOpenPresentationModelTest.admission(BuildOpenPresentationModelTest.reference("b"));
    var replaced = new java.util.concurrent.atomic.AtomicBoolean();
    holder[0] =
        new BuildMiningPresentationModel(
            prep,
            new Overview(),
            new Modes(),
            LIMITS,
            Runnable::run,
            state -> {
              if (state.admission() == null && replaced.compareAndSet(false, true))
                holder[0].start(replacement);
            },
            () -> 0);
    holder[0].start(
        BuildOpenPresentationModelTest.admission(BuildOpenPresentationModelTest.reference("a")));
    assertSame(replacement, holder[0].state().admission());
    assertEquals(1, prep.jobs.size());
    assertEquals(0, prep.jobs.getFirst().canceled);
  }

  @Test
  void retryPreparationCannotOverrideNewObserverContextOrRetireItsOperationTwice() {
    var prep = new Preparation();
    var holder = new BuildMiningPresentationModel[1];
    var replacement =
        BuildOpenPresentationModelTest.admission(BuildOpenPresentationModelTest.reference("b"));
    var replace = new java.util.concurrent.atomic.AtomicBoolean();
    holder[0] =
        new BuildMiningPresentationModel(
            prep,
            new Overview(),
            new Modes(),
            LIMITS,
            Runnable::run,
            state -> {
              if (state.admission() == null && replace.compareAndSet(true, false))
                holder[0].start(replacement);
            },
            () -> 0);
    holder[0].start(
        BuildOpenPresentationModelTest.admission(BuildOpenPresentationModelTest.reference("a")));
    prep.jobs.getFirst().done.completeExceptionally(new IllegalStateException("Retryable"));
    replace.set(true);
    holder[0].retryPreparation();
    assertSame(replacement, holder[0].state().admission());
    assertEquals(2, prep.jobs.size());
    assertEquals(0, prep.jobs.getLast().canceled);
  }
}
