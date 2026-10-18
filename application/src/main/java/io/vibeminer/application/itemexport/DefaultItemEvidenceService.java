package io.vibeminer.application.itemexport;

import static io.vibeminer.application.itemexport.ItemEvidenceException.Code.*;

import io.vibeminer.application.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One flat actual job; borrowed adapters/executor and commit-aware save cancellation. */
public final class DefaultItemEvidenceService implements ItemEvidenceService {
  private final ItemEvidenceSnapshotBackend backend;
  private final ItemEvidenceWriter writer;
  private final Executor executor;
  private ItemEvidenceSnapshot issued;
  private Job<?> active;
  private volatile boolean closed;

  private static final class Job<T> {
    final CompletableFuture<T> result = new CompletableFuture<>();
    final AtomicBoolean cancellation = new AtomicBoolean();
    final boolean capture;
    boolean accepted;
    ItemEvidenceSnapshot captured;

    Job(boolean capture) {
      this.capture = capture;
    }
  }

  public DefaultItemEvidenceService(
      ItemEvidenceSnapshotBackend backend, ItemEvidenceWriter writer, Executor executor) {
    this.backend = Objects.requireNonNull(backend);
    this.writer = Objects.requireNonNull(writer);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public CompletionStage<ItemEvidenceSnapshot> capture(ItemEvidenceSelection selection) {
    if (selection == null) return failed(INVALID_REQUEST, "Pinned selection required");
    var job = new Job<ItemEvidenceSnapshot>(true);
    synchronized (this) {
      if (closed) return failed(CLOSED, "Export service is closed");
      if (active != null) return failed(BUSY, "Actual export job has not retired");
      active = job;
    }
    job.result.whenComplete(
        (value, failure) -> {
          if (job.result.isCancelled()) {
            job.cancellation.set(true);
            synchronized (this) {
              if (job.captured != null && issued == job.captured) issued = null;
            }
          }
        });
    dispatch(
        job,
        token -> {
          var snapshot = backend.capture(selection, token);
          token.throwIfCancellationRequested();
          if (snapshot == null || !snapshot.selection().equals(selection))
            throw error(CORRUPT, "Snapshot backend changed pinned selection");
          // Reconstruct to run every physical/name/coverage invariant at the trust boundary.
          try {
            snapshot =
                new ItemEvidenceSnapshot(
                    snapshot.selection(),
                    snapshot.row(),
                    snapshot.name(),
                    snapshot.verificationStartedAt(),
                    snapshot.verificationCompletedAt(),
                    snapshot.generationFingerprint());
          } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new ItemEvidenceException(CORRUPT, "Invalid backend snapshot", malformed);
          }
          synchronized (this) {
            token.throwIfCancellationRequested();
            job.captured = snapshot;
            issued = snapshot;
          }
          return snapshot;
        });
    return job.result;
  }

  @Override
  public SaveOperation save(ItemEvidenceSnapshot snapshot, String authorizedReplacementPath) {
    if (snapshot == null || !validPathText(authorizedReplacementPath))
      return failedSave(INVALID_REQUEST, "Issued snapshot and exact authorized pathname required");
    try {
      writer.validateDestination(authorizedReplacementPath);
    } catch (ItemEvidenceException invalid) {
      return failedSave(invalid);
    }
    var job = new Job<ItemEvidenceReceipt>(false);
    synchronized (this) {
      if (closed) return failedSave(CLOSED, "Export service is closed");
      if (active != null) return failedSave(BUSY, "Actual export job has not retired");
      if (issued == null || !issued.equals(snapshot))
        return failedSave(STALE_SELECTION, "Snapshot is not the current issued export observation");
      active = job;
    }
    var operation = operation(job);
    dispatch(
        job,
        token -> {
          var receipt = writer.publish(snapshot, authorizedReplacementPath, token);
          // Never check cancellation after publication: the writer owns the commit outcome.
          if (receipt == null)
            throw error(COMMIT_UNCERTAIN, "Writer omitted its publication outcome");
          return receipt;
        });
    return operation;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(CancellationToken token);
  }

  private <T> void dispatch(Job<T> job, Work<T> work) {
    try {
      executor.execute(
          () -> {
            accept(job);
            CancellationToken token =
                () -> closed || job.cancellation.get() || job.result.isCancelled();
            try {
              token.throwIfCancellationRequested();
              job.result.complete(work.run(token));
            } catch (Throwable failure) {
              job.result.completeExceptionally(failure);
            } finally {
              synchronized (this) {
                if (active == job) active = null;
              }
            }
          });
      accept(job);
    } catch (RuntimeException rejected) {
      boolean unstarted;
      synchronized (this) {
        unstarted = !job.accepted;
        if (unstarted && active == job) active = null;
      }
      if (unstarted) {
        job.cancellation.set(true);
        job.result.completeExceptionally(
            new ItemEvidenceException(BUSY, "Export executor rejected admission", rejected));
      }
    }
  }

  private synchronized void accept(Job<?> job) {
    if (!job.accepted && active == job) {
      job.accepted = true;
      if (job.capture) issued = null;
    }
  }

  private static SaveOperation operation(Job<ItemEvidenceReceipt> job) {
    var readOnly = job.result.minimalCompletionStage();
    return new SaveOperation() {
      public CompletionStage<ItemEvidenceReceipt> completion() {
        return readOnly;
      }

      public void requestCancellation() {
        job.cancellation.set(true);
      }
    };
  }

  private static SaveOperation failedSave(ItemEvidenceException failure) {
    var job = new Job<ItemEvidenceReceipt>(false);
    job.result.completeExceptionally(failure);
    return operation(job);
  }

  private static SaveOperation failedSave(ItemEvidenceException.Code code, String message) {
    var job = new Job<ItemEvidenceReceipt>(false);
    job.result.completeExceptionally(error(code, message));
    return operation(job);
  }

  private static boolean validPathText(String path) {
    if (path == null
        || path.isBlank()
        || path.length() > 32768
        || path.indexOf('\0') >= 0
        || !StandardCharsets.UTF_8.newEncoder().canEncode(path)) return false;
    return true;
  }

  private static <T> CompletionStage<T> failed(ItemEvidenceException.Code code, String message) {
    return CompletableFuture.failedFuture(error(code, message));
  }

  private static ItemEvidenceException error(ItemEvidenceException.Code code, String message) {
    return new ItemEvidenceException(code, message);
  }

  @Override
  public void close() {
    Job<?> job;
    synchronized (this) {
      if (closed) return;
      closed = true;
      issued = null;
      job = active;
    }
    if (job != null) {
      job.cancellation.set(true);
      if (job.capture) job.result.cancel(false);
    }
  }
}
