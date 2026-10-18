package io.vibeminer.application.buildoverview;

import static io.vibeminer.application.buildoverview.BuildOverview.*;
import static io.vibeminer.application.buildoverview.BuildOverviewException.Code.*;

import io.vibeminer.application.CancellationToken;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Borrowed executor, four actual queued/running workers, no retained readiness cache. */
public final class DefaultBuildOverviewService implements BuildOverviewService {
  private final BuildOverviewBackend backend;
  private final Executor executor;
  private final Set<Job> active = new HashSet<>();
  private volatile boolean closed;

  private static final class Job {
    final Request request;
    final CompletableFuture<Snapshot> result = new CompletableFuture<>();
    final AtomicBoolean cancellation = new AtomicBoolean();
    volatile Progress progress = Progress.initial();
    Progress latestReported = Progress.initial();
    boolean started, acceptingProgress;
    long startedAt;

    Job(Request request) {
      this.request = request;
    }
  }

  public DefaultBuildOverviewService(BuildOverviewBackend backend, Executor executor) {
    this.backend = Objects.requireNonNull(backend);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public Operation read(Request request) {
    var job = new Job(request);
    if (request == null) {
      job.result.completeExceptionally(error(INVALID_REQUEST, "Exact overview request required"));
      return operation(job);
    }
    synchronized (this) {
      if (closed)
        job.result.completeExceptionally(error(CLOSED, "Build overview service is closed"));
      else if (active.size() >= 4)
        job.result.completeExceptionally(error(BUSY, "Four overview workers are still active"));
      else active.add(job);
    }
    if (job.result.isDone()) return operation(job);
    try {
      executor.execute(() -> run(job));
    } catch (RuntimeException | Error rejected) {
      synchronized (this) {
        if (!job.started) {
          active.remove(job);
          job.result.completeExceptionally(
              new BuildOverviewException(BUSY, "Overview executor rejected admission", rejected));
        }
      }
    }
    return operation(job);
  }

  private void run(Job job) {
    synchronized (this) {
      if (job.started) return;
      job.started = true;
      job.acceptingProgress = true;
      job.startedAt = System.nanoTime();
    }
    Snapshot result = null;
    Throwable failure = null;
    CancellationToken token =
        () ->
            closed
                || job.cancellation.get()
                || System.nanoTime() - job.startedAt
                    >= job.request.limits().maximumDuration().toNanos();
    try {
      token.throwIfCancellationRequested();
      result = backend.read(job.request, p -> update(job, p, token), token);
      token.throwIfCancellationRequested();
      requireContext(job.request, result);
    } catch (Throwable failed) {
      failure = failed;
    } finally {
      synchronized (this) {
        if (failure == null) {
          if (token.isCancellationRequested())
            failure = new CancellationException("Overview canceled before terminal observation");
          else if (job.latestReported.stage() != Stage.COMPLETE
              || job.latestReported.observation().isEmpty()
              || !job.latestReported.observation().get().equals(result))
            failure = error(CORRUPT_RESULT, "Overview omitted its exact final observation");
        }
        job.acceptingProgress = false;
        active.remove(job);
        if (failure == null) job.progress = job.latestReported;
      }
    }
    if (failure == null) job.result.complete(result);
    else job.result.completeExceptionally(failure);
  }

  private void update(Job job, Progress next, CancellationToken token) {
    synchronized (this) {
      if (!job.acceptingProgress) return;
      token.throwIfCancellationRequested();
      if (next == null) throw error(CORRUPT_RESULT, "Backend omitted typed overview progress");
      var old = job.latestReported;
      if (next.stage().ordinal() < old.stage().ordinal()
          || next.checkedMembers() < old.checkedMembers()
          || next.chargedPayloadVerificationBytes() < old.chargedPayloadVerificationBytes()
          || next.checkedMembers() > job.request.limits().maximumMembers()
          || next.chargedPayloadVerificationBytes()
              > job.request.limits().maximumPayloadVerificationBytes()
          || old.totalMembers().isPresent() && (!old.totalMembers().equals(next.totalMembers()))
          || old.stage() == Stage.COMPLETE && !old.equals(next))
        throw error(CORRUPT_RESULT, "Overview progress regressed or exceeded admitted work");
      if (next.observation().isPresent()) {
        var observation = next.observation().get();
        requireContext(job.request, observation);
        if (observation.measurements().verificationComplete()
            && (next.stage() != Stage.COMPLETE
                || next.totalMembers().isEmpty()
                || next.checkedMembers() != next.totalMembers().getAsLong()
                || observation.measurements().acquisitionUnits().orElseThrow()
                    != next.checkedMembers()))
          throw error(
              CORRUPT_RESULT, "Complete verification omitted exact checked member coverage");
      }
      if (next.stage() == Stage.COMPLETE && next.observation().isEmpty())
        throw error(CORRUPT_RESULT, "Complete overview progress requires its snapshot");
      job.latestReported = next;
      if (next.stage() != Stage.COMPLETE) job.progress = next;
    }
  }

  private static void requireContext(Request request, Snapshot result) {
    if (result == null
        || !request.reference().equals(result.reference())
        || !request.locale().equals(result.locale())
        || !request.scopeRevision().equals(result.scopeRevision())
        || !request.target().equals(result.target()))
      throw error(CORRUPT_RESULT, "Overview changed exact event/build/locale/scope");
  }

  private static Operation operation(Job job) {
    var readOnly = job.result.minimalCompletionStage();
    return new Operation() {
      public CompletionStage<Snapshot> completion() {
        return readOnly;
      }

      public Progress progress() {
        return job.progress;
      }

      public void requestCancellation() {
        job.cancellation.set(true);
      }
    };
  }

  private static BuildOverviewException error(BuildOverviewException.Code code, String message) {
    return new BuildOverviewException(code, message);
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
      active.forEach(job -> job.cancellation.set(true));
    }
  }
}
