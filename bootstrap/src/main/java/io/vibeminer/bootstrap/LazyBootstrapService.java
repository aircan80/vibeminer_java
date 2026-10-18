package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.*;
import io.vibeminer.domain.BuildSnapshot;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    default io.vibeminer.application.inventory.BuildInventoryPreparationService
        inventoryPreparation() {
      throw new io.vibeminer.application.inventory.BuildInventoryPreparationException(
          io.vibeminer.application.inventory.BuildInventoryPreparationException.Code.UNSUPPORTED,
          "This content runtime does not provide inventory preparation");
    }

    default io.vibeminer.application.buildoverview.BuildOverviewService buildOverview() {
      throw new UnsupportedOperationException(
          "This content runtime does not provide build overview");
    }

    default io.vibeminer.application.buildretention.BuildRetentionModeService retentionMode() {
      throw new UnsupportedOperationException(
          "This content runtime does not provide durable build modes");
    }

    default io.vibeminer.application.buildopening.BuildOpeningService buildOpening() {
      throw new io.vibeminer.application.buildopening.BuildOpeningException(
          io.vibeminer.application.buildopening.BuildOpeningException.Code.UNSUPPORTED_METADATA,
          "This content runtime does not provide build metadata admission");
    }

    default io.vibeminer.application.itemexport.ItemEvidenceService itemEvidence() {
      throw new io.vibeminer.application.itemexport.ItemEvidenceException(
          io.vibeminer.application.itemexport.ItemEvidenceException.Code.UNSUPPORTED,
          "This content runtime does not provide Item evidence export");
    }

    default io.vibeminer.application.itemnames.ItemNamesService itemNames() {
      throw new io.vibeminer.application.itemnames.ItemNamesException(
          io.vibeminer.application.itemnames.ItemNamesException.Code.UNSUPPORTED,
          "This content runtime does not provide physical Item names");
    }

    default io.vibeminer.application.content.MetadataRepairService metadataRepair() {
      throw new io.vibeminer.application.content.ContentClaimException(
          io.vibeminer.application.content.ContentClaimException.Code.UNSUPPORTED_REUSE,
          "This content runtime does not provide metadata repair");
    }

    default io.vibeminer.application.content.MetadataReuseService metadataReuse() {
      throw new io.vibeminer.application.content.ContentClaimException(
          io.vibeminer.application.content.ContentClaimException.Code.UNSUPPORTED_REUSE,
          "This content runtime does not provide metadata reuse");
    }

    default io.vibeminer.application.item.ItemProjectionService itemProjection() {
      throw new io.vibeminer.application.item.ItemProjectionException(
          io.vibeminer.application.item.ItemProjectionException.Code.UNSUPPORTED,
          "This content runtime does not provide Item projection");
    }

    default io.vibeminer.application.selectedfile.SelectedFileService selectedFiles() {
      throw new io.vibeminer.application.selectedfile.SelectedFileException(
          io.vibeminer.application.selectedfile.SelectedFileException.Code.UNSUPPORTED,
          "This content runtime does not provide selected-file acquisition");
    }

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
    var retired = new AtomicBoolean();
    var result =
        new CompletableFuture<T>() {
          private void retire() {
            if (retired.compareAndSet(false, true)) {
              requests.remove(this);
              capacity.release();
            }
          }

          @Override
          public boolean complete(T value) {
            retire();
            return super.complete(value);
          }

          @Override
          public boolean completeExceptionally(Throwable failure) {
            Objects.requireNonNull(failure);
            retire();
            return super.completeExceptionally(failure);
          }

          @Override
          public boolean cancel(boolean interrupt) {
            retire();
            return super.cancel(interrupt);
          }
        };
    var operation = new AtomicReference<CompletableFuture<T>>();
    requests.add(result);
    result.whenComplete(
        (value, failure) -> {
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
