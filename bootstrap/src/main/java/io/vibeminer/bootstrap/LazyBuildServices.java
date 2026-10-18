package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.inventory.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Three typed build views over the same borrowed lazy content runtime and admission bound. */
final class LazyBuildServices
    implements BuildInventoryPreparationService, BuildOverviewService, BuildRetentionModeService {
  private final LazyBootstrapService owner;
  private final Set<Pending<?, ?>> active = new HashSet<>();
  private boolean closed;

  LazyBuildServices(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public BuildInventoryPreparationService.Operation prepare(BuildMetadataAdmission admission) {
    var pending =
        start(
            BuildInventoryPreparationProgress.initial(),
            runtime -> {
              var actual = runtime.inventoryPreparation().prepare(admission);
              return new Bound<>(
                  actual.completion(), actual::progress, actual::requestCancellation);
            },
            LazyBuildServices::preparationFailure);
    return new BuildInventoryPreparationService.Operation() {
      public CompletionStage<BuildInventoryPreparationResult> completion() {
        return pending.completion();
      }

      public BuildInventoryPreparationProgress progress() {
        return pending.progress();
      }

      public void requestCancellation() {
        pending.cancel();
      }
    };
  }

  @Override
  public BuildOverviewService.Operation read(BuildOverview.Request request) {
    var pending =
        start(
            BuildOverview.Progress.initial(),
            runtime -> {
              var actual = runtime.buildOverview().read(request);
              return new Bound<>(
                  actual.completion(), actual::progress, actual::requestCancellation);
            },
            LazyBuildServices::overviewFailure);
    return new BuildOverviewService.Operation() {
      public CompletionStage<BuildOverview.Snapshot> completion() {
        return pending.completion();
      }

      public BuildOverview.Progress progress() {
        return pending.progress();
      }

      public void requestCancellation() {
        pending.cancel();
      }
    };
  }

  @Override
  public BuildRetentionModeService.Operation read(BuildRetention.Target target) {
    return mode(runtime -> runtime.retentionMode().read(target));
  }

  @Override
  public BuildRetentionModeService.Operation setMode(
      BuildRetention.Target target,
      long generation,
      BuildRetention.Mode mode,
      BuildRetention.Policy policy) {
    return mode(runtime -> runtime.retentionMode().setMode(target, generation, mode, policy));
  }

  @Override
  public BuildRetentionModeService.Operation resume(BuildRetention.Target target) {
    return mode(runtime -> runtime.retentionMode().resume(target));
  }

  private BuildRetentionModeService.Operation mode(
      Function<LazyBootstrapService.Resources, BuildRetentionModeService.Operation> action) {
    Pending<BuildRetentionModeService.View, Void> pending =
        start(
            null,
            runtime -> {
              var actual = action.apply(runtime);
              return new Bound<>(actual.completion(), () -> null, actual::requestCancellation);
            },
            LazyBuildServices::modeFailure);
    return new BuildRetentionModeService.Operation() {
      public CompletionStage<BuildRetentionModeService.View> completion() {
        return pending.completion();
      }

      public void requestCancellation() {
        pending.cancel();
      }
    };
  }

  private record Bound<T, P>(CompletionStage<T> completion, Supplier<P> progress, Runnable cancel) {
    Bound {
      Objects.requireNonNull(completion);
      Objects.requireNonNull(progress);
      Objects.requireNonNull(cancel);
    }
  }

  private static final class Pending<T, P> {
    final CompletableFuture<T> result = new CompletableFuture<>();
    final CompletionStage<T> readOnly = result.minimalCompletionStage();
    final P initial;
    volatile Bound<T, P> bound;
    volatile boolean binding;
    volatile boolean admissionFailed;
    volatile boolean ownerRetired;
    volatile boolean actualDone;
    T value;
    Throwable failure;
    boolean cancelled;

    Pending(P initial) {
      this.initial = initial;
    }

    CompletionStage<T> completion() {
      return readOnly;
    }

    void publishActual() {
      if (actualDone && ownerRetired) {
        if (failure == null) result.complete(value);
        else result.completeExceptionally(failure);
      }
    }

    P progress() {
      var actual = bound;
      return actual == null ? initial : actual.progress().get();
    }

    void cancel() {
      final Bound<T, P> actual;
      synchronized (this) {
        cancelled = true;
        actual = bound;
      }
      if (actual != null) actual.cancel().run();
    }
  }

  private <T, P> Pending<T, P> start(
      P initial,
      Function<LazyBootstrapService.Resources, Bound<T, P>> action,
      Function<Throwable, Throwable> classify) {
    var pending = new Pending<T, P>(initial);
    synchronized (this) {
      if (closed) {
        pending.result.completeExceptionally(
            classify.apply(
                new BootstrapException(
                    BootstrapException.Reason.CLOSED, "Build view facade is closed")));
        return pending;
      }
      active.add(pending);
    }
    pending.result.whenComplete(
        (value, failure) -> {
          synchronized (this) {
            active.remove(pending);
          }
        });
    try {
      owner
          .dispatchResources(
              runtime -> {
                synchronized (pending) {
                  if (pending.cancelled)
                    return CompletableFuture.failedFuture(new CancellationException());
                  pending.binding = true;
                }
                final Bound<T, P> actual;
                try {
                  actual = Objects.requireNonNull(action.apply(runtime));
                } catch (Throwable failure) {
                  pending.admissionFailed = true;
                  if (pending.ownerRetired)
                    pending.result.completeExceptionally(classify.apply(failure));
                  throw failure;
                }
                final boolean cancel;
                synchronized (pending) {
                  pending.bound = actual;
                  cancel = pending.cancelled;
                }
                var bridge =
                    new CompletableFuture<T>() {
                      @Override
                      public boolean cancel(boolean interrupt) {
                        actual.cancel().run();
                        return false;
                      }
                    };
                actual
                    .completion()
                    .whenComplete(
                        (value, failure) -> {
                          pending.value = value;
                          pending.failure = failure == null ? null : unwrap(failure);
                          pending.actualDone = true;
                          // Retire the owner admission before any public callbacks. A source
                          // already
                          // complete during binding waits for dispatch's own terminal callback
                          // below.
                          if (failure == null) bridge.complete(value);
                          else bridge.completeExceptionally(failure);
                          pending.publishActual();
                        });
                // Cancellation may complete inline and re-enter from callbacks; never hold a
                // monitor.
                if (cancel) actual.cancel().run();
                return bridge;
              },
              classify)
          .whenComplete(
              (value, failure) -> {
                pending.ownerRetired = true;
                if (failure != null && (!pending.binding || pending.admissionFailed))
                  pending.result.completeExceptionally(classify.apply(failure));
                else pending.publishActual();
              });
    } catch (Throwable failure) {
      // Covers admission/initialization failures, including fatal rejection, before any actual
      // operation has authority. A bound operation retains its own terminal/commit outcome.
      if (!pending.binding || pending.admissionFailed)
        pending.result.completeExceptionally(classify.apply(failure));
    }
    return pending;
  }

  @Override
  public void close() {
    List<Pending<?, ?>> jobs;
    synchronized (this) {
      if (closed) return;
      closed = true;
      jobs = List.copyOf(active);
    }
    Throwable failure = null;
    for (var job : jobs)
      try {
        job.cancel();
      } catch (Throwable rejected) {
        if (failure == null) failure = rejected;
        else failure.addSuppressed(rejected);
      }
    if (failure instanceof Error fatal) throw fatal;
    if (failure instanceof RuntimeException runtime) throw runtime;
  }

  private static Throwable unwrap(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    return failure;
  }

  private static Throwable preparationFailure(Throwable failure) {
    failure = unwrap(failure);
    if (failure instanceof BootstrapException b)
      return new BuildInventoryPreparationException(
          b.reason() == BootstrapException.Reason.CLOSED
              ? BuildInventoryPreparationException.Code.CLOSED
              : b.reason() == BootstrapException.Reason.CAPACITY
                  ? BuildInventoryPreparationException.Code.BUSY
                  : BuildInventoryPreparationException.Code.STORAGE_UNAVAILABLE,
          b.getMessage(),
          b);
    return failure;
  }

  private static Throwable overviewFailure(Throwable failure) {
    failure = unwrap(failure);
    if (failure instanceof BootstrapException b)
      return new BuildOverviewException(
          b.reason() == BootstrapException.Reason.CLOSED
              ? BuildOverviewException.Code.CLOSED
              : b.reason() == BootstrapException.Reason.CAPACITY
                  ? BuildOverviewException.Code.BUSY
                  : BuildOverviewException.Code.STORAGE,
          b.getMessage(),
          b);
    return failure;
  }

  private static Throwable modeFailure(Throwable failure) {
    failure = unwrap(failure);
    if (failure instanceof BootstrapException b)
      return new BuildRetentionException(
          b.reason() == BootstrapException.Reason.CAPACITY
              ? BuildRetentionException.Code.BUSY
              : b.reason() == BootstrapException.Reason.CLOSED
                  ? BuildRetentionException.Code.NOT_READY
                  : BuildRetentionException.Code.STORAGE_UNAVAILABLE,
          b.getMessage(),
          b);
    return failure;
  }
}
