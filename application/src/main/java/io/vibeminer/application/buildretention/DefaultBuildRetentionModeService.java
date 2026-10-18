package io.vibeminer.application.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static io.vibeminer.application.buildretention.BuildRetentionException.Code.*;

import io.vibeminer.application.CancellationToken;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Four actual actions until finally retirement; no SQL, content publication or execution pool. */
public final class DefaultBuildRetentionModeService implements BuildRetentionModeService {
  private final BuildRetentionRepository repository;
  private final BuildRetentionActivation activation;
  private final Executor executor;
  private final Clock clock;
  private final Set<Job> active = new HashSet<>();
  private boolean closed;

  private static final class Job {
    final CompletableFuture<View> result = new CompletableFuture<>();
    final AtomicBoolean cancellation = new AtomicBoolean();
    boolean started;
  }

  public DefaultBuildRetentionModeService(
      BuildRetentionRepository repository,
      BuildRetentionActivation activation,
      Executor executor,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.activation = Objects.requireNonNull(activation);
    this.executor = Objects.requireNonNull(executor);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Operation read(Target target) {
    return submit(
        token -> {
          Objects.requireNonNull(target);
          var saved = repository.readIntent(target, token);
          saved.ifPresent(intent -> validate(target, intent));
          return new View(target, saved, Optional.empty());
        });
  }

  @Override
  public Operation setMode(Target target, long expectedGeneration, Mode mode, Policy policy) {
    return submit(
        token -> {
          Objects.requireNonNull(target);
          Objects.requireNonNull(mode);
          Objects.requireNonNull(policy);
          if (expectedGeneration < 0)
            throw new IllegalArgumentException("Expected generation required");
          token.throwIfCancellationRequested();
          // Repository replays the adapter-issued sealed inventory in its transaction. A Target
          // value
          // constructed by a caller is not proof or permission to create an arbitrary retention
          // scope.
          var accepted =
              repository.setMode(target, expectedGeneration, mode, policy, clock.instant(), token);
          validate(target, accepted);
          if (accepted.mode() != mode)
            throw new BuildRetentionException(CORRUPT_STATE, "Repository changed requested mode");
          // The durable commit wins late cancellation. Notify the exact new/prior generation
          // without
          // a cancelled cleanup token, and report activation failure separately from saved
          // preference.
          return activate(accepted, expectedGeneration);
        });
  }

  @Override
  public Operation resume(Target target) {
    return submit(
        token -> {
          Objects.requireNonNull(target);
          var saved = repository.readIntent(target, token);
          token.throwIfCancellationRequested();
          if (saved.isEmpty()) return new View(target, Optional.empty(), Optional.empty());
          var intent = saved.get();
          validate(target, intent);
          return intent.mode() == Mode.DOWNLOAD_ALL
              ? activate(intent, intent.generation())
              : new View(target, saved, Optional.empty());
        });
  }

  private View activate(Intent accepted, long priorGeneration) {
    try {
      if (accepted.mode() == Mode.DOWNLOAD_ALL) activation.resumeDownloadAll(accepted);
      else activation.withdraw(accepted.target(), priorGeneration);
      return new View(accepted.target(), Optional.of(accepted), Optional.empty());
    } catch (RuntimeException failed) {
      var code =
          failed instanceof BuildRetentionException typed ? typed.code() : STORAGE_UNAVAILABLE;
      return new View(
          accepted.target(),
          Optional.of(accepted),
          Optional.of(
              new ActivationFailure(
                  code, "Preference was saved, but background activation needs retry")));
    }
  }

  private static void validate(Target target, Intent intent) {
    if (intent == null || !intent.target().equals(target))
      throw new BuildRetentionException(
          CORRUPT_STATE, "Repository returned another retention target");
  }

  private Operation submit(Function<CancellationToken, View> action) {
    var job = new Job();
    synchronized (this) {
      if (closed)
        job.result.completeExceptionally(
            new IllegalStateException("Retention mode service is closed"));
      else if (active.size() >= 4)
        job.result.completeExceptionally(
            new BuildRetentionException(BUSY, "Four retention mode actions remain active"));
      else active.add(job);
    }
    if (!job.result.isDone()) {
      try {
        executor.execute(() -> run(job, action));
      } catch (RuntimeException | Error rejected) {
        synchronized (this) {
          if (!job.started) {
            active.remove(job);
            job.result.completeExceptionally(
                new BuildRetentionException(
                    BUSY, "Retention mode executor rejected admission", rejected));
          }
        }
      }
    }
    var readOnly = job.result.minimalCompletionStage();
    return new Operation() {
      public CompletionStage<View> completion() {
        return readOnly;
      }

      public void requestCancellation() {
        job.cancellation.set(true);
      }
    };
  }

  private void run(Job job, Function<CancellationToken, View> action) {
    synchronized (this) {
      job.started = true;
    }
    View value = null;
    Throwable failure = null;
    try {
      CancellationToken token = job.cancellation::get;
      token.throwIfCancellationRequested();
      value = action.apply(token);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      failure =
          new BuildRetentionException(
              INVALID_REQUEST, "Valid exact retention mode request required", invalid);
    } catch (Throwable failed) {
      failure = failed;
    } finally {
      synchronized (this) {
        active.remove(job);
      }
    }
    if (failure == null) job.result.complete(value);
    else job.result.completeExceptionally(failure);
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
