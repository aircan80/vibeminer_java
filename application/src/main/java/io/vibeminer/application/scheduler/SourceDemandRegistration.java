package io.vibeminer.application.scheduler;

import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Late binding of demand handles for an existing producer flight. This class owns no artifact
 * results, source coalescing, verification or publication permits. User callbacks run outside
 * locks.
 */
public final class SourceDemandRegistration implements AutoCloseable {
  public final class Subscription implements AutoCloseable {
    final PrioritySourceScheduler.Demand demand;
    final Consumer<RuntimeException> rejected;
    PrioritySourceScheduler.DemandHandle handle;
    boolean binding, closed;

    private Subscription(
        PrioritySourceScheduler.Demand demand, Consumer<RuntimeException> rejected) {
      this.demand = demand;
      this.rejected = rejected;
    }

    @Override
    public void close() {
      PrioritySourceScheduler.DemandHandle old;
      synchronized (monitor) {
        if (closed) return;
        closed = true;
        subscriptions.remove(this);
        old = handle;
      }
      if (old != null) old.close();
    }
  }

  private final Object monitor = new Object();
  private final PrioritySourceScheduler scheduler;
  private final ResolvedSourceIdentity identity;
  private final List<Subscription> subscriptions = new ArrayList<>();
  private PrioritySourceScheduler.ScheduledSource scheduled;
  private boolean started, closed;
  private Consumer<Throwable> retirementFailure;
  private final CompletableFuture<Void> retired = new CompletableFuture<>();
  private final AtomicBoolean entered = new AtomicBoolean();
  private final AtomicBoolean neverStartedReported = new AtomicBoolean();

  public SourceDemandRegistration(
      PrioritySourceScheduler scheduler, ResolvedSourceIdentity identity) {
    this.scheduler = Objects.requireNonNull(scheduler);
    this.identity = Objects.requireNonNull(identity);
  }

  /** Actual scheduler retirement, independent of consumer cancellation or artifact outcomes. */
  public CompletionStage<Void> retirement() {
    return retired.minimalCompletionStage();
  }

  public Subscription register(
      PrioritySourceScheduler.Demand demand, Consumer<RuntimeException> rejected) {
    var subscription =
        new Subscription(Objects.requireNonNull(demand), Objects.requireNonNull(rejected));
    RuntimeException failure = null;
    synchronized (monitor) {
      if (closed) subscription.closed = true;
      else if (subscriptions.size() >= PrioritySourceScheduler.MAXIMUM_DEMANDS_PER_SOURCE)
        failure =
            new PrioritySourceException(
                PrioritySourceException.Code.CAPACITY_EXCEEDED,
                "Source demand registration limit reached");
      else subscriptions.add(subscription);
    }
    if (failure != null) rejected.accept(failure);
    else bind(subscription);
    return subscription;
  }

  public void start(PrioritySourceScheduler.SourceWork work, Consumer<Throwable> neverStarted) {
    Objects.requireNonNull(work);
    Objects.requireNonNull(neverStarted);
    synchronized (monitor) {
      if (started) throw new IllegalStateException("Producer scheduling already started");
      started = true;
      retirementFailure = neverStarted;
    }
    Subscription initial;
    while (true) {
      initial = null;
      synchronized (monitor) {
        for (var subscription : subscriptions)
          if (!subscription.closed && !subscription.demand.isCancellationRequested()) {
            if (initial == null || subscription.demand.isForeground()) initial = subscription;
            if (subscription.demand.isForeground()) break;
          }
        if (initial != null) initial.binding = true;
      }
      if (initial == null) {
        try {
          reportNeverStarted(neverStarted, new CancellationException("No active source demand"));
        } finally {
          close();
          retired.complete(null);
        }
        return;
      }
      try {
        var selectedInitial = initial;
        scheduler.submit(
            identity,
            token -> {
              entered.set(true);
              work.run(token);
            },
            initial.demand,
            issued -> bindIssued(issued, selectedInitial));
        break;
      } catch (RuntimeException | Error failure) {
        boolean issued;
        synchronized (monitor) {
          issued = scheduled != null;
        }
        if (issued) {
          // A sibling dispatched synchronously can throw after our wrapper was accepted.
          // Withdraw demands, but only the issued scheduler retirement may retire ownership.
          close();
          if (failure instanceof Error fatal) throw fatal;
          return;
        }
        if (failure instanceof PrioritySourceException scoped
            && scoped.code() == PrioritySourceException.Code.BULK_DISABLED
            && !entered.get()) {
          // Mode-off can race initial admission. Withdraw only that consumer and try a survivor.
          initial.close();
          initial.rejected.accept(new CancellationException("Bulk generation disabled"));
          continue;
        }
        try {
          reportNeverStarted(neverStarted, failure);
        } finally {
          close();
          retired.complete(null);
        }
        if (failure instanceof Error fatal) throw fatal;
        return;
      }
    }
    List<Subscription> pending;
    synchronized (monitor) {
      pending = List.copyOf(subscriptions);
    }
    for (var subscription : pending) bind(subscription);
  }

  private void bindIssued(PrioritySourceScheduler.ScheduledSource issued, Subscription initial) {
    synchronized (monitor) {
      scheduled = issued;
      initial.handle = issued.initialDemand();
    }
    issued
        .retirement()
        .whenComplete(
            (ignored, failure) -> {
              try {
                var callback = retirementFailure;
                if (callback != null && !entered.get())
                  reportNeverStarted(
                      callback,
                      failure == null
                          ? new CancellationException("Source retired before producer entry")
                          : failure);
              } finally {
                close();
                retired.complete(null);
              }
            });
    List<Subscription> pending;
    synchronized (monitor) {
      pending = List.copyOf(subscriptions);
    }
    for (var subscription : pending) bind(subscription);
    boolean discardInitial;
    synchronized (monitor) {
      discardInitial = closed || initial.closed;
    }
    if (discardInitial) issued.initialDemand().close();
  }

  private void reportNeverStarted(Consumer<Throwable> callback, Throwable failure) {
    if (!entered.get() && neverStartedReported.compareAndSet(false, true)) callback.accept(failure);
  }

  private void bind(Subscription subscription) {
    PrioritySourceScheduler.ScheduledSource actual;
    synchronized (monitor) {
      if (closed
          || subscription.closed
          || subscription.binding
          || subscription.handle != null
          || scheduled == null) return;
      subscription.binding = true;
      actual = scheduled;
    }
    try {
      install(subscription, actual.attach(subscription.demand));
    } catch (RuntimeException failure) {
      boolean report;
      synchronized (monitor) {
        report =
            !closed && !subscription.closed && !actual.retirement().toCompletableFuture().isDone();
        subscription.closed = true;
        subscriptions.remove(subscription);
      }
      if (report) subscription.rejected.accept(failure);
    }
  }

  private void install(Subscription subscription, PrioritySourceScheduler.DemandHandle handle) {
    boolean discard;
    synchronized (monitor) {
      discard = closed || subscription.closed;
      if (!discard) subscription.handle = handle;
    }
    if (discard) handle.close();
  }

  @Override
  public void close() {
    List<Subscription> old;
    synchronized (monitor) {
      if (closed) return;
      closed = true;
      old = List.copyOf(subscriptions);
    }
    for (var subscription : old) subscription.close();
  }
}
