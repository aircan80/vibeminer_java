package io.vibeminer.application.buildreadiness;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildoverview.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Borrows delegate/executor. Only successful retired real operations mint historical receipts. Four
 * wrapper slots span the actual overview AND queued/running SQL recorder. Closing cancels accepted
 * work; the runtime separately owns and closes the borrowed services after retirement.
 */
public final class RecordingBuildOverviewService implements BuildOverviewService {
  private final BuildOverviewService delegate;
  private final BuildReadinessBackend recorder;
  private final Executor executor;
  private final String inventoryScope;
  private final Set<Job> active = new HashSet<>();
  private volatile boolean closed;

  private static final class Job {
    final BuildOverview.Request request;
    final CompletableFuture<BuildOverview.Snapshot> result = new CompletableFuture<>();
    final AtomicBoolean canceled = new AtomicBoolean();
    volatile Operation actual;
    volatile BuildOverview.Progress lastNonterminal = BuildOverview.Progress.initial();
    volatile boolean terminalSuccess;
    boolean recordStarted;

    Job(BuildOverview.Request request) {
      this.request = request;
    }
  }

  public RecordingBuildOverviewService(
      BuildOverviewService delegate,
      BuildReadinessBackend recorder,
      Executor executor,
      String inventoryScope) {
    this.delegate = Objects.requireNonNull(delegate);
    this.recorder = Objects.requireNonNull(recorder);
    this.executor = Objects.requireNonNull(executor);
    if (inventoryScope == null
        || inventoryScope.isBlank()
        || inventoryScope.length() > 128
        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(inventoryScope)
        || inventoryScope.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Exact inventory scope required");
    this.inventoryScope = inventoryScope;
  }

  public Operation read(BuildOverview.Request request) {
    var job = new Job(request);
    synchronized (this) {
      if (request == null)
        job.result.completeExceptionally(
            error(BuildOverviewException.Code.INVALID_REQUEST, "Exact overview request required"));
      else if (closed)
        job.result.completeExceptionally(
            error(BuildOverviewException.Code.CLOSED, "Recording overview wrapper closed"));
      else if (active.size() >= 4)
        job.result.completeExceptionally(
            error(
                BuildOverviewException.Code.BUSY, "Four overview/recorder wrappers remain active"));
      else active.add(job);
    }
    if (job.result.isDone()) return operation(job);
    try {
      var actual = Objects.requireNonNull(delegate.read(request));
      job.actual = actual;
      actual.completion().whenComplete((snapshot, failure) -> completed(job, snapshot, failure));
      if (closed || job.canceled.get()) cancel(actual);
    } catch (Throwable failure) {
      retire(job, null, failure);
    }
    return operation(job);
  }

  private void completed(Job job, BuildOverview.Snapshot snapshot, Throwable failure) {
    if (failure != null) {
      retire(job, null, failure);
      return;
    }
    if (closed || job.canceled.get()) {
      retire(job, null, new CancellationException("Overview wrapper canceled"));
      return;
    }
    CompletedAssessment assessment;
    try {
      if (snapshot == null
          || !snapshot.reference().equals(job.request.reference())
          || !snapshot.locale().equals(job.request.locale())
          || !snapshot.scopeRevision().equals(job.request.scopeRevision())
          || !snapshot.target().equals(job.request.target()))
        throw error(
            BuildOverviewException.Code.CORRUPT_RESULT,
            "Successful overview changed exact context");
      assessment = CompletedAssessment.successful(snapshot, inventoryScope);
    } catch (Throwable invalid) {
      retire(job, null, invalid);
      return;
    }
    try {
      executor.execute(() -> record(job, snapshot, assessment));
    } catch (RuntimeException | Error rejection) {
      synchronized (this) {
        if (job.recordStarted) return;
      }
      retire(job, snapshot, rejection instanceof Error ? rejection : null);
    }
  }

  private void record(Job job, BuildOverview.Snapshot snapshot, CompletedAssessment assessment) {
    synchronized (this) {
      if (job.recordStarted) return;
      job.recordStarted = true;
    }
    Throwable canceled = null;
    Error fatal = null;
    CancellationToken token = () -> closed || job.canceled.get();
    try {
      token.throwIfCancellationRequested();
      recorder.record(assessment, token);
      token.throwIfCancellationRequested();
    } catch (CancellationException failure) {
      canceled = failure;
    } catch (RuntimeException recordingFailure) {
      /* Assessment remains valid; no synthetic observation is made. */
    } catch (Error failure) {
      fatal = failure;
      canceled = failure;
    } finally {
      retire(job, snapshot, canceled);
    }
    if (fatal != null) throw fatal;
  }

  private void retire(Job job, BuildOverview.Snapshot snapshot, Throwable failure) {
    synchronized (this) {
      active.remove(job);
      if (failure == null && (closed || job.canceled.get()))
        failure = new CancellationException("Overview wrapper canceled");
      job.terminalSuccess = failure == null;
    }
    if (failure == null) job.result.complete(snapshot);
    else job.result.completeExceptionally(failure);
  }

  private Operation operation(Job job) {
    var completion = job.result.minimalCompletionStage();
    return new Operation() {
      public CompletionStage<BuildOverview.Snapshot> completion() {
        return completion;
      }

      public BuildOverview.Progress progress() {
        var actual = job.actual;
        if (actual == null) return job.lastNonterminal;
        var value = actual.progress();
        if (value.stage() != BuildOverview.Stage.COMPLETE) {
          job.lastNonterminal = value;
          return value;
        }
        return job.terminalSuccess ? value : job.lastNonterminal;
      }

      public void requestCancellation() {
        job.canceled.set(true);
        var actual = job.actual;
        if (actual != null) cancel(actual);
      }
    };
  }

  public void close() {
    List<Job> jobs;
    synchronized (this) {
      closed = true;
      jobs = List.copyOf(active);
      jobs.forEach(job -> job.canceled.set(true));
    }
    for (var job : jobs) {
      var actual = job.actual;
      if (actual != null) cancel(actual);
    }
  }

  private static void cancel(Operation operation) {
    try {
      operation.requestCancellation();
    } catch (RuntimeException | Error ignored) {
      /* Cancellation cannot prove actual retirement. Completion remains authoritative. */
    }
  }

  private static BuildOverviewException error(BuildOverviewException.Code code, String message) {
    return new BuildOverviewException(code, message);
  }
}
