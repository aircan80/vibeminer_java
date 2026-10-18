package io.vibeminer.desktop;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.inventory.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Presentation-thread-confined observations of one admitted mining session; services are borrowed.
 */
public final class BuildMiningPresentationModel implements AutoCloseable {
  public record State(
      BuildMetadataAdmission admission,
      BuildInventoryPreparationResult prepared,
      boolean preparing,
      BuildInventoryPreparationProgress preparationProgress,
      BuildOverview.Snapshot snapshot,
      boolean reading,
      BuildOverview.Progress overviewProgress,
      BuildRetentionModeService.View modeView,
      boolean changingMode,
      String error) {}

  private final BuildInventoryPreparationService preparation;
  private final BuildOverviewService overview;
  private final BuildRetentionModeService modes;
  private final BuildOverview.Limits limits;
  private final Executor presentation;
  private final Consumer<State> observer;
  private final LongSupplier nanos;
  private BuildMetadataAdmission admission;
  private BuildInventoryPreparationResult prepared;
  private BuildOverview.Snapshot snapshot;
  private BuildRetentionModeService.View modeView;
  private BuildInventoryPreparationProgress preparationProgress;
  private BuildOverview.Progress overviewProgress;
  private BuildInventoryPreparationService.Operation preparing;
  private BuildOverviewService.Operation reading;
  private BuildRetentionModeService.Operation changing;
  private long epoch, readGeneration, modeGeneration, lastRead;
  private boolean closed, retiring, refreshAfterRead, visible = true;
  private String preparationError = "", overviewError = "", modeError = "";
  private static final long REFRESH_INTERVAL = java.time.Duration.ofSeconds(5).toNanos();

  public BuildMiningPresentationModel(
      BuildInventoryPreparationService preparation,
      BuildOverviewService overview,
      BuildRetentionModeService modes,
      BuildOverview.Limits limits,
      Executor presentation,
      Consumer<State> observer,
      LongSupplier nanos) {
    this.preparation = Objects.requireNonNull(preparation);
    this.overview = Objects.requireNonNull(overview);
    this.modes = Objects.requireNonNull(modes);
    this.limits = Objects.requireNonNull(limits);
    this.presentation = Objects.requireNonNull(presentation);
    this.observer = Objects.requireNonNull(observer);
    this.nanos = Objects.requireNonNull(nanos);
  }

  public State state() {
    String error =
        java.util.stream.Stream.of(preparationError, modeError, overviewError)
            .filter(s -> !s.isBlank())
            .collect(java.util.stream.Collectors.joining(" "));
    return new State(
        admission,
        prepared,
        preparing != null,
        preparationProgress,
        snapshot,
        reading != null,
        overviewProgress,
        modeView,
        changing != null,
        error);
  }

  public void start(BuildMetadataAdmission next) {
    Objects.requireNonNull(next);
    if (closed || retiring || next.equals(admission)) return;
    replaceAdmission(next);
  }

  private void replaceAdmission(BuildMetadataAdmission next) {
    long retiredEpoch = epoch + 1;
    retire();
    // A retirement observer may already have selected and started another exact build.
    if (closed || retiring || epoch != retiredEpoch || admission != null) return;
    admission = next;
    long requestEpoch = epoch;
    preparationProgress = BuildInventoryPreparationProgress.initial();
    emit();
    if (!current(requestEpoch)) return;
    try {
      var operation = Objects.requireNonNull(preparation.prepare(next));
      if (!current(requestEpoch)) {
        cancel(operation::requestCancellation);
        return;
      }
      preparing = operation;
      preparationProgress = operation.progress();
      emit();
      operation
          .completion()
          .whenComplete(
              (result, failure) ->
                  presentation.execute(
                      () -> {
                        if (!current(requestEpoch) || preparing != operation) return;
                        preparing = null;
                        if (failure != null) {
                          preparationError = "Inventory preparation failed. Retry preparation.";
                          emit();
                          return;
                        }
                        if (result == null || !next.equals(result.admission())) {
                          preparationError = "Inventory result changed the exact admitted build.";
                          emit();
                          return;
                        }
                        prepared = result;
                        preparationProgress = operation.progress();
                        emit();
                        if (current(requestEpoch)) readMode(true);
                      }));
    } catch (RuntimeException failure) {
      if (current(requestEpoch)) {
        preparing = null;
        preparationError = "Inventory preparation failed. Retry preparation.";
        emit();
      }
    }
  }

  /**
   * Settings hides observations without changing the admitted session or durable background intent.
   */
  public void setVisible(boolean next) {
    visible = next;
  }

  public void retryPreparation() {
    if (closed || admission == null || preparing != null) return;
    replaceAdmission(admission);
  }

  public void refresh() {
    if (closed || prepared == null) return;
    readMode(false);
  }

  public void requestMode(BuildRetention.Mode requested) {
    Objects.requireNonNull(requested);
    if (closed || retiring || prepared == null || changing != null || modeView == null) return;
    var policy =
        prepared.downloadPolicy().or(() -> modeView.intent().map(BuildRetention.Intent::policy));
    if (policy.isEmpty()) {
      modeError = "No application-issued download policy is available for this build.";
      emit();
      return;
    }
    long generation = modeView.intent().map(BuildRetention.Intent::generation).orElse(0L);
    if (snapshot != null && snapshot.retention().isPresent())
      generation = Math.max(generation, snapshot.retention().orElseThrow().generation());
    long expectedGeneration = generation;
    beginMode(
        () -> modes.setMode(prepared.target(), expectedGeneration, requested, policy.orElseThrow()),
        true);
  }

  private void readMode(boolean resume) {
    if (closed || prepared == null || changing != null) return;
    var target = prepared.target();
    beginMode(() -> resume ? modes.resume(target) : modes.read(target), resume);
  }

  public void retryActivation() {
    if (modeView != null && modeView.activationFailure().isPresent()) readMode(true);
  }

  private void beginMode(Supplier<BuildRetentionModeService.Operation> begin, boolean activates) {
    long requestEpoch = epoch, generation = ++modeGeneration;
    var target = prepared.target();
    modeError = "";
    try {
      var operation = Objects.requireNonNull(begin.get());
      if (!current(requestEpoch) || generation != modeGeneration) {
        cancel(operation::requestCancellation);
        return;
      }
      changing = operation;
      emit();
      operation
          .completion()
          .whenComplete(
              (result, failure) ->
                  presentation.execute(
                      () -> {
                        if (!current(requestEpoch)
                            || generation != modeGeneration
                            || changing != operation) return;
                        changing = null;
                        if (failure != null) {
                          modeError =
                              "Download preference could not be updated or restored. Refresh to"
                                  + " retry.";
                        } else if (result == null || !target.equals(result.target())) {
                          modeError = "Download preference returned another inventory target.";
                        } else {
                          var prior = modeView;
                          if (!activates
                              && result.activationFailure().isEmpty()
                              && prior != null
                              && prior.activationFailure().isPresent()
                              && prior
                                  .intent()
                                  .map(BuildRetention.Intent::generation)
                                  .equals(result.intent().map(BuildRetention.Intent::generation))) {
                            modeView =
                                new BuildRetentionModeService.View(
                                    result.target(), result.intent(), prior.activationFailure());
                          } else modeView = result;
                          modeError =
                              modeView
                                  .activationFailure()
                                  .map(
                                      f ->
                                          "Download preference is saved; background activation"
                                              + " needs attention ("
                                              + f.code()
                                              + ").")
                                  .orElse("");
                        }
                        emit();
                        if (current(requestEpoch)) readOverview();
                      }));
    } catch (RuntimeException failure) {
      if (current(requestEpoch) && generation == modeGeneration) {
        changing = null;
        modeError = "Download preference could not be updated or restored. Refresh to retry.";
        emit();
        readOverview();
      }
    }
  }

  private void readOverview() {
    if (closed || prepared == null) return;
    if (reading != null) {
      refreshAfterRead = true;
      return;
    }
    long requestEpoch = epoch, generation = ++readGeneration;
    var request =
        new BuildOverview.Request(
            admission.reference(),
            admission.locale(),
            admission.scopeRevision(),
            limits,
            Optional.of(prepared.target()));
    overviewError = "";
    lastRead = nanos.getAsLong();
    try {
      var operation = Objects.requireNonNull(overview.read(request));
      if (!current(requestEpoch) || generation != readGeneration) {
        cancel(operation::requestCancellation);
        return;
      }
      reading = operation;
      overviewProgress = operation.progress();
      emit();
      operation
          .completion()
          .whenComplete(
              (result, failure) ->
                  presentation.execute(
                      () -> {
                        if (!current(requestEpoch)
                            || generation != readGeneration
                            || reading != operation) return;
                        reading = null;
                        if (failure != null) {
                          snapshot = null;
                          overviewError = "Build coverage could not be verified. Refresh to retry.";
                        } else if (!matches(result)) {
                          snapshot = null;
                          overviewError = "Build coverage returned another admitted scope.";
                        } else {
                          snapshot = result;
                          overviewProgress = operation.progress();
                        }
                        emit();
                        if (current(requestEpoch) && refreshAfterRead) {
                          refreshAfterRead = false;
                          readOverview();
                        }
                      }));
    } catch (RuntimeException failure) {
      if (current(requestEpoch) && generation == readGeneration) {
        reading = null;
        snapshot = null;
        overviewError = "Build coverage could not be verified. Refresh to retry.";
        emit();
      }
    }
  }

  private boolean matches(BuildOverview.Snapshot result) {
    return result != null
        && admission.reference().equals(result.reference())
        && admission.locale().equals(result.locale())
        && admission.scopeRevision().equals(result.scopeRevision())
        && (result.target().isEmpty() || prepared.target().equals(result.target().orElseThrow()));
  }

  /** Cheap progress sampling; bounded physical overview verification is never run on each tick. */
  public void tick() {
    if (closed || admission == null || !visible) return;
    boolean changed = false;
    if (preparing != null) {
      var progress = preparing.progress();
      if (!Objects.equals(progress, preparationProgress)) {
        preparationProgress = progress;
        changed = true;
      }
    }
    if (reading != null) {
      var progress = reading.progress();
      if (!Objects.equals(progress, overviewProgress)) {
        overviewProgress = progress;
        changed = true;
      }
    }
    if (changed) emit();
    if (reading == null
        && changing == null
        && overviewError.isBlank()
        && (snapshot == null || snapshot.readiness() != BuildOverview.Readiness.FULL)
        && nanos.getAsLong() - lastRead >= REFRESH_INTERVAL
        && (enumerationPending()
            || snapshot != null
                && snapshot
                    .retention()
                    .map(r -> r.queued().orElse(0) > 0 || r.running().orElse(0) > 0)
                    .orElse(false))) readOverview();
  }

  private boolean enumerationPending() {
    if (modeView != null && modeView.activationFailure().isPresent()) return false;
    long knownGeneration =
        modeView == null ? 0 : modeView.intent().map(BuildRetention.Intent::generation).orElse(0L);
    if (snapshot != null
        && snapshot.retention().isPresent()
        && snapshot.retention().orElseThrow().generation() >= knownGeneration) {
      var retention = snapshot.retention().orElseThrow();
      return retention.mode() == BuildRetention.Mode.DOWNLOAD_ALL
          && !retention.enumerationComplete();
    }
    return modeView != null
        && modeView
            .intent()
            .map(
                intent ->
                    intent.mode() == BuildRetention.Mode.DOWNLOAD_ALL
                        && !intent.enumerationComplete())
            .orElse(false);
  }

  /**
   * Projects only supplied application observations; ledger success is never a physical Full proof.
   */
  public BuildOverviewPane.ViewData viewData(BuildOverviewPane.ViewData baseline) {
    Objects.requireNonNull(baseline);
    if (admission == null) return baseline;
    var source = admission.reference();
    var identity = baseline.identity();
    if (!identity.contextId().equals(source.snapshotId() + ":" + admission.locale())
        || !identity.productId().equals(source.key().productId())
        || !identity.exactBuild().equals(source.key().versionLabel())
        || !identity.channel().equals(source.key().channel())
        || !identity.region().equals(source.region())
        || !identity.locale().equals(admission.locale())
        || !baseline.scope().revision().equals(admission.scopeRevision()))
      throw new IllegalArgumentException(
          "Overview baseline differs from the exact admitted source");
    var observed = snapshot;
    var metrics = baseline.metrics();
    var readiness = baseline.readiness();
    var metadata = baseline.metadata();
    String revision =
        prepared == null ? baseline.scope().revision() : prepared.target().scopeCommitment();
    if (observed != null) {
      var m = observed.measurements();
      metrics =
          new BuildOverviewPane.Metrics(
              revision,
              observed.measuredAt(),
              m.distinctFileIds(),
              m.variantMemberships(),
              m.plannedEncodedBytes(),
              m.retainedScopeEncodedBytes(),
              m.denominatorComplete(),
              m.localPhysicalBytes(),
              m.sharedPhysicalBytes(),
              m.metadataDerivedBytes());
      readiness = BuildOverviewPane.Readiness.valueOf(observed.readiness().name());
      metadata = BuildOverviewPane.MetadataState.valueOf(observed.metadata().name());
    } else if (!revision.equals(metrics.scopeRevision())) {
      metrics =
          new BuildOverviewPane.Metrics(
              revision,
              metrics.measuredAt(),
              metrics.distinctFileIds(),
              metrics.variantMemberships(),
              metrics.plannedEncodedBytes(),
              metrics.retainedScopeEncodedBytes(),
              metrics.denominatorComplete(),
              metrics.localPhysicalBytes(),
              metrics.sharedPhysicalBytes(),
              metrics.metadataDerivedBytes());
    }
    var inventory =
        preparing != null
            ? BuildOverviewPane.InventoryState.PREPARING
            : observed != null
                ? BuildOverviewPane.InventoryState.valueOf(observed.inventory().name())
                : prepared != null
                    ? BuildOverviewPane.InventoryState.SEALED
                    : !preparationError.isBlank()
                        ? BuildOverviewPane.InventoryState.NEEDS_ATTENTION
                        : BuildOverviewPane.InventoryState.UNKNOWN;
    BuildRetention.Mode actualMode =
        modeView == null ? BuildRetention.Mode.ON_DEMAND : modeView.mode();
    if (observed != null && observed.retention().isPresent()) {
      var retained = observed.retention().orElseThrow();
      long knownGeneration =
          modeView == null
              ? 0
              : modeView.intent().map(BuildRetention.Intent::generation).orElse(0L);
      if (retained.generation() > knownGeneration) actualMode = retained.mode();
    }
    String activity = "";
    String job = "";
    if (preparing != null) {
      activity = "Preparing inventory";
      job =
          preparationProgress == null
              ? "Inventory preparation queued."
              : preparationProgress.stage().name().replace('_', ' ')
                  + " · "
                  + (preparationProgress.processedReferences().isPresent()
                      ? preparationProgress.processedReferences().getAsLong()
                      : "unknown")
                  + " of "
                  + (preparationProgress.totalReferences().isPresent()
                      ? preparationProgress.totalReferences().getAsLong()
                      : "unknown")
                  + " origin references processed.";
    } else if (changing != null) {
      activity = "Updating preference";
    } else if (reading != null) {
      activity = "Verifying coverage";
      job =
          overviewProgress == null
              ? "Bounded local verification queued."
              : overviewProgress.stage().name().replace('_', ' ')
                  + " · "
                  + overviewProgress.checkedMembers()
                  + " of "
                  + (overviewProgress.totalMembers().isPresent()
                      ? overviewProgress.totalMembers().getAsLong()
                      : "unknown")
                  + " acquisition units checked.";
    } else if (modeView != null && modeView.activationFailure().isPresent()) {
      activity = "Needs attention";
    } else if (actualMode == BuildRetention.Mode.DOWNLOAD_ALL && enumerationPending()) {
      activity = "Preparing download queue";
    } else if (observed != null && observed.retention().isPresent()) {
      var retention = observed.retention().orElseThrow();
      if (retention.queued().isPresent() && retention.running().isPresent()) {
        activity =
            retention.queued().getAsLong()
                + " queued · "
                + retention.running().getAsLong()
                + " active";
        if (retention.failed().isPresent() && retention.failed().getAsLong() > 0)
          activity = "Needs attention";
      } else activity = "Activity unknown";
    } else if (actualMode == BuildRetention.Mode.DOWNLOAD_ALL) activity = "Activity unknown";
    var problems = new ArrayList<String>();
    if (observed != null)
      observed
          .problems()
          .forEach(problem -> problems.add(problem.code() + ": " + problem.message()));
    if (!state().error().isBlank()) problems.add(state().error());
    boolean hasPolicy =
        prepared != null
            && (prepared.downloadPolicy().isPresent()
                || modeView != null && modeView.intent().isPresent());
    boolean modeAvailable = prepared != null && modeView != null && hasPolicy;
    String reason =
        prepared == null
            ? "Download policy becomes available after inventory preparation."
            : modeView == null
                ? "Download preference has not been read. Refresh coverage to retry."
                : !hasPolicy
                    ? "No application-issued download budget is available for this scope."
                    : "Download all applies to all declared languages and platforms in this build.";
    return new BuildOverviewPane.ViewData(
        baseline.identity(),
        new BuildOverviewPane.Scope(revision, baseline.scope().description()),
        readiness,
        metadata,
        metrics,
        BuildOverviewPane.DownloadMode.valueOf(actualMode.name()),
        activity,
        job,
        problems,
        new BuildOverviewPane.Capabilities(
            modeAvailable,
            preparing != null || changing != null,
            false,
            modeView != null && modeView.activationFailure().isPresent(),
            reason),
        inventory,
        prepared == null && preparing == null && !preparationError.isBlank(),
        prepared != null,
        reading != null || changing != null);
  }

  public void retire() {
    if (retiring) return;
    retiring = true;
    epoch++;
    readGeneration++;
    modeGeneration++;
    var p = preparing;
    var r = reading;
    var m = changing;
    preparing = null;
    reading = null;
    changing = null;
    admission = null;
    prepared = null;
    snapshot = null;
    modeView = null;
    preparationProgress = null;
    overviewProgress = null;
    refreshAfterRead = false;
    preparationError = "";
    overviewError = "";
    modeError = "";
    if (p != null) cancel(p::requestCancellation);
    if (r != null) cancel(r::requestCancellation);
    if (m != null) cancel(m::requestCancellation);
    retiring = false;
    if (!closed) emit();
  }

  private boolean current(long value) {
    return !closed && !retiring && epoch == value && admission != null;
  }

  private static void cancel(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ignored) {
      /* Local generation fencing remains authoritative. */
    }
  }

  private void emit() {
    if (!closed) observer.accept(state());
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
  }
}
