package io.vibeminer.application.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded borrowed-worker orchestration for already authorized durable intents. Source proof,
 * publication and actual consumer retirement remain the source adapter's responsibility. No
 * completion here is a Full/current-physical-readiness assertion.
 */
public final class BuildRetentionPump implements BuildRetentionActivation, AutoCloseable {
  public static final int MAXIMUM_HANDLES = 16;

  public enum Outcome {
    QUEUE_DRAINED,
    BLOCKED,
    WITHDRAWN
  }

  /** Queue drained can include historical failures; blocked retains the actual diagnostic cause. */
  public record Result(Outcome outcome, Optional<Throwable> failure) {
    public Result {
      Objects.requireNonNull(outcome);
      Objects.requireNonNull(failure);
    }
  }

  private record Key(Target target, long generation) {}

  private final class Run {
    final Intent accepted;
    final PrioritySourceScheduler.BulkOwner owner;
    final AtomicBoolean withdrawn = new AtomicBoolean();
    final CompletableFuture<Result> completion = new CompletableFuture<>();
    boolean eligible = true, terminal;
    int inFlight;
    Result pending;

    Run(Intent accepted, PrioritySourceScheduler.BulkOwner owner) {
      this.accepted = accepted;
      this.owner = owner;
    }

    CancellationToken cancellation() {
      return () -> closed || withdrawn.get();
    }
  }

  private static final class Ticket {
    boolean started, retired;
  }

  private enum Progress {
    MEMBER,
    EMPTY,
    BLOCKED,
    WITHDRAWN
  }

  private record Attempt(Progress progress, Throwable failure) {}

  private final Object monitor = new Object();
  private final BuildRetentionRepository repository;
  private final BuildRetentionSource source;
  private final PrioritySourceScheduler scheduler;
  private final Executor executor;
  private final LinkedHashMap<Key, Run> runs = new LinkedHashMap<>();
  private int slots, cursor;
  private boolean dispatching;
  private volatile boolean closed;

  public BuildRetentionPump(
      BuildRetentionRepository repository,
      BuildRetentionSource source,
      PrioritySourceScheduler scheduler,
      Executor executor) {
    this.repository = Objects.requireNonNull(repository);
    this.source = Objects.requireNonNull(source);
    this.scheduler = Objects.requireNonNull(scheduler);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public void resumeDownloadAll(Intent accepted) {
    Objects.requireNonNull(accepted);
    // Persist-before-activate is an authority boundary, not an arbitrary caller hint.
    var current =
        repository
            .readIntent(accepted.target(), CancellationToken.none())
            .orElseThrow(
                () ->
                    new BuildRetentionException(
                        BuildRetentionException.Code.NOT_FOUND, "Persisted intent required"));
    if (!current.equals(accepted) || current.mode() != Mode.DOWNLOAD_ALL)
      throw new BuildRetentionException(
          BuildRetentionException.Code.STALE_GENERATION,
          "Exact persisted Download all intent required");
    var owner = scheduler.newBulkOwner(accepted.target().snapshotId());
    boolean installed = false;
    try {
      synchronized (monitor) {
        if (closed) throw new IllegalStateException("Build retention pump closed");
        var key = new Key(accepted.target(), accepted.generation());
        var prior = runs.get(key);
        if (prior != null && !prior.terminal) return;
        if (prior != null) runs.remove(key);
        if (runs.size() == MAXIMUM_HANDLES) {
          var oldest = runs.entrySet().stream().filter(e -> e.getValue().terminal).findFirst();
          if (oldest.isEmpty())
            throw new BuildRetentionException(
                BuildRetentionException.Code.LIMIT_EXCEEDED, "Retention handle limit reached");
          runs.remove(oldest.get().getKey());
        }
        runs.put(key, new Run(accepted, owner));
        installed = true;
      }
    } finally {
      if (!installed) scheduler.cancelBulk(owner);
    }
    dispatch();
  }

  /** Observation of this bounded handle; completion cancellation cannot retire actual work. */
  public Optional<CompletionStage<Result>> completion(Target target, long generation) {
    synchronized (monitor) {
      var run = runs.get(new Key(target, generation));
      return run == null ? Optional.empty() : Optional.of(run.completion.minimalCompletionStage());
    }
  }

  @Override
  public void withdraw(Target target, long priorGeneration) {
    Run run;
    synchronized (monitor) {
      run = runs.get(new Key(target, priorGeneration));
      if (run == null || run.terminal) return;
      run.withdrawn.set(true);
      run.eligible = false;
    }
    scheduler.cancelBulk(run.owner);
    completeIfIdle(run, new Result(Outcome.WITHDRAWN, Optional.empty()));
  }

  private boolean eligible() {
    return runs.values().stream().anyMatch(r -> !r.terminal && r.eligible && !r.withdrawn.get());
  }

  /** The dispatch guard supports direct/reentrant executors without recursive worker creation. */
  private void dispatch() {
    synchronized (monitor) {
      if (dispatching) return;
      dispatching = true;
    }
    try {
      while (true) {
        Ticket ticket;
        synchronized (monitor) {
          if (closed || slots >= MAXIMUM_RUNNING || !eligible()) return;
          slots++;
          ticket = new Ticket();
        }
        try {
          executor.execute(() -> work(ticket));
        } catch (RuntimeException | Error rejection) {
          boolean rejected;
          synchronized (monitor) {
            rejected = !ticket.started && !ticket.retired;
            if (rejected) {
              ticket.retired = true;
              slots--;
            }
          }
          if (rejected) blockEligible(rejection);
          // A direct worker's Error has already retired its actual lease and blocked its handle.
        }
      }
    } finally {
      boolean again;
      synchronized (monitor) {
        dispatching = false;
        again = !closed && slots < MAXIMUM_RUNNING && eligible();
      }
      if (again) dispatch();
    }
  }

  private Run next() {
    synchronized (monitor) {
      if (closed) return null;
      var candidates = new ArrayList<>(runs.values());
      for (int n = 0; n < candidates.size(); n++) {
        int index = Math.floorMod(cursor++, candidates.size());
        var run = candidates.get(index);
        if (!run.terminal && run.eligible && !run.withdrawn.get()) {
          run.inFlight++;
          return run;
        }
      }
      return null;
    }
  }

  private void work(Ticket ticket) {
    synchronized (monitor) {
      if (ticket.retired) return;
      ticket.started = true;
    }
    try {
      Run run;
      while ((run = next()) != null) {
        Attempt result = attempt(run);
        Result terminal = null;
        synchronized (monitor) {
          run.inFlight--;
          if (result.progress() == Progress.BLOCKED && run.pending == null) {
            run.pending = new Result(Outcome.BLOCKED, Optional.of(result.failure()));
            run.withdrawn.set(true);
          }
          if (run.withdrawn.get() || closed || result.progress() == Progress.WITHDRAWN) {
            run.eligible = false;
            run.withdrawn.set(true);
            if (run.inFlight == 0)
              terminal =
                  run.pending != null
                      ? run.pending
                      : new Result(Outcome.WITHDRAWN, Optional.empty());
          } else if (result.progress() == Progress.EMPTY) {
            run.eligible = false;
            if (run.inFlight == 0)
              terminal =
                  result.failure() == null
                      ? new Result(Outcome.QUEUE_DRAINED, Optional.empty())
                      : new Result(Outcome.BLOCKED, Optional.of(result.failure()));
          } else run.eligible = true;
        }
        if (result.progress() == Progress.BLOCKED || result.progress() == Progress.WITHDRAWN)
          scheduler.cancelBulk(run.owner);
        if (terminal != null) completeIfIdle(run, terminal);
      }
    } finally {
      synchronized (monitor) {
        if (!ticket.retired) {
          ticket.retired = true;
          slots--;
        }
      }
      dispatch();
    }
  }

  private Attempt attempt(Run run) {
    var token = run.cancellation();
    Job job;
    try {
      token.throwIfCancellationRequested();
      if (!authorized(run)) return new Attempt(Progress.WITHDRAWN, null);
      var filled =
          repository.fillWindow(
              run.accepted.target(), run.accepted.generation(), MAXIMUM_FILL, token);
      token.throwIfCancellationRequested();
      if (!authorized(run)) return new Attempt(Progress.WITHDRAWN, null);
      var claimed = repository.claimNext(run.accepted.target(), run.accepted.generation(), token);
      if (claimed.isEmpty()) {
        boolean pending = repository.hasPending(run.accepted.target(), token);
        if (!authorized(run)) return new Attempt(Progress.WITHDRAWN, null);
        return new Attempt(
            Progress.EMPTY,
            filled.enumerationComplete() && !pending
                ? null
                : new BuildRetentionException(
                    BuildRetentionException.Code.BUSY, "Durable retention work remains pending"));
      }
      job = claimed.get();
    } catch (CancellationException cancelled) {
      return new Attempt(Progress.WITHDRAWN, null);
    } catch (Throwable failure) {
      return new Attempt(Progress.BLOCKED, failure);
    }
    Throwable failure = null;
    Finish result = null;
    boolean cleanupFailed = false;
    try {
      if (!job.target().equals(run.accepted.target())
          || job.state() != State.RUNNING
          || job.lease().orElseThrow().modeGeneration() != run.accepted.generation())
        throw new BuildRetentionException(
            BuildRetentionException.Code.CORRUPT_STATE, "Claim changed exact target or generation");
      token.throwIfCancellationRequested();
      var proof = source.retain(job.target(), job.member(), run.owner.demand(), token);
      if (proof == null || !proof.matches("[0-9a-f]{64}"))
        throw new BuildRetentionException(
            BuildRetentionException.Code.CORRUPT_STATE,
            "Source returned invalid fulfillment commitment");
      result = new Finish(State.SUCCEEDED, Optional.of(proof), Optional.empty());
    } catch (Throwable failed) {
      failure = failed;
      result = new Finish(State.FAILED, Optional.empty(), Optional.of(failure(failed)));
    } finally {
      // retain returns/throws only after actual consumer retirement. Cancellation is never ledger
      // retirement.
      try {
        repository.finish(job.lease().orElseThrow(), result, CancellationToken.none());
      } catch (Throwable cleanup) {
        cleanupFailed = true;
        if (failure != null && failure != cleanup) cleanup.addSuppressed(failure);
        failure = cleanup;
      }
    }
    return cleanupFailed || failure instanceof Error
        ? new Attempt(Progress.BLOCKED, failure)
        : new Attempt(Progress.MEMBER, null);
  }

  private boolean authorized(Run run) {
    var found = repository.readIntent(run.accepted.target(), run.cancellation());
    if (found.isEmpty()) return false;
    var current = found.get();
    var accepted = run.accepted;
    return current.target().equals(accepted.target())
        && current.mode() == Mode.DOWNLOAD_ALL
        && current.generation() == accepted.generation()
        && current.policy().equals(accepted.policy())
        && current.authorizedAt().equals(accepted.authorizedAt())
        && current.memberCount() == accepted.memberCount();
  }

  private static Failure failure(Throwable failed) {
    var code = FailureCode.STORAGE;
    if (failed instanceof CancellationException) code = FailureCode.CANCELLED;
    else if (failed instanceof BuildRetentionException typed)
      code =
          switch (typed.code()) {
            case QUOTA_EXCEEDED, LIMIT_EXCEEDED -> FailureCode.QUOTA;
            case CORRUPT_STATE -> FailureCode.CORRUPT;
            case NOT_FOUND -> FailureCode.UNAVAILABLE;
            case INVALID_REQUEST, NOT_READY, STALE_GENERATION -> FailureCode.UNSUPPORTED;
            default -> FailureCode.STORAGE;
          };
    // Do not copy arbitrary exception text into durable records (it may contain paths/secrets).
    return new Failure(code, "Build content retention attempt failed: " + code);
  }

  private void completeIfIdle(Run run, Result result) {
    synchronized (monitor) {
      if (run.terminal || run.inFlight != 0) return;
      run.terminal = true;
      run.eligible = false;
    }
    scheduler.cancelBulk(run.owner);
    run.completion.complete(result);
  }

  private void blockEligible(Throwable failure) {
    List<Run> blocked;
    synchronized (monitor) {
      blocked = runs.values().stream().filter(r -> !r.terminal).toList();
      for (var r : blocked) {
        r.eligible = false;
        r.withdrawn.set(true);
        r.pending = new Result(Outcome.BLOCKED, Optional.of(failure));
      }
    }
    for (var r : blocked) {
      scheduler.cancelBulk(r.owner);
      completeIfIdle(r, new Result(Outcome.BLOCKED, Optional.of(failure)));
    }
  }

  @Override
  public void close() {
    List<Run> closing;
    synchronized (monitor) {
      if (closed) return;
      closed = true;
      closing = runs.values().stream().filter(r -> !r.terminal).toList();
      for (var r : closing) {
        r.withdrawn.set(true);
        r.eligible = false;
      }
    }
    for (var r : closing) {
      scheduler.cancelBulk(r.owner);
      completeIfIdle(r, new Result(Outcome.WITHDRAWN, Optional.empty()));
    }
  }
}
