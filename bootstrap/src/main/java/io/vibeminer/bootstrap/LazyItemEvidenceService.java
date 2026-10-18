package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.itemexport.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Borrowed lazy export facade. Save cancellation requests never settle its actual commit outcome.
 */
final class LazyItemEvidenceService implements ItemEvidenceService {
  private final LazyBootstrapService owner;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<CompletableFuture<?>> captures = ConcurrentHashMap.newKeySet();
  private final Set<PendingSave> saves = ConcurrentHashMap.newKeySet();

  LazyItemEvidenceService(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public CompletionStage<ItemEvidenceSnapshot> capture(ItemEvidenceSelection selection) {
    if (closed.get()) return CompletableFuture.failedFuture(closedFailure());
    var request =
        owner
            .dispatchResources(
                runtime -> {
                  if (closed.get()) throw closedFailure();
                  return runtime.itemEvidence().capture(selection);
                },
                LazyItemEvidenceService::classify)
            .toCompletableFuture();
    captures.add(request);
    request.whenComplete((value, failure) -> captures.remove(request));
    if (closed.get()) request.cancel(true);
    return request;
  }

  @Override
  public SaveOperation save(ItemEvidenceSnapshot snapshot, String authorizedReplacementPath) {
    var pending = new PendingSave();
    if (closed.get()) {
      pending.failBeforeBinding(closedFailure());
      return pending;
    }
    saves.add(pending);
    pending.terminal.whenComplete((value, failure) -> saves.remove(pending));
    if (closed.get()) pending.requestCancellation();
    owner
        .dispatchResources(
            runtime -> {
              pending.beginAction();
              try {
                var actual =
                    Objects.requireNonNull(
                        runtime.itemEvidence().save(snapshot, authorizedReplacementPath));
                pending.bind(actual);
                return CompletableFuture.completedFuture(null);
              } catch (Throwable failure) {
                pending.actionFailed(failure);
                throw failure;
              }
            },
            LazyItemEvidenceService::classify)
        .whenComplete(
            (value, failure) -> {
              if (failure != null) pending.failBeforeBinding(classify(failure));
            });
    return pending;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      captures.forEach(request -> request.cancel(true));
      saves.forEach(PendingSave::requestCancellation);
    }
  }

  private final class PendingSave implements SaveOperation {
    private final CompletableFuture<ItemEvidenceReceipt> terminal = new CompletableFuture<>();
    private SaveOperation actual;
    private boolean cancelRequested, actionStarted, settledBeforeBinding;

    synchronized void beginAction() {
      if (closed.get()) throw closedFailure();
      if (cancelRequested || settledBeforeBinding)
        throw new CancellationException("Export cancellation requested before save");
      actionStarted = true;
    }

    void bind(SaveOperation operation) {
      boolean cancel;
      synchronized (this) {
        actual = operation;
        cancel = cancelRequested;
      }
      operation
          .completion()
          .whenComplete(
              (receipt, failure) -> {
                if (failure != null) terminal.completeExceptionally(classify(failure));
                else terminal.complete(receipt);
              });
      if (cancel) operation.requestCancellation();
    }

    void actionFailed(Throwable failure) {
      boolean settle;
      synchronized (this) {
        settle = actual == null && !settledBeforeBinding;
        if (settle) settledBeforeBinding = true;
      }
      if (settle) terminal.completeExceptionally(classify(failure));
    }

    void failBeforeBinding(Throwable failure) {
      boolean cancel;
      boolean settle;
      synchronized (this) {
        // Once creation starts, its actual handle remains the terminal authority even if owner
        // dispatch is canceled. Request cancellation, then wait for binding and actual retirement.
        cancel = actionStarted;
        settle = !cancel && !settledBeforeBinding;
        if (settle) settledBeforeBinding = true;
      }
      if (settle) terminal.completeExceptionally(classify(failure));
      if (cancel) requestCancellation();
    }

    @Override
    public CompletionStage<ItemEvidenceReceipt> completion() {
      return terminal.minimalCompletionStage();
    }

    @Override
    public void requestCancellation() {
      SaveOperation operation;
      synchronized (this) {
        if (cancelRequested) return;
        cancelRequested = true;
        operation = actual;
      }
      if (operation != null) operation.requestCancellation();
    }
  }

  private static ItemEvidenceException closedFailure() {
    return new ItemEvidenceException(
        ItemEvidenceException.Code.CLOSED, "Evidence facade is closed");
  }

  private static Throwable classify(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof BootstrapException bootstrap) {
      var code =
          switch (bootstrap.reason()) {
            case CLOSED -> ItemEvidenceException.Code.CLOSED;
            case CAPACITY -> ItemEvidenceException.Code.BUSY;
            default -> ItemEvidenceException.Code.STORAGE_UNAVAILABLE;
          };
      return new ItemEvidenceException(code, bootstrap.getMessage(), bootstrap);
    }
    return failure;
  }
}
