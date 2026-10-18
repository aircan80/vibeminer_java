package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildopening.*;
import java.util.*;
import java.util.concurrent.*;

/** Build-opening facade borrowing the existing lazy initializer and content resources. */
final class LazyBuildOpeningService implements BuildOpeningService {
  private final LazyBootstrapService owner;
  private final Set<Job> active = new HashSet<>();
  private boolean closed;

  private static final class Job {
    final CompletableFuture<BuildMetadataAdmission> result = new CompletableFuture<>();
    volatile OpenOperation delegate;
    boolean cancellation;
    volatile boolean binding;
  }

  LazyBuildOpeningService(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public OpenOperation open(BuildLibraryReference reference, String locale) {
    var job = new Job();
    synchronized (this) {
      if (closed) {
        job.result.completeExceptionally(
            new BuildOpeningException(
                BuildOpeningException.Code.CLOSED, "Build-opening facade is closed"));
        return operation(job);
      }
      active.add(job);
    }
    job.result.whenComplete(
        (value, failure) -> {
          synchronized (this) {
            active.remove(job);
          }
        });
    owner
        .dispatchResources(
            runtime -> {
              synchronized (job) {
                if (job.cancellation)
                  return CompletableFuture.failedFuture(new CancellationException());
                job.binding = true;
              }
              final OpenOperation actual;
              try {
                actual = Objects.requireNonNull(runtime.buildOpening().open(reference, locale));
              } catch (Throwable failure) {
                job.result.completeExceptionally(classify(failure));
                throw failure;
              }
              final boolean cancellationRequested;
              synchronized (job) {
                job.delegate = actual;
                cancellationRequested = job.cancellation;
              }
              // Delegate cancellation can synchronously complete and invoke arbitrary callbacks.
              // Publish the binding under the monitor, then call foreign code without the lock.
              if (cancellationRequested) actual.requestCancellation();
              actual
                  .completion()
                  .whenComplete(
                      (value, failure) -> {
                        if (failure == null) job.result.complete(value);
                        else job.result.completeExceptionally(unwrap(failure));
                      });
              // This bridge is owned by dispatch, not the actual worker's completion. A
              // global-owner
              // cancellation requests cooperation, never fabricates retirement of the underlying
              // worker.
              var bridge =
                  new CompletableFuture<BuildMetadataAdmission>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                      actual.requestCancellation();
                      return false;
                    }
                  };
              actual
                  .completion()
                  .whenComplete(
                      (value, failure) -> {
                        if (failure == null) bridge.complete(value);
                        else bridge.completeExceptionally(failure);
                      });
              return bridge;
            },
            LazyBuildOpeningService::classify)
        .whenComplete(
            (value, failure) -> {
              // Once bound, only the actual operation determines terminal completion/commit
              // outcome.
              if (!job.binding && failure != null)
                job.result.completeExceptionally(classify(failure));
            });
    return operation(job);
  }

  private OpenOperation operation(Job job) {
    var readOnly = job.result.minimalCompletionStage();
    return new OpenOperation() {
      @Override
      public CompletionStage<BuildMetadataAdmission> completion() {
        return readOnly;
      }

      @Override
      public BuildOpenProgress progress() {
        var actual = job.delegate;
        return actual == null ? BuildOpenProgress.initial() : actual.progress();
      }

      @Override
      public void requestCancellation() {
        cancel(job);
      }
    };
  }

  private static void cancel(Job job) {
    final OpenOperation actual;
    synchronized (job) {
      job.cancellation = true;
      actual = job.delegate;
    }
    if (actual != null) actual.requestCancellation();
  }

  @Override
  public void close() {
    List<Job> pending;
    synchronized (this) {
      if (closed) return;
      closed = true;
      pending = List.copyOf(active);
    }
    pending.forEach(LazyBuildOpeningService::cancel);
  }

  private static Throwable unwrap(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    return failure;
  }

  private static Throwable classify(Throwable failure) {
    failure = unwrap(failure);
    if (failure instanceof BootstrapException bootstrap) {
      var code =
          switch (bootstrap.reason()) {
            case CLOSED -> BuildOpeningException.Code.CLOSED;
            case CAPACITY -> BuildOpeningException.Code.BUSY;
            default -> BuildOpeningException.Code.STORAGE_UNAVAILABLE;
          };
      return new BuildOpeningException(code, bootstrap.getMessage(), bootstrap);
    }
    return failure;
  }
}
