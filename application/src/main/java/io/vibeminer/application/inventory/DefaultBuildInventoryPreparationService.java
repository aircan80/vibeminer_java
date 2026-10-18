package io.vibeminer.application.inventory;

import static io.vibeminer.application.inventory.BuildInventoryPreparationException.Code.*;
import static io.vibeminer.application.inventory.BuildInventoryPreparationProgress.Stage.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Four bounded actual jobs; executor, local sources and persistence remain borrowed. */
public final class DefaultBuildInventoryPreparationService
    implements BuildInventoryPreparationService {
  public static final int MAXIMUM_ACTUAL_JOBS = 4;
  private final BuildInventoryPreparationBackend backend;
  private final Executor executor;
  private final Set<Job> active = new HashSet<>();
  private volatile boolean closed;

  private static final class Job {
    final CompletableFuture<BuildInventoryPreparationResult> result = new CompletableFuture<>();
    final AtomicBoolean cancelled = new AtomicBoolean();
    volatile BuildInventoryPreparationProgress progress =
        BuildInventoryPreparationProgress.initial();
    boolean started, acceptingProgress;
    BuildInventoryPreparationException invalidProgress;
    long highestProcessed;
    OptionalLong knownTotal = OptionalLong.empty();
  }

  public DefaultBuildInventoryPreparationService(
      BuildInventoryPreparationBackend backend, Executor executor) {
    this.backend = Objects.requireNonNull(backend);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public Operation prepare(BuildMetadataAdmission admission) {
    var job = new Job();
    if (admission == null) {
      reject(job, error(INVALID_REQUEST, "Exact complete metadata admission required"));
      return operation(job);
    }
    synchronized (this) {
      if (closed) reject(job, error(CLOSED, "Inventory preparation service is closed"));
      else if (active.size() >= MAXIMUM_ACTUAL_JOBS)
        reject(job, error(BUSY, "Four inventory preparation jobs are still active"));
      else active.add(job);
    }
    if (job.result.isDone()) return operation(job);
    try {
      executor.execute(() -> run(job, admission));
    } catch (RuntimeException | Error rejection) {
      boolean unstarted;
      synchronized (this) {
        unstarted = !job.started;
        if (unstarted) active.remove(job);
      }
      if (unstarted)
        reject(
            job,
            new BuildInventoryPreparationException(
                BUSY, "Inventory executor rejected admission", rejection));
      if (rejection instanceof Error fatal) throw fatal;
    }
    return operation(job);
  }

  private void run(Job job, BuildMetadataAdmission admission) {
    synchronized (this) {
      job.started = true;
      job.acceptingProgress = true;
      job.progress =
          new BuildInventoryPreparationProgress(
              REVALIDATING_ADMISSION, OptionalLong.empty(), OptionalLong.empty(), Optional.empty());
    }
    BuildInventoryPreparationResult result = null;
    Throwable failure = null;
    CancellationToken token = () -> closed || job.cancelled.get();
    try {
      token.throwIfCancellationRequested();
      result = backend.prepare(admission, p -> update(job, p), token);
      // The trusted backend owns issuance. Never relabel another context or fabricate a target.
      synchronized (this) {
        if (job.invalidProgress != null) throw job.invalidProgress;
        if (result == null
            || !result.admission().equals(admission)
            || !result.target().groupId().equals(admission.reference().key().identity())
            || !result.target().snapshotId().equals(admission.reference().snapshotId())
            || job.progress.stage() != ISSUING_TARGET
            || job.progress.totalReferences().isPresent()
                && job.progress.processedReferences().getAsLong()
                    != job.progress.totalReferences().getAsLong())
          throw error(
              CORRUPT_INVENTORY,
              "Backend changed context or omitted complete inventory/target issuance");
      }
      // A returned, validated issued target wins late cancellation at its durable commit boundary.
    } catch (Throwable failed) {
      failure = failed;
    } finally {
      synchronized (this) {
        job.acceptingProgress = false;
        active.remove(job);
        // Only service retirement publishes a terminal stage, after all backend cleanup returned.
        job.progress = terminal(job.progress, failure);
      }
    }
    if (failure == null) job.result.complete(result);
    else job.result.completeExceptionally(failure);
  }

  private void update(Job job, BuildInventoryPreparationProgress progress) {
    synchronized (this) {
      if (!job.acceptingProgress) return;
      if (job.invalidProgress != null) throw job.invalidProgress;
      if (progress == null
          || progress.stage() == QUEUED
          || progress.stage().ordinal() >= COMPLETE.ordinal()
          || progress.stage().ordinal() < job.progress.stage().ordinal()
          || progress.processedReferences().isPresent()
              && progress.processedReferences().getAsLong() < job.highestProcessed
          || job.progress.processedReferences().isPresent()
              && progress.processedReferences().isEmpty()
          || job.knownTotal.isPresent()
              && (progress.totalReferences().isEmpty()
                  || progress.totalReferences().getAsLong() != job.knownTotal.getAsLong())) {
        job.invalidProgress =
            error(CORRUPT_INVENTORY, "Inventory progress regressed or claimed terminal readiness");
        throw job.invalidProgress;
      }
      if (progress.processedReferences().isPresent())
        job.highestProcessed = progress.processedReferences().getAsLong();
      if (progress.totalReferences().isPresent()) job.knownTotal = progress.totalReferences();
      job.progress = progress;
    }
  }

  private static BuildInventoryPreparationProgress terminal(
      BuildInventoryPreparationProgress prior, Throwable failure) {
    var stage =
        failure == null ? COMPLETE : failure instanceof CancellationException ? CANCELLED : FAILED;
    var code =
        failure == null || failure instanceof CancellationException
            ? Optional.<BuildInventoryPreparationException.Code>empty()
            : Optional.of(
                failure instanceof BuildInventoryPreparationException typed
                    ? typed.code()
                    : STORAGE_UNAVAILABLE);
    return new BuildInventoryPreparationProgress(
        stage, prior.processedReferences(), prior.totalReferences(), code);
  }

  private static void reject(Job job, BuildInventoryPreparationException failure) {
    job.progress = terminal(job.progress, failure);
    job.result.completeExceptionally(failure);
  }

  private static Operation operation(Job job) {
    var readOnly = job.result.minimalCompletionStage();
    return new Operation() {
      public CompletionStage<BuildInventoryPreparationResult> completion() {
        return readOnly;
      }

      public BuildInventoryPreparationProgress progress() {
        return job.progress;
      }

      public void requestCancellation() {
        job.cancelled.set(true);
      }
    };
  }

  private static BuildInventoryPreparationException error(
      BuildInventoryPreparationException.Code code, String message) {
    return new BuildInventoryPreparationException(code, message);
  }

  @Override
  public void close() {
    List<Job> jobs;
    synchronized (this) {
      if (closed) return;
      closed = true;
      jobs = List.copyOf(active);
    }
    jobs.forEach(job -> job.cancelled.set(true));
  }
}
