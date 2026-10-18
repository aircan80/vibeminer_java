package io.vibeminer.application.buildreadiness;

import io.vibeminer.application.CancellationToken;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Borrowed executor, four actual SQL workers; no source reads, implicit discovery or retained
 * cache.
 */
public final class DefaultBuildReadinessService implements BuildReadinessService {
  private final BuildReadinessBackend backend;
  private final Executor executor;
  private final Set<Job> active = new HashSet<>();
  private volatile boolean closed;

  private static final class Job {
    final CompletableFuture<List<BuildReadiness.Status>> result = new CompletableFuture<>();
    final AtomicBoolean canceled = new AtomicBoolean();
    List<BuildReadiness.Context> contexts;
    boolean started;
  }

  public DefaultBuildReadinessService(BuildReadinessBackend backend, Executor executor) {
    this.backend = Objects.requireNonNull(backend);
    this.executor = Objects.requireNonNull(executor);
  }

  public Operation read(List<BuildReadiness.Context> contexts) {
    var job = new Job();
    try {
      job.contexts = BuildReadiness.contexts(contexts);
    } catch (RuntimeException failure) {
      job.result.completeExceptionally(
          new BuildReadinessException(
              BuildReadinessException.Code.INVALID_REQUEST,
              "Bounded exact visible contexts required",
              failure));
      return operation(job);
    }
    synchronized (this) {
      if (closed)
        job.result.completeExceptionally(
            error(BuildReadinessException.Code.CLOSED, "Readiness overlay closed"));
      else if (active.size() >= 4)
        job.result.completeExceptionally(
            error(BuildReadinessException.Code.BUSY, "Four overlay workers remain active"));
      else active.add(job);
    }
    if (job.result.isDone()) return operation(job);
    try {
      executor.execute(() -> run(job));
    } catch (RuntimeException | Error failure) {
      synchronized (this) {
        if (!job.started) {
          active.remove(job);
          job.result.completeExceptionally(
              new BuildReadinessException(
                  BuildReadinessException.Code.BUSY, "Overlay executor rejected work", failure));
        }
      }
    }
    return operation(job);
  }

  private void run(Job job) {
    synchronized (this) {
      if (job.started) return;
      job.started = true;
    }
    List<BuildReadiness.Status> result = null;
    Throwable failure = null;
    CancellationToken token = () -> closed || job.canceled.get();
    try {
      token.throwIfCancellationRequested();
      var supplied =
          job.contexts.isEmpty()
              ? List.<BuildReadiness.Status>of()
              : backend.read(job.contexts, token);
      if (supplied == null || supplied.stream().anyMatch(Objects::isNull))
        throw error(BuildReadinessException.Code.CORRUPT_RESULT, "Overlay omitted typed statuses");
      result = List.copyOf(supplied);
      token.throwIfCancellationRequested();
      if (result.size() != job.contexts.size())
        throw error(BuildReadinessException.Code.CORRUPT_RESULT, "Overlay omitted contexts");
      for (int i = 0; i < result.size(); i++)
        if (!result.get(i).context().equals(job.contexts.get(i)))
          throw error(
              BuildReadinessException.Code.CORRUPT_RESULT, "Overlay changed exact visible context");
    } catch (Throwable thrown) {
      failure = thrown;
    } finally {
      synchronized (this) {
        active.remove(job);
        if (failure == null && token.isCancellationRequested())
          failure = new CancellationException("Overlay canceled");
      }
    }
    if (failure == null) job.result.complete(result);
    else job.result.completeExceptionally(failure);
  }

  private static Operation operation(Job job) {
    var completion = job.result.minimalCompletionStage();
    return new Operation() {
      public CompletionStage<List<BuildReadiness.Status>> completion() {
        return completion;
      }

      public void requestCancellation() {
        job.canceled.set(true);
      }
    };
  }

  public void close() {
    synchronized (this) {
      closed = true;
      active.forEach(job -> job.canceled.set(true));
    }
  }

  private static BuildReadinessException error(BuildReadinessException.Code code, String message) {
    return new BuildReadinessException(code, message);
  }
}
