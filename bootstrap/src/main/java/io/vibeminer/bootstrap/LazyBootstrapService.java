package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.*;
import io.vibeminer.domain.BuildSnapshot;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Composition lifecycle: metadata launch never opens/locks content storage; initialization runs off
 * the UI thread.
 */
final class LazyBootstrapService implements BootstrapService {
  interface Resources extends AutoCloseable {
    BootstrapService service();

    default io.vibeminer.application.lookup.FileLookupService lookup() {
      throw new io.vibeminer.application.lookup.FileLookupException(
          io.vibeminer.application.lookup.FileLookupException.Code.UNSUPPORTED,
          "This content runtime does not provide root lookup");
    }

    @Override
    void close();
  }

  private final Object monitor = new Object();
  private final Supplier<? extends Resources> factory;
  private final ExecutorService initializer =
      Executors.newSingleThreadExecutor(
          task -> {
            var thread = new Thread(task, "vibeminer-content-initializer");
            thread.setDaemon(true);
            return thread;
          });
  private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
  private final Semaphore capacity = new Semaphore(64);
  private CompletableFuture<Resources> initialization;
  private Resources resources;
  private boolean closed;

  LazyBootstrapService(Supplier<? extends Resources> factory) {
    this.factory = Objects.requireNonNull(factory);
  }

  @Override
  public CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot build) {
    return dispatch(service -> service.inspectConfiguration(build));
  }

  @Override
  public CompletionStage<InstallInspection> download(InstallPlan plan) {
    return dispatch(service -> service.download(plan));
  }

  @Override
  public CompletionStage<InstallInspection> reopen(BuildSnapshot build) {
    return dispatch(service -> service.reopen(build));
  }

  private <T> CompletionStage<T> dispatch(Function<BootstrapService, CompletionStage<T>> action) {
    return dispatchResources(runtime -> action.apply(runtime.service()), Function.identity());
  }

  <T> CompletionStage<T> dispatchResources(
      Function<Resources, CompletionStage<T>> action, Function<Throwable, Throwable> classify) {
    if (!capacity.tryAcquire())
      return CompletableFuture.failedFuture(
          classify.apply(
              new BootstrapException(
                  BootstrapException.Reason.CAPACITY, "Too many pending content actions")));
    var result = new CompletableFuture<T>();
    var operation = new AtomicReference<CompletableFuture<T>>();
    requests.add(result);
    result.whenComplete(
        (value, failure) -> {
          requests.remove(result);
          capacity.release();
          if (result.isCancelled()) {
            var running = operation.get();
            if (running != null) running.cancel(true);
          }
        });
    initialize()
        .whenComplete(
            (runtime, failure) -> {
              if (result.isDone()) return;
              if (failure != null) {
                result.completeExceptionally(classify.apply(failure));
                return;
              }
              try {
                synchronized (monitor) {
                  if (closed) {
                    result.cancel(true);
                    return;
                  }
                }
                var running = Objects.requireNonNull(action.apply(runtime)).toCompletableFuture();
                operation.set(running);
                if (result.isCancelled()) running.cancel(true);
                running.whenComplete(
                    (value, error) -> {
                      if (error != null) result.completeExceptionally(error);
                      else result.complete(value);
                    });
              } catch (Throwable error) {
                result.completeExceptionally(classify.apply(error));
                if (error instanceof Error fatal) throw fatal;
              }
            });
    return result;
  }

  private CompletableFuture<Resources> initialize() {
    synchronized (monitor) {
      if (closed) return CompletableFuture.failedFuture(closedFailure());
      if (initialization != null) return initialization;
      var future = new CompletableFuture<Resources>();
      initialization = future;
      try {
        initializer.execute(
            () -> {
              Resources created = null;
              try {
                synchronized (monitor) {
                  if (closed) throw closedFailure();
                }
                created = Objects.requireNonNull(factory.get());
                synchronized (monitor) {
                  if (closed) throw closedFailure();
                  resources = created;
                }
                future.complete(created);
              } catch (Throwable failure) {
                if (created != null)
                  try {
                    created.close();
                  } catch (RuntimeException suppressed) {
                    failure.addSuppressed(suppressed);
                  }
                synchronized (monitor) {
                  if (initialization == future) initialization = null;
                }
                future.completeExceptionally(
                    failure instanceof Error || failure instanceof BootstrapException
                        ? failure
                        : new BootstrapException(
                            BootstrapException.Reason.STORAGE,
                            "Content storage could not be opened. Metadata workspaces remain"
                                + " available.",
                            failure));
                if (failure instanceof Error fatal) throw fatal;
              }
            });
      } catch (RuntimeException rejected) {
        initialization = null;
        future.completeExceptionally(rejected);
      }
      return future;
    }
  }

  @Override
  public void close() {
    Resources opened;
    CompletableFuture<Resources> pending;
    synchronized (monitor) {
      if (closed) return;
      closed = true;
      opened = resources;
      resources = null;
      pending = initialization;
    }
    requests.forEach(request -> request.cancel(true));
    if (pending != null) pending.completeExceptionally(closedFailure());
    initializer.shutdownNow();
    boolean interrupted = false;
    try {
      if (!initializer.awaitTermination(10, TimeUnit.SECONDS))
        throw new IllegalStateException(
            "Content initialization did not stop within shutdown budget");
    } catch (InterruptedException failure) {
      interrupted = true;
    } finally {
      if (opened != null) opened.close();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static BootstrapException closedFailure() {
    return new BootstrapException(BootstrapException.Reason.CLOSED, "Content workspace is closed");
  }
}
