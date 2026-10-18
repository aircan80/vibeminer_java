package io.vibeminer.application.acquisition;

import io.vibeminer.application.ObjectStore;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.vibeminer.application.acquisition.AcquisitionException.Code.*;

/**
 * Bounded application coordination over an injected source verifier and object store.
 * Successful source aliases are process-local and bounded; durable alias catalogs come later.
 * This service owns neither the injected executor nor the store. close() requests cancellation,
 * while caller shutdown coordinates final worker termination before closing those resources.
 */
public final class CoalescingAcquisitionService implements AutoCloseable {
    private final Object coordinator = new Object();
    private final ObjectStore store;
    private final VerifiedSourceAcquirer acquirer;
    private final Executor executor;
    private final int maximumSources;
    private final int maximumConsumers;
    private final int maximumAliases;
    private final boolean reuseProcessAliases;
    private final Map<ResolvedSourceIdentity, Flight> inFlight = new HashMap<>();
    private final Set<Flight> active = new HashSet<>();
    private final LinkedHashMap<ResolvedSourceIdentity, VerifiedSourceAlias> aliases = new LinkedHashMap<>(16, .75f, true);
    private boolean closed;

    public CoalescingAcquisitionService(ObjectStore store, VerifiedSourceAcquirer acquirer, Executor executor) {
        this(store, acquirer, executor, 16, 256, 1024);
    }
    /**
     * @param maximumSources total running or queued source tasks, including cancelled tasks not yet retired
     * @param maximumConsumers maximum waiting consumers per source; total waiters are bounded by the product of these limits
     * @param maximumAliases maximum successful source mappings remembered for this process
     */
    public CoalescingAcquisitionService(ObjectStore store, VerifiedSourceAcquirer acquirer, Executor executor,
            int maximumSources, int maximumConsumers, int maximumAliases) {
        this(store, acquirer, executor, maximumSources, maximumConsumers, maximumAliases, true);
    }

    /** Disable alias shortcuts when every source worker must replay a durable authority policy. */
    public CoalescingAcquisitionService(ObjectStore store, VerifiedSourceAcquirer acquirer, Executor executor,
            int maximumSources, int maximumConsumers, int maximumAliases, boolean reuseProcessAliases) {
        this.reuseProcessAliases = reuseProcessAliases;
        this.store = Objects.requireNonNull(store);
        this.acquirer = Objects.requireNonNull(acquirer);
        this.executor = Objects.requireNonNull(executor);
        if (maximumSources < 1 || maximumConsumers < 1 || maximumAliases < 1) throw new IllegalArgumentException("Acquisition limits must be positive");
        this.maximumSources = maximumSources;
        this.maximumConsumers = maximumConsumers;
        this.maximumAliases = maximumAliases;
    }

    public AcquisitionRequest acquire(ResolvedSourceIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Flight flight;
        boolean start = false;
        var result = new CompletableFuture<VerifiedSourceAlias>();
        synchronized (coordinator) {
            if (closed) return failed(CLOSED, "Acquisition service is closed");
            flight = inFlight.get(identity);
            if (flight == null) {
                if (active.size() >= maximumSources) return failed(CAPACITY_EXCEEDED, "Too many source acquisitions are active");
                flight = new Flight(identity);
                inFlight.put(identity, flight);
                active.add(flight);
                start = true;
            } else if (flight.consumers.size() >= maximumConsumers) {
                return failed(CAPACITY_EXCEEDED, "Too many consumers are waiting for this source");
            }
            flight.consumers.add(result);
        }
        var shared = flight;
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) detach(shared, result);
        });
        if (start) {
            try { executor.execute(() -> run(shared)); }
            catch (RuntimeException rejected) {
                finish(shared, null, new AcquisitionException(CAPACITY_EXCEEDED, "Acquisition executor rejected work", rejected));
            }
        }
        return new AcquisitionRequest(result);
    }

    private void run(Flight flight) {
        synchronized (coordinator) { flight.worker = Thread.currentThread(); }
        VerifiedSourceAlias result = null;
        Throwable failure = null;
        try {
            flight.checkCancellation();
            VerifiedSourceAlias cached;
            synchronized (coordinator) { cached = reuseProcessAliases ? aliases.get(flight.identity) : null; }
            if (cached != null) {
                try {
                    var verified = store.verify(cached.object().digest(), flight.cancelled::get);
                    if (verified.isPresent()) {
                        if (!verified.get().equals(cached.object())) throw new AcquisitionException(INVALID_VERIFIED_OBJECT, "Stored object metadata changed during reuse");
                        result = cached;
                    } else {
                        synchronized (coordinator) { aliases.remove(flight.identity, cached); }
                    }
                } catch (RuntimeException invalidLocal) {
                    synchronized (coordinator) { aliases.remove(flight.identity, cached); }
                    throw invalidLocal;
                }
            }
            if (result == null) {
                flight.checkCancellation();
                var acquired = acquirer.acquire(flight.identity, flight.cancelled::get);
                if (acquired == null || !acquired.source().equals(flight.identity)) {
                    throw new AcquisitionException(INVALID_VERIFIED_OBJECT, "Source verifier returned no matching acquisition receipt");
                }
                var verified = store.verify(acquired.object().digest(), flight.cancelled::get);
                if (verified.isEmpty() || !verified.get().equals(acquired.object())) {
                    throw new AcquisitionException(INVALID_VERIFIED_OBJECT, "Source verifier did not return a complete verified local object");
                }
                result = acquired;
            }
            flight.checkCancellation();
        } catch (Throwable unsuccessful) {
            failure = unsuccessful;
        } finally {
            finish(flight, result, failure);
        }
        if (failure instanceof Error error) throw error;
    }

    private void detach(Flight flight, CompletableFuture<VerifiedSourceAlias> consumer) {
        synchronized (coordinator) {
            if (!flight.consumers.remove(consumer) || !flight.consumers.isEmpty()) return;
            cancelLocked(flight);
        }
    }

    private void cancelLocked(Flight flight) {
        flight.cancelled.set(true);
        inFlight.remove(flight.identity, flight);
        // Queued cancelled work remains counted in active until its actual runnable retires.
        if (flight.worker != null) flight.worker.interrupt();
    }

    private void finish(Flight flight, VerifiedSourceAlias object, Throwable failure) {
        List<CompletableFuture<VerifiedSourceAlias>> consumers;
        synchronized (coordinator) {
            if (!active.remove(flight)) return;
            inFlight.remove(flight.identity, flight);
            flight.worker = null;
            if (reuseProcessAliases && failure == null && object != null && !flight.cancelled.get() && !flight.consumers.isEmpty()) {
                aliases.put(flight.identity, object);
                if (aliases.size() > maximumAliases) aliases.remove(aliases.keySet().iterator().next());
            }
            consumers = List.copyOf(flight.consumers);
            flight.consumers.clear();
        }
        for (var consumer : consumers) {
            if (failure == null && object != null) consumer.complete(object);
            else consumer.completeExceptionally(failure == null ? new java.util.concurrent.CancellationException("Acquisition cancelled") : failure);
        }
    }

    @Override public void close() {
        List<CompletableFuture<VerifiedSourceAlias>> consumers = new ArrayList<>();
        synchronized (coordinator) {
            if (closed) return;
            closed = true;
            for (var flight : active) {
                cancelLocked(flight);
                consumers.addAll(flight.consumers);
            }
            aliases.clear();
        }
        consumers.forEach(consumer -> consumer.cancel(true));
    }

    private static AcquisitionRequest failed(AcquisitionException.Code code, String message) {
        return new AcquisitionRequest(CompletableFuture.failedFuture(new AcquisitionException(code, message)));
    }
    private static final class Flight {
        private final ResolvedSourceIdentity identity;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<CompletableFuture<VerifiedSourceAlias>> consumers = new ArrayList<>();
        private Thread worker;
        private Flight(ResolvedSourceIdentity identity) { this.identity = identity; }
        private void checkCancellation() {
            if (cancelled.get()) throw new java.util.concurrent.CancellationException("Acquisition cancelled");
        }
    }
}
