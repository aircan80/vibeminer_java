package io.vibeminer.application.buildlibrary;

import static io.vibeminer.application.buildlibrary.BuildLibraryException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.discoveryhistory.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One paired capture per refresh, independent durable phases, and four actual borrowed workers.
 * Initial support bounds the union of known/captured regional scopes to 64 so every primary and
 * check-recording failure fits the existing 128-outcome contract without truncation.
 */
public final class DefaultBuildLibraryService implements BuildLibraryService {
  /**
   * Successful paired retrieval retained as evidence when the supported scope limit refuses saving.
   */
  public static final class CapturedObservationEvidence extends RuntimeException {
    private final BuildLibraryProductObservation observation;

    private CapturedObservationEvidence(BuildLibraryProductObservation observation) {
      super("Paired source capture succeeded; regional observation was not saved");
      this.observation = Objects.requireNonNull(observation);
    }

    public BuildLibraryProductObservation observation() {
      return observation;
    }
  }

  private final BuildLibraryProductGateway gateway;
  private final DiscoveryHistoryRepository history;
  private final BuildLibraryRepository library;
  private final BuildLibraryProductChecks checks;
  private final BuildLibraryReader reader;
  private final DiscoveryHistoryLimits limits;
  private final Executor executor;
  private final Clock clock;
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

  private record FailedCheck(
      BuildLibraryCheckTicket check, BuildLibraryRepository.Failure outcome) {}

  @FunctionalInterface
  private interface Work<T> {
    T run(CancellationToken token);
  }

  public DefaultBuildLibraryService(
      BuildLibraryProductGateway gateway,
      DiscoveryHistoryRepository history,
      BuildLibraryRepository library,
      BuildLibraryProductChecks checks,
      BuildLibraryReader reader,
      Executor executor,
      Clock clock) {
    this(
        gateway,
        history,
        library,
        checks,
        reader,
        DiscoveryHistoryLimits.defaults(),
        executor,
        clock);
  }

  public DefaultBuildLibraryService(
      BuildLibraryProductGateway gateway,
      DiscoveryHistoryRepository history,
      BuildLibraryRepository library,
      BuildLibraryProductChecks checks,
      BuildLibraryReader reader,
      DiscoveryHistoryLimits limits,
      Executor executor,
      Clock clock) {
    this.gateway = Objects.requireNonNull(gateway);
    this.history = Objects.requireNonNull(history);
    this.library = Objects.requireNonNull(library);
    this.checks = Objects.requireNonNull(checks);
    this.reader = Objects.requireNonNull(reader);
    this.limits = Objects.requireNonNull(limits);
    this.executor = Objects.requireNonNull(executor);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public RefreshOperation refreshProduct(String productId) {
    var job = new Job<BuildLibraryRefreshResult>(true);
    try {
      new DiscoveryHistoryFilter(productId, Optional.empty(), Optional.empty());
    } catch (IllegalArgumentException invalid) {
      job.result.completeExceptionally(
          error(INVALID_REQUEST, "Exact raw product required", invalid));
      return operation(job);
    }
    if (admit(job)) dispatch(job, token -> refresh(productId, token));
    return operation(job);
  }

  private BuildLibraryRefreshResult refresh(String product, CancellationToken token) {
    Instant requested = clock.instant();
    var parent = checks.beginProductCheck(product, requested, token);
    if (parent == null
        || !parent.productId().equals(product)
        || !parent.requestedAt().equals(requested))
      throw error(CORRUPT_LIBRARY, "Product admission changed requested scope or time");
    var known = checkedKnownScopes(product, checks.knownScopes(product, token));
    BuildLibraryProductObservation observation;
    try {
      token.throwIfCancellationRequested();
      observation = gateway.observeProduct(product, token);
      if (observation == null || !observation.productId().equals(product))
        throw error(CORRUPT_LIBRARY, "Product capture changed admitted identity");
    } catch (RuntimeException failure) {
      recordWholeFailure(parent, known, failure, token);
      throw failure;
    }
    var allScopes = new LinkedHashSet<BuildLibraryScope>(known);
    observation
        .events()
        .forEach(event -> allScopes.add(new BuildLibraryScope(product, event.region())));
    observation.failures().forEach(failed -> allScopes.add(failed.scope()));
    if (allScopes.size() > 64) {
      var failure =
          error(
              LIMIT_EXCEEDED,
              "Combined prior and captured regional scopes exceed 64; observation not saved",
              new CapturedObservationEvidence(observation));
      recordWholeFailure(parent, known, failure, token);
      throw failure;
    }
    var registrations = new ArrayList<BuildLibraryRegistration>();
    var failures = new ArrayList<BuildLibraryRefreshResult.ScopeFailure>();
    var tickets = new LinkedHashMap<BuildLibraryScope, BuildLibraryCheckTicket>();
    var failedChecks = new LinkedHashMap<BuildLibraryScope, FailedCheck>();
    var successful = new HashSet<BuildLibraryScope>();
    var outcomes = new HashSet<BuildLibraryScope>();
    for (var event : observation.events()) {
      var scope = new BuildLibraryScope(product, event.region());
      outcomes.add(scope);
      BuildLibraryCheckTicket check;
      try {
        check = child(parent, scope);
        tickets.put(scope, check);
      } catch (RuntimeException failed) {
        failures.add(failure(scope, BuildLibraryRefreshResult.Phase.CHECK_RECORDING, failed));
        continue;
      }
      DiscoveryEvent stored;
      try {
        token.throwIfCancellationRequested();
        stored = history.append(event, limits, token);
        if (!event.equals(stored))
          throw error(CORRUPT_LIBRARY, "History returned changed actual committed regional event");
      } catch (RuntimeException failed) {
        failures.add(failure(scope, BuildLibraryRefreshResult.Phase.HISTORY_APPEND, failed));
        failedChecks.put(scope, new FailedCheck(check, outcome(failed, token)));
        continue;
      }
      try {
        var result =
            library.recordObservation(
                stored.eventId(),
                BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK,
                Optional.of(check),
                clock.instant(),
                CancellationToken.none());
        requireRegistration(stored, check, result);
        registrations.add(result);
        successful.add(scope);
      } catch (RuntimeException failed) {
        failures.add(failure(scope, BuildLibraryRefreshResult.Phase.LIBRARY_REGISTRATION, failed));
        failedChecks.put(scope, new FailedCheck(check, outcome(failed, token)));
      }
    }
    for (var failed : observation.failures()) {
      outcomes.add(failed.scope());
      var cause = new DiscoveryHistoryException(failed.code(), failed.message());
      failures.add(
          failure(failed.scope(), BuildLibraryRefreshResult.Phase.CAPTURE_INTERPRETATION, cause));
      scheduleFailure(
          parent, failed.scope(), outcome(cause, token), tickets, failedChecks, failures);
    }
    for (var scope : known)
      if (!outcomes.contains(scope)) {
        var cause =
            new DiscoveryHistoryException(
                DiscoveryHistoryException.Code.NOT_FOUND,
                "Paired capture did not establish this previously known region");
        failures.add(failure(scope, BuildLibraryRefreshResult.Phase.CAPTURE_INTERPRETATION, cause));
        scheduleFailure(parent, scope, outcome(cause, token), tickets, failedChecks, failures);
      }
    // Source retrieval and regional append/registration attempts are over. Only now publish failed
    // check outcomes, using a noncancelled cleanup token and preserving successful checks.
    for (var entry : failedChecks.entrySet())
      if (!successful.contains(entry.getKey())) {
        try {
          recordFailure(entry.getValue());
        } catch (RuntimeException failed) {
          failures.add(
              failure(entry.getKey(), BuildLibraryRefreshResult.Phase.CHECK_RECORDING, failed));
        }
      }
    return new BuildLibraryRefreshResult(
        parent, observation, registrations, failures, token.isCancellationRequested());
  }

  private void scheduleFailure(
      BuildLibraryProductCheckTicket parent,
      BuildLibraryScope scope,
      BuildLibraryRepository.Failure outcome,
      Map<BuildLibraryScope, BuildLibraryCheckTicket> tickets,
      Map<BuildLibraryScope, FailedCheck> pending,
      List<BuildLibraryRefreshResult.ScopeFailure> failures) {
    try {
      var check = tickets.get(scope);
      if (check == null) {
        check = child(parent, scope);
        tickets.put(scope, check);
      }
      pending.put(scope, new FailedCheck(check, outcome));
    } catch (RuntimeException failed) {
      failures.add(failure(scope, BuildLibraryRefreshResult.Phase.CHECK_RECORDING, failed));
    }
  }

  private void recordWholeFailure(
      BuildLibraryProductCheckTicket parent,
      List<BuildLibraryScope> known,
      RuntimeException original,
      CancellationToken token) {
    for (var scope : known)
      try {
        recordFailure(new FailedCheck(child(parent, scope), outcome(original, token)));
      } catch (RuntimeException cleanup) {
        if (cleanup != original) original.addSuppressed(cleanup);
      }
  }

  private void recordFailure(FailedCheck failed) {
    var result =
        library.recordFailedScope(
            failed.check(), failed.outcome(), clock.instant(), CancellationToken.none());
    // A stale child cannot overwrite a newer successful check; the repository may truthfully
    // return that newer state. Validate scope, not equality with this attempted failure outcome.
    if (result == null || !result.scope().equals(failed.check().scope()))
      throw error(CORRUPT_LIBRARY, "Failed-scope recording changed actual scope");
  }

  private BuildLibraryCheckTicket child(
      BuildLibraryProductCheckTicket parent, BuildLibraryScope scope) {
    var ticket = checks.deriveRegionalCheck(parent, scope.region(), CancellationToken.none());
    if (ticket == null
        || !ticket.scope().equals(scope)
        || ticket.ordinal() != parent.ordinal()
        || !ticket.requestedAt().equals(parent.requestedAt()))
      throw error(CORRUPT_LIBRARY, "Regional admission did not inherit original product order");
    return ticket;
  }

  private static List<BuildLibraryScope> checkedKnownScopes(
      String product, List<BuildLibraryScope> values) {
    if (values == null
        || new HashSet<>(values).size() != values.size()
        || values.stream().anyMatch(scope -> scope == null || !scope.productId().equals(product)))
      throw error(CORRUPT_LIBRARY, "Known scopes changed admitted product or bounds");
    if (values.size() > 64) throw error(LIMIT_EXCEEDED, "Known regional scope count exceeds64");
    return List.copyOf(values);
  }

  private static void requireRegistration(
      DiscoveryEvent event, BuildLibraryCheckTicket ticket, BuildLibraryRegistration result) {
    if (result == null
        || result.kind() != BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK
        || !result.eventId().equals(event.eventId())
        || !result.check().equals(Optional.of(ticket))
        || !result
            .references()
            .equals(
                BuildLibraryReference.from(
                    event, result.references().getFirst().observationSequence())))
      throw error(
          CORRUPT_LIBRARY,
          "Registration changed actual committed event, reference facts or admission");
  }

  @Override
  public CompletionStage<BuildLibraryPage> page(
      BuildLibraryQuery query, Optional<BuildLibraryCursor> cursor, int pageSize) {
    try {
      Objects.requireNonNull(query);
      Objects.requireNonNull(cursor);
      if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException();
      cursor.ifPresent(c -> c.requireContext(query, pageSize, c.libraryRevision()));
    } catch (IllegalArgumentException | NullPointerException invalid) {
      return failed(INVALID_REQUEST, "Bounded exact library page required", invalid);
    }
    var job = new Job<BuildLibraryPage>(false);
    if (admit(job))
      dispatch(
          job,
          token -> {
            var page = reader.page(query, cursor, pageSize, token);
            token.throwIfCancellationRequested();
            if (page == null || !page.query().equals(query) || page.pageSize() != pageSize)
              throw error(CORRUPT_LIBRARY, "Library page changed requested scope");
            if (cursor.isPresent()) {
              var c = cursor.get();
              if (page.libraryRevision() != c.libraryRevision())
                throw error(STALE_CURSOR, "Library changed since requested page");
              for (var entry : page.entries())
                if (entry.firstRegistrationSequence() > c.beforeFirstRegistrationSequence()
                    || entry.firstRegistrationSequence() == c.beforeFirstRegistrationSequence()
                        && entry.row().group().key().identity().compareTo(c.beforeGroupId()) <= 0)
                  throw error(CORRUPT_LIBRARY, "Library page crossed requested cursor position");
            }
            return page;
          });
    return job.result;
  }

  @Override
  public CompletionStage<BuildLibraryPage.Entry> select(String groupId) {
    try {
      BuildLibraryValues.digest(groupId);
    } catch (IllegalArgumentException invalid) {
      return failed(INVALID_REQUEST, "Exact group identity required", invalid);
    }
    var job = new Job<BuildLibraryPage.Entry>(false);
    if (admit(job))
      dispatch(
          job,
          token -> {
            var entry = reader.select(groupId, token);
            token.throwIfCancellationRequested();
            if (entry == null || !entry.row().group().key().identity().equals(groupId))
              throw error(CORRUPT_LIBRARY, "Library selected a replacement group");
            return entry;
          });
    return job.result;
  }

  private boolean admit(Job<?> job) {
    BuildLibraryException failure = null;
    synchronized (this) {
      if (closed) failure = error(CLOSED, "Build library service is closed");
      else if (active.size() >= 4)
        failure = error(BUSY, "Four build library jobs are still active");
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
            error(BUSY, "Library executor rejected admission", rejected));
    }
  }

  private static RefreshOperation operation(Job<BuildLibraryRefreshResult> job) {
    var readOnly = job.result.minimalCompletionStage();
    return new RefreshOperation() {
      public CompletionStage<BuildLibraryRefreshResult> completion() {
        return readOnly;
      }

      public void requestCancellation() {
        job.cancellation.set(true);
      }
    };
  }

  private static BuildLibraryRepository.Failure outcome(
      Throwable failure, CancellationToken token) {
    return failure instanceof CancellationException || token.isCancellationRequested()
        ? BuildLibraryRepository.Failure.CANCELLED
        : BuildLibraryRepository.Failure.FAILED;
  }

  private static BuildLibraryRefreshResult.ScopeFailure failure(
      BuildLibraryScope scope, BuildLibraryRefreshResult.Phase phase, Throwable cause) {
    return new BuildLibraryRefreshResult.ScopeFailure(scope, phase, cause);
  }

  private static BuildLibraryException error(BuildLibraryException.Code code, String message) {
    return new BuildLibraryException(code, message);
  }

  private static BuildLibraryException error(
      BuildLibraryException.Code code, String message, Throwable cause) {
    return new BuildLibraryException(code, message, cause);
  }

  private static <T> CompletionStage<T> failed(
      BuildLibraryException.Code code, String message, Throwable cause) {
    return CompletableFuture.failedFuture(error(code, message, cause));
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
