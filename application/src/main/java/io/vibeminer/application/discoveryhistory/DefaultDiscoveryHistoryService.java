package io.vibeminer.application.discoveryhistory;

import static io.vibeminer.application.discoveryhistory.DiscoveryHistoryException.Code.*;

import io.vibeminer.application.CancellationToken;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Four flat actual jobs; borrowed resources and commit-aware explicit observation refresh.
 * OBSERVATION_NOT_SAVED preserves the exact repository exception as its cause, including a typed
 * quota, corruption or storage code, so presentation can explain both retrieval and persistence.
 */
public final class DefaultDiscoveryHistoryService implements DiscoveryHistoryService {
  private final DiscoveryObservationGateway gateway;
  private final DiscoveryHistoryRepository repository;
  private final DiscoveryHistoryLimits limits;
  private final Executor executor;
  private final Set<Job<?>> active = new HashSet<>();
  private volatile boolean closed;

  private static final class Job<T> {
    final CompletableFuture<T> result = new CompletableFuture<>();
    final AtomicBoolean cancellation = new AtomicBoolean();
    final boolean refresh;
    boolean started;

    Job(boolean refresh) {
      this.refresh = refresh;
    }
  }

  public DefaultDiscoveryHistoryService(
      DiscoveryObservationGateway gateway,
      DiscoveryHistoryRepository repository,
      Executor executor) {
    this(gateway, repository, DiscoveryHistoryLimits.defaults(), executor);
  }

  public DefaultDiscoveryHistoryService(
      DiscoveryObservationGateway gateway,
      DiscoveryHistoryRepository repository,
      DiscoveryHistoryLimits limits,
      Executor executor) {
    this.gateway = Objects.requireNonNull(gateway);
    this.repository = Objects.requireNonNull(repository);
    this.limits = Objects.requireNonNull(limits);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public RefreshOperation refreshObservation(String productId, String region) {
    try {
      HistoryValues.product(productId);
      HistoryValues.region(region);
    } catch (IllegalArgumentException invalid) {
      return operation(
          failedJob(true, INVALID_REQUEST, "Exact raw product and region required", invalid));
    }
    var job = new Job<DiscoveryEvent>(true);
    if (admit(job))
      dispatch(
          job,
          token -> {
            var event = gateway.observe(productId, region, token);
            if (event == null
                || !event.productId().equals(productId)
                || !event.region().equals(region))
              throw error(
                  CORRUPT_OBSERVATION, "Retrieved observation changed requested product or region");
            token.throwIfCancellationRequested();
            DiscoveryEvent stored;
            try {
              stored = repository.append(event, limits, token);
            } catch (CancellationException cancelled) {
              throw cancelled;
            } catch (RuntimeException failed) {
              throw new DiscoveryHistoryException(
                  OBSERVATION_NOT_SAVED,
                  "Retrieved observation was not saved: " + failed.getMessage(),
                  failed);
            }
            // Append owns the transaction boundary. A committed event wins a late cancellation.
            if (!event.equals(stored))
              throw error(
                  CORRUPT_OBSERVATION, "Repository returned different committed observation facts");
            return stored;
          });
    return operation(job);
  }

  @Override
  public CompletionStage<DiscoveryHistoryPage> history(
      DiscoveryHistoryFilter filter, Optional<DiscoveryHistoryCursor> cursor, int pageSize) {
    try {
      Objects.requireNonNull(filter);
      Objects.requireNonNull(cursor);
      HistoryValues.pageSize(pageSize);
      cursor.ifPresent(value -> value.requireContext(filter, pageSize));
    } catch (IllegalArgumentException | NullPointerException invalid) {
      return DefaultDiscoveryHistoryService.<DiscoveryHistoryPage>failedJob(
              false, INVALID_REQUEST, "Bounded exact history query required", invalid)
          .result;
    }
    var job = new Job<DiscoveryHistoryPage>(false);
    if (admit(job))
      dispatch(
          job,
          token -> {
            var page = repository.page(filter, cursor, pageSize, token);
            token.throwIfCancellationRequested();
            requirePage(page, filter, cursor, pageSize);
            return page;
          });
    return job.result;
  }

  @Override
  public CompletionStage<DiscoveryHistorySelection> select(
      DiscoveryObservationSelection selection) {
    if (selection == null)
      return DefaultDiscoveryHistoryService.<DiscoveryHistorySelection>failedJob(
              false, INVALID_REQUEST, "Exact event, snapshot and row selection required", null)
          .result;
    var job = new Job<DiscoveryHistorySelection>(false);
    if (admit(job))
      dispatch(
          job,
          token -> {
            var found = repository.read(selection.eventId(), token);
            token.throwIfCancellationRequested();
            if (found == null)
              throw error(CORRUPT_OBSERVATION, "Repository omitted observation result");
            var event =
                found.orElseThrow(() -> error(NOT_FOUND, "Selected observation was not found"));
            if (!event.eventId().equals(selection.eventId()))
              throw error(CORRUPT_OBSERVATION, "Repository returned another observation");
            var row =
                event.builds().stream()
                    .filter(
                        value ->
                            value.versionsRowOrdinal() == selection.versionsRowOrdinal()
                                && value.build().snapshotId().equals(selection.snapshotId()))
                    .findFirst()
                    .orElseThrow(
                        () ->
                            error(
                                NOT_FOUND,
                                "Selected snapshot row was not found in this observation"));
            return new DiscoveryHistorySelection(selection, event, row);
          });
    return job.result;
  }

  private boolean admit(Job<?> job) {
    DiscoveryHistoryException failure = null;
    synchronized (this) {
      if (closed) failure = error(CLOSED, "Discovery history service is closed");
      else if (active.size() >= 4)
        failure = error(BUSY, "Four discovery history jobs are still active");
      else active.add(job);
    }
    if (failure != null) {
      job.result.completeExceptionally(failure);
      return false;
    }
    if (!job.refresh)
      job.result.whenComplete(
          (value, failed) -> {
            if (job.result.isCancelled()) job.cancellation.set(true);
          });
    return true;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(CancellationToken token);
  }

  private <T> void dispatch(Job<T> job, Work<T> work) {
    try {
      executor.execute(
          () -> {
            synchronized (this) {
              job.started = true;
            }
            T value = null;
            Throwable failure = null;
            CancellationToken token =
                () ->
                    closed || job.cancellation.get() || (!job.refresh && job.result.isCancelled());
            try {
              token.throwIfCancellationRequested();
              value = work.run(token);
            } catch (Throwable failed) {
              failure = failed;
            } finally {
              synchronized (this) {
                active.remove(job);
              }
            }
            // Actual source/transaction work has retired. Completion callbacks may admit another
            // job.
            if (failure == null) job.result.complete(value);
            else job.result.completeExceptionally(failure);
          });
    } catch (RuntimeException rejected) {
      boolean unstarted;
      synchronized (this) {
        unstarted = !job.started;
        if (unstarted) active.remove(job);
      }
      if (unstarted)
        job.result.completeExceptionally(
            new DiscoveryHistoryException(BUSY, "History executor rejected admission", rejected));
    }
  }

  private static void requirePage(
      DiscoveryHistoryPage page,
      DiscoveryHistoryFilter filter,
      Optional<DiscoveryHistoryCursor> cursor,
      int pageSize) {
    if (page == null || !page.filter().equals(filter) || page.events().size() > pageSize)
      throw error(CORRUPT_OBSERVATION, "Repository history page changed query scope");
    page.nextCursor()
        .ifPresent(
            next -> {
              try {
                next.requireContext(filter, pageSize);
              } catch (IllegalArgumentException invalid) {
                throw new DiscoveryHistoryException(
                    CORRUPT_OBSERVATION, "Repository cursor changed query scope", invalid);
              }
            });
    if (cursor.isPresent()) {
      var current = cursor.get();
      if (page.events().stream()
              .anyMatch(
                  event ->
                      event.sequence() > current.upperSequence()
                          || event.sequence() >= current.beforeSequence())
          || page.nextCursor().isPresent()
              && page.nextCursor().get().upperSequence() != current.upperSequence())
        throw error(
            CORRUPT_OBSERVATION, "Repository page changed stable history watermark or position");
    }
  }

  private static RefreshOperation operation(Job<DiscoveryEvent> job) {
    var readOnly = job.result.minimalCompletionStage();
    return new RefreshOperation() {
      public CompletionStage<DiscoveryEvent> completion() {
        return readOnly;
      }

      public void requestCancellation() {
        job.cancellation.set(true);
      }
    };
  }

  private static <T> Job<T> failedJob(
      boolean refresh, DiscoveryHistoryException.Code code, String message, Throwable cause) {
    var job = new Job<T>(refresh);
    job.result.completeExceptionally(
        cause == null ? error(code, message) : new DiscoveryHistoryException(code, message, cause));
    return job;
  }

  private static DiscoveryHistoryException error(
      DiscoveryHistoryException.Code code, String message) {
    return new DiscoveryHistoryException(code, message);
  }

  @Override
  public void close() {
    List<Job<?>> jobs;
    synchronized (this) {
      if (closed) return;
      closed = true;
      jobs = List.copyOf(active);
    }
    for (var job : jobs) {
      job.cancellation.set(true);
      if (!job.refresh) job.result.cancel(false);
    }
  }
}
