package io.vibeminer.application.scheduler;

import static io.vibeminer.application.scheduler.PrioritySourceException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Admission and consumer priority for source workers, not a downloader or artifact authority. The
 * caller owns the executor, source verification, publication, and artifact completion. Four
 * accepted executor wrappers may be outstanding, at most two admitted as bulk. A running wrapper's
 * admission class never changes on promotion/demotion: capacity is released only by its actual
 * finally. Planning HTTP and other executors are outside these limits. Within each effective
 * priority lane, original source-admission order is preserved; promotion retains that order. After
 * three contested foreground admissions an eligible bulk source runs.
 */
public final class PrioritySourceScheduler implements AutoCloseable {
  public static final int MAXIMUM_ACTUAL_WORKERS = 4;
  public static final int MAXIMUM_BULK_WORKERS = 2;
  public static final int MAXIMUM_QUEUED_SOURCES = 64;
  public static final int MAXIMUM_DEMANDS = 256;
  public static final int MAXIMUM_DEMANDS_PER_SOURCE = 32;

  @FunctionalInterface
  public interface SourceWork {
    void run(CancellationToken cancellation);
  }

  /**
   * A unique mode generation. Closing one generation cannot stop another or a foreground reader.
   */
  public final class BulkOwner {
    private final String snapshotId;
    private final UUID generation = UUID.randomUUID();
    private final AtomicBoolean disabled = new AtomicBoolean();

    private BulkOwner(String snapshotId) {
      this.snapshotId = snapshotId;
    }

    public String snapshotId() {
      return snapshotId;
    }

    public UUID generation() {
      return generation;
    }

    public Demand demand() {
      return new Demand(this);
    }

    private PrioritySourceScheduler getScheduler() {
      return PrioritySourceScheduler.this;
    }
  }

  /** Foreground demand has no bulk owner and survives bulk mode cancellation. */
  public static final class Demand {
    private final BulkOwner owner;

    private Demand(BulkOwner owner) {
      this.owner = owner;
    }

    /** Mode-off applies only to this generation; foreground demand is independent. */
    public boolean isForeground() {
      return owner == null;
    }

    public boolean isCancellationRequested() {
      return owner != null && owner.disabled.get();
    }

    public static Demand foreground() {
      return new Demand(null);
    }
  }

  public final class DemandHandle implements AutoCloseable {
    private final Flight flight;
    private final Demand demand;
    private boolean detached;

    private DemandHandle(Flight flight, Demand demand) {
      this.flight = flight;
      this.demand = demand;
    }

    @Override
    public void close() {
      detach(this);
    }
  }

  public final class ScheduledSource {
    private final Flight flight;
    private final DemandHandle initial;

    private ScheduledSource(Flight flight, DemandHandle initial) {
      this.flight = flight;
      this.initial = initial;
    }

    public ResolvedSourceIdentity source() {
      return flight.source;
    }

    public DemandHandle initialDemand() {
      return initial;
    }

    /** Only this issued handle can attach to the existing producer closure. */
    public DemandHandle attach(Demand demand) {
      return attachDemand(flight, demand);
    }

    /** No external future cancellation can retire the source or replace its actual outcome. */
    public CompletionStage<Void> retirement() {
      return flight.retired.minimalCompletionStage();
    }
  }

  private enum State {
    QUEUED,
    DISPATCHED,
    RETIRED
  }

  private final class Flight {
    final ResolvedSourceIdentity source;
    final SourceWork work;
    final List<DemandHandle> demands = new ArrayList<>();
    final CompletableFuture<Void> retired = new CompletableFuture<>();
    final AtomicBoolean cancelled = new AtomicBoolean();
    State state = State.QUEUED;
    boolean admittedBulk;
    boolean bound;

    Flight(ResolvedSourceIdentity source, SourceWork work) {
      this.source = source;
      this.work = work;
    }

    CancellationToken cancelledToken() {
      return cancelled::get;
    }

    boolean foreground() {
      return demands.stream().anyMatch(handle -> handle.demand.owner == null);
    }
  }

  private final Object monitor = new Object();
  private final Executor executor;
  private final Map<ResolvedSourceIdentity, Flight> sources = new HashMap<>();
  private final List<Flight> queued = new ArrayList<>();
  private int dispatched, dispatchedBulk, demandCount, foregroundStreak;
  private boolean closed;

  public PrioritySourceScheduler(Executor executor) {
    this.executor = Objects.requireNonNull(executor);
  }

  public BulkOwner newBulkOwner(String snapshotId) {
    if (snapshotId == null || !snapshotId.matches("[0-9a-f]{64}"))
      throw error(INVALID_REQUEST, "Exact snapshot identity required");
    synchronized (monitor) {
      if (closed) throw error(CLOSED, "Source scheduler closed");
      return new BulkOwner(snapshotId);
    }
  }

  public ScheduledSource submit(ResolvedSourceIdentity source, SourceWork work, Demand demand) {
    return submit(source, work, demand, issued -> {});
  }

  /** Internal binding boundary: no producer can execute before the issued handle is bound. */
  ScheduledSource submit(
      ResolvedSourceIdentity source,
      SourceWork work,
      Demand demand,
      java.util.function.Consumer<ScheduledSource> beforeDispatch) {
    Objects.requireNonNull(beforeDispatch);
    Objects.requireNonNull(source);
    Objects.requireNonNull(work);
    ScheduledSource result;
    synchronized (monitor) {
      checkAdmission(demand);
      if (sources.containsKey(source))
        throw error(BUSY, "An independent producer already owns this exact source");
      if (queued.size() >= MAXIMUM_QUEUED_SOURCES)
        throw error(CAPACITY_EXCEEDED, "Source queue is full");
      var flight = new Flight(source, work);
      var initial = addDemand(flight, demand);
      sources.put(source, flight);
      queued.add(flight);
      result = new ScheduledSource(flight, initial);
    }
    try {
      beforeDispatch.accept(result);
    } catch (RuntimeException | Error failedBinding) {
      synchronized (monitor) {
        for (var handle : List.copyOf(result.flight.demands)) detachLocked(handle);
        queued.remove(result.flight);
        sources.remove(result.flight.source, result.flight);
        result.flight.state = State.RETIRED;
      }
      result.flight.retired.completeExceptionally(failedBinding);
      pump();
      throw failedBinding;
    }
    synchronized (monitor) {
      result.flight.bound = true;
    }
    pump();
    return result;
  }

  private DemandHandle attachDemand(Flight flight, Demand demand) {
    DemandHandle handle;
    synchronized (monitor) {
      checkAdmission(demand);
      if (flight.state == State.RETIRED || flight.cancelled.get())
        throw error(BUSY, "Source has completed or is retiring");
      handle = addDemand(flight, demand);
    }
    pump();
    return handle;
  }

  private void checkAdmission(Demand demand) {
    if (closed) throw error(CLOSED, "Source scheduler closed");
    if (demand == null) throw error(INVALID_REQUEST, "Consumer demand required");
    if (demand.owner != null) {
      if (demand.owner.getScheduler() != this)
        throw error(INVALID_REQUEST, "Bulk generation belongs to another scheduler");
      if (demand.owner.disabled.get()) throw error(BULK_DISABLED, "Bulk generation disabled");
    }
    if (demandCount >= MAXIMUM_DEMANDS)
      throw error(CAPACITY_EXCEEDED, "Consumer demand limit reached");
  }

  private DemandHandle addDemand(Flight flight, Demand demand) {
    if (flight.demands.size() >= MAXIMUM_DEMANDS_PER_SOURCE)
      throw error(CAPACITY_EXCEEDED, "Source consumer limit reached");
    var handle = new DemandHandle(flight, demand);
    flight.demands.add(handle);
    demandCount++;
    return handle;
  }

  public void cancelBulk(BulkOwner owner) {
    if (owner == null || owner.getScheduler() != this)
      throw error(INVALID_REQUEST, "Owned bulk generation required");
    var retired = new ArrayList<Flight>();
    synchronized (monitor) {
      owner.disabled.set(true);
      for (var flight : List.copyOf(sources.values())) {
        for (var handle : List.copyOf(flight.demands))
          if (handle.demand.owner == owner) detachLocked(handle);
        retireEmptyQueued(flight, retired);
      }
    }
    completeCancelled(retired);
    pump();
  }

  private void detach(DemandHandle handle) {
    var retired = new ArrayList<Flight>();
    synchronized (monitor) {
      detachLocked(handle);
      retireEmptyQueued(handle.flight, retired);
    }
    completeCancelled(retired);
    pump();
  }

  private void detachLocked(DemandHandle handle) {
    if (handle.detached) return;
    handle.detached = true;
    if (handle.flight.demands.remove(handle)) demandCount--;
    if (handle.flight.demands.isEmpty()) handle.flight.cancelled.set(true);
  }

  private void retireEmptyQueued(Flight flight, List<Flight> retired) {
    if (flight.state != State.QUEUED || !flight.demands.isEmpty()) return;
    queued.remove(flight);
    sources.remove(flight.source, flight);
    flight.state = State.RETIRED;
    retired.add(flight);
  }

  private void completeCancelled(List<Flight> retired) {
    for (var flight : retired)
      flight.retired.completeExceptionally(new CancellationException("Source has no consumers"));
  }

  private void pump() {
    var dispatch = new ArrayList<Flight>();
    synchronized (monitor) {
      while (!closed && dispatched < MAXIMUM_ACTUAL_WORKERS) {
        Flight foreground = null, bulk = null;
        for (var flight : queued) {
          if (!flight.bound) continue;
          if (flight.foreground()) {
            if (foreground == null) foreground = flight;
          } else if (bulk == null && dispatchedBulk < MAXIMUM_BULK_WORKERS) bulk = flight;
        }
        Flight next;
        if (bulk != null && (foreground == null || foregroundStreak >= 3)) {
          next = bulk;
          foregroundStreak = 0;
        } else if (foreground != null) {
          next = foreground;
          foregroundStreak = bulk == null ? 0 : Math.min(3, foregroundStreak + 1);
        } else break;
        queued.remove(next);
        next.state = State.DISPATCHED;
        next.admittedBulk = !next.foreground();
        dispatched++;
        if (next.admittedBulk) dispatchedBulk++;
        dispatch.add(next);
      }
    }
    Error fatal = null;
    for (var flight : dispatch) {
      try {
        try {
          executor.execute(() -> run(flight));
        } catch (RuntimeException rejected) {
          finish(
              flight,
              new PrioritySourceException(
                  CAPACITY_EXCEEDED, "Borrowed source executor rejected work", rejected));
        }
      } catch (Error failed) {
        // A direct executor may propagate an already-retired producer's Error, or fail before
        // invoking its wrapper. Retire the latter, then dispatch every other reserved wrapper.
        try {
          finish(flight, failed);
        } catch (Error cleanupFailure) {
          if (cleanupFailure != failed) failed.addSuppressed(cleanupFailure);
        }
        if (fatal == null) fatal = failed;
        else if (fatal != failed) fatal.addSuppressed(failed);
      }
    }
    if (fatal != null) throw fatal;
  }

  private void run(Flight flight) {
    Throwable failure = null;
    try {
      // Cancellation is cooperative; no borrowed thread interruption.
      flight.cancelledToken().throwIfCancellationRequested();
      flight.work.run(flight.cancelledToken());
      // The producer's successful publication wins a late cancellation; no post-work check.
    } catch (Throwable failed) {
      failure = failed;
    } finally {
      finish(flight, failure);
    }
    if (failure instanceof Error error) throw error;
  }

  private void finish(Flight flight, Throwable failure) {
    synchronized (monitor) {
      if (flight.state == State.RETIRED) return;
      flight.state = State.RETIRED;
      sources.remove(flight.source, flight);
      dispatched--;
      if (flight.admittedBulk) dispatchedBulk--;
      for (var demand : List.copyOf(flight.demands)) detachLocked(demand);
    }
    if (failure == null) flight.retired.complete(null);
    else flight.retired.completeExceptionally(failure);
    pump();
  }

  @Override
  public void close() {
    var retired = new ArrayList<Flight>();
    synchronized (monitor) {
      if (closed) return;
      closed = true;
      for (var flight : List.copyOf(sources.values())) {
        for (var demand : List.copyOf(flight.demands)) detachLocked(demand);
        retireEmptyQueued(flight, retired);
      }
    }
    completeCancelled(retired);
  }

  private static PrioritySourceException error(PrioritySourceException.Code code, String message) {
    return new PrioritySourceException(code, message);
  }
}
