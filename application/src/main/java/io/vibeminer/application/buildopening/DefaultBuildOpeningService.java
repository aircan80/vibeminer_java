package io.vibeminer.application.buildopening;

import static io.vibeminer.application.buildopening.BuildOpeningException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Four actual queued/running operations, retired before external completion callbacks. */
public final class DefaultBuildOpeningService implements BuildOpeningService {
  private final BuildOpeningGateway gateway;
  private final Executor executor;
  private final Set<Job> active = new HashSet<>();
  private volatile boolean closed;

  private static final class Job {
    final CompletableFuture<BuildMetadataAdmission> result = new CompletableFuture<>();
    final AtomicBoolean cancellation = new AtomicBoolean();
    volatile BuildOpenProgress progress = BuildOpenProgress.initial();
    boolean started, acceptingProgress;
    long highestMeasuredWireBytes;
  }

  public DefaultBuildOpeningService(BuildOpeningGateway gateway, Executor executor) {
    this.gateway = Objects.requireNonNull(gateway);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public OpenOperation open(BuildLibraryReference reference, String locale) {
    var job = new Job();
    try {
      Objects.requireNonNull(reference);
      BuildMetadataAdmission.locale(locale);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      job.result.completeExceptionally(
          new BuildOpeningException(
              INVALID_REQUEST, "Exact build reference and research locale required", invalid));
      return operation(job);
    }
    BuildOpeningException refusal = null;
    synchronized (this) {
      if (closed) refusal = error(CLOSED, "Build opening service is closed");
      else if (active.size() >= 4)
        refusal = error(BUSY, "Four build-opening workers are still active");
      else active.add(job);
    }
    if (refusal != null) {
      job.result.completeExceptionally(refusal);
      return operation(job);
    }
    try {
      executor.execute(() -> run(job, reference, locale));
    } catch (RuntimeException rejected) {
      boolean unstarted;
      synchronized (this) {
        unstarted = !job.started;
        if (unstarted) active.remove(job);
      }
      if (unstarted)
        job.result.completeExceptionally(
            new BuildOpeningException(BUSY, "Build opening executor rejected admission", rejected));
    }
    return operation(job);
  }

  private void run(Job job, BuildLibraryReference reference, String locale) {
    synchronized (this) {
      job.started = true;
      job.acceptingProgress = true;
    }
    BuildMetadataAdmission result = null;
    Throwable failure = null;
    CancellationToken token = () -> closed || job.cancellation.get();
    try {
      token.throwIfCancellationRequested();
      result = gateway.open(reference, locale, progress -> update(job, progress), token);
      // Gateway owns the commit boundary. Never turn committed success into late cancellation.
      if (result == null
          || !result.reference().equals(reference)
          || !result.locale().equals(locale)
          || job.progress.stage() != BuildOpenProgress.Stage.COMPLETE
          || job.progress.verifiedRequirements() != result.required().size())
        throw error(
            CORRUPT_METADATA,
            "Gateway changed exact admission context or omitted complete metadata proof");
    } catch (Throwable failed) {
      failure = failed;
    } finally {
      synchronized (this) {
        job.acceptingProgress = false;
        active.remove(job);
      }
    }
    if (failure == null) job.result.complete(result);
    else job.result.completeExceptionally(failure);
  }

  private void update(Job job, BuildOpenProgress progress) {
    if (progress == null) throw error(CORRUPT_METADATA, "Gateway omitted typed metadata progress");
    synchronized (this) {
      if (!job.acceptingProgress) return;
      if (progress.transferredWireBytes().isPresent()
              && progress.transferredWireBytes().getAsLong() < job.highestMeasuredWireBytes
          || progress.verifiedRequirements() < job.progress.verifiedRequirements()
          || job.progress.stage() == BuildOpenProgress.Stage.COMPLETE
              && !progress.equals(job.progress))
        throw error(CORRUPT_METADATA, "Metadata progress changed completed observations");
      if (progress.transferredWireBytes().isPresent())
        job.highestMeasuredWireBytes = progress.transferredWireBytes().getAsLong();
      job.progress = progress;
    }
  }

  private static OpenOperation operation(Job job) {
    var readOnly = job.result.minimalCompletionStage();
    return new OpenOperation() {
      public CompletionStage<BuildMetadataAdmission> completion() {
        return readOnly;
      }

      public BuildOpenProgress progress() {
        return job.progress;
      }

      public void requestCancellation() {
        job.cancellation.set(true);
      }
    };
  }

  private static BuildOpeningException error(BuildOpeningException.Code code, String message) {
    return new BuildOpeningException(code, message);
  }

  @Override
  public void close() {
    List<Job> jobs;
    synchronized (this) {
      if (closed) return;
      closed = true;
      jobs = List.copyOf(active);
    }
    jobs.forEach(job -> job.cancellation.set(true));
  }
}
