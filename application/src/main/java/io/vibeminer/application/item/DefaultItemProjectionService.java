package io.vibeminer.application.item;

import static io.vibeminer.application.item.ItemProjectionException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/**
 * Bounded orchestration; remembered handles authorize queries but never replace backend proof
 * replay.
 */
public final class DefaultItemProjectionService implements ItemProjectionService {
  private final ItemProjectionBackend backend;
  private final Executor executor;
  private final Semaphore capacity = new Semaphore(4);
  private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Map<String, PreparedItemProjection> ready = new LinkedHashMap<>();

  public DefaultItemProjectionService(ItemProjectionBackend backend, Executor executor) {
    this.backend = Objects.requireNonNull(backend);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public CompletionStage<PreparedItemProjection> prepare(ItemProjectionRequest request) {
    return submit(
        token -> {
          if (request == null) throw error(INVALID_REQUEST, "Preparation request required");
          try {
            var result = backend.prepare(request, token);
            token.throwIfCancellationRequested();
            if (result == null
                || !result.snapshotId().equals(request.source().association().build().snapshotId())
                || !result.sourceRole().equals(request.source().role())
                || !result.locale().equals(request.locale()))
              throw error(CORRUPT, "Preparation changed exact source context");
            var limits = request.limits();
            if (result.evidence().localVerification().object().length() > limits.maxEncodedBytes()
                || result.definition().interpretation().sectionDecodedBytes()
                    > limits.maxSectionBytes()
                || result.coverage().physicalRows() > limits.maxPhysicalRows()
                || result.coverage().unindexedCopyDeclarations() > limits.maxCopyDeclarations())
              throw error(LIMIT_EXCEEDED, "Preparation exceeded explicit local bounds");
            synchronized (ready) {
              token.throwIfCancellationRequested();
              ready.put(key(result), result);
              while (ready.size() > 16) ready.remove(ready.keySet().iterator().next());
            }
            return result;
          } catch (ItemProjectionException failure) {
            if (badEvidence(failure))
              synchronized (ready) {
                ready
                    .values()
                    .removeIf(
                        h ->
                            h.snapshotId()
                                    .equals(request.source().association().build().snapshotId())
                                && h.sourceRole().equals(request.source().role())
                                && h.locale().equals(request.locale()));
              }
            throw failure;
          }
        });
  }

  @Override
  public CompletionStage<ItemPage> page(
      PreparedItemProjection handle, Optional<ItemPageCursor> cursor, int size) {
    return submit(
        token -> {
          if (handle == null || cursor == null || size < 1 || size > 500)
            throw error(INVALID_REQUEST, "Invalid bounded page request");
          if (cursor.isPresent())
            try {
              cursor.get().requireContext(handle, size);
            } catch (IllegalArgumentException stale) {
              throw error(STALE_CURSOR, "Cursor context differs");
            }
          requireReady(handle);
          try {
            var result = backend.page(handle, cursor, size, token);
            token.throwIfCancellationRequested();
            requireReady(handle);
            if (result == null
                || !result.handle().equals(handle)
                || result.rows().size() > size
                || result.rows().size() > handle.coverage().physicalRows())
              throw error(CORRUPT, "Page changed context or bounds");
            long previous = cursor.map(ItemPageCursor::afterRecordId).orElse(0L);
            var ordinals = new HashSet<Integer>();
            for (var row : result.rows()) {
              validateRow(handle, row);
              if (row.recordId() <= previous || !ordinals.add(row.physicalOrdinal()))
                throw error(CORRUPT, "Page order or physical identity invalid");
              previous = row.recordId();
            }
            if (result.nextCursor().isPresent()) {
              var next = result.nextCursor().get();
              try {
                next.requireContext(handle, size);
              } catch (IllegalArgumentException stale) {
                throw error(CORRUPT, "Backend cursor context differs");
              }
              if (result.rows().size() != size || next.afterRecordId() != previous)
                throw error(CORRUPT, "Backend cursor does not follow bounded rows");
            }
            return result;
          } catch (ItemProjectionException failure) {
            revoke(handle, failure);
            throw failure;
          }
        });
  }

  @Override
  public CompletionStage<ItemLookupResult> lookup(PreparedItemProjection handle, long id) {
    return submit(
        token -> {
          if (handle == null || id < 1 || id > 0xffffffffL)
            throw error(INVALID_REQUEST, "Unsigned Item record ID required");
          requireReady(handle);
          try {
            var result = backend.lookup(handle, id, token);
            token.throwIfCancellationRequested();
            requireReady(handle);
            if (result == null
                || result.requestedId() != id
                || !result.coverage().equals(handle.coverage()))
              throw error(CORRUPT, "Lookup changed exact scope");
            if (result.row().isPresent()) validateRow(handle, result.row().get());
            if (result.outcome() == ItemLookupResult.Outcome.COPY_UNINDEXED
                && handle.coverage().unindexedCopyDeclarations() == 0)
              throw error(CORRUPT, "Copy result lacks admitted declarations");
            return result;
          } catch (ItemProjectionException failure) {
            revoke(handle, failure);
            throw failure;
          }
        });
  }

  private static void validateRow(PreparedItemProjection h, ItemPhysicalRow r) {
    if (r.sectionOrdinal() != h.coverage().sectionOrdinal()
        || r.physicalOrdinal() >= h.coverage().physicalRows())
      throw error(CORRUPT, "Row outside admitted physical scope");
    var bindings = h.definition().interpretation().schema().fields();
    for (int i = 0; i < 4; i++) {
      var v = r.values().get(i);
      var b = bindings.get(i);
      if (v.field() != b.field()
          || v.candidateBits() != b.candidateBits()
          || v.candidateSign() != b.sign()) throw error(CORRUPT, "Row candidate binding differs");
    }
  }

  private static String key(PreparedItemProjection h) {
    return h.artifactId() + ":" + h.snapshotId() + ":" + h.sourceRole() + ":" + h.locale();
  }

  private void requireReady(PreparedItemProjection h) {
    synchronized (ready) {
      if (!h.equals(ready.get(key(h))))
        throw error(NOT_READY, "Explicit successful preparation required for this exact handle");
    }
  }

  private void revoke(PreparedItemProjection h, ItemProjectionException failure) {
    if (badEvidence(failure))
      synchronized (ready) {
        ready.remove(key(h), h);
      }
  }

  private static boolean badEvidence(ItemProjectionException failure) {
    return failure.code() == MISSING_LOCAL
        || failure.code() == CORRUPT
        || failure.code() == INCOMPATIBLE
        || failure.code() == UNSUPPORTED;
  }

  private <T> CompletionStage<T> submit(Function<CancellationToken, T> action) {
    if (closed.get()) return CompletableFuture.failedFuture(error(CLOSED, "Item service closed"));
    if (!capacity.tryAcquire())
      return CompletableFuture.failedFuture(error(BUSY, "Four Item workers already admitted"));
    var result = new CompletableFuture<T>();
    var worker = new AtomicReference<Thread>();
    active.add(result);
    result.whenComplete(
        (v, f) -> {
          if (result.isCancelled()) {
            var t = worker.get();
            if (t != null) t.interrupt();
          }
        });
    try {
      executor.execute(
          () -> {
            worker.set(Thread.currentThread());
            CancellationToken token =
                () ->
                    result.isCancelled() || closed.get() || Thread.currentThread().isInterrupted();
            try {
              token.throwIfCancellationRequested();
              var value = action.apply(token);
              token.throwIfCancellationRequested();
              result.complete(value);
            } catch (Throwable failure) {
              result.completeExceptionally(
                  failure instanceof ItemProjectionException
                          || failure instanceof CancellationException
                      ? failure
                      : new ItemProjectionException(
                          STORAGE_UNAVAILABLE, "Local Item operation failed", failure));
              if (failure instanceof Error fatal) throw fatal;
            } finally {
              worker.set(null);
              active.remove(result);
              capacity.release();
            }
          });
    } catch (RuntimeException rejected) {
      active.remove(result);
      capacity.release();
      result.completeExceptionally(
          new ItemProjectionException(BUSY, "Item executor rejected work", rejected));
    }
    if (closed.get()) result.cancel(true);
    return result;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    active.forEach(f -> f.cancel(true));
    synchronized (ready) {
      ready.clear();
    }
  }

  private static ItemProjectionException error(ItemProjectionException.Code code, String message) {
    return new ItemProjectionException(code, message);
  }
}
