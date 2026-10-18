package io.vibeminer.application.encodedsource;

import static io.vibeminer.application.encodedsource.EncodedSourceException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.scheduler.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One encoded source-flight/canonical publisher core shared by independent consumer facades. */
public final class EncodedSourceAcquisition implements AutoCloseable {
  private final EncodedSourceCatalog files;
  private final Executor acquisitions;
  private final PrioritySourceScheduler scheduler;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object coordinator = new Object();
  private final Map<ContentSlotKey, Slot> slots = new HashMap<>();
  private final Map<ResolvedSourceIdentity, Flight> flights = new HashMap<>();

  private static final class Slot {
    final ContentSlotKey key;
    final String encodingKey;
    int consumers, workers;
    boolean cleanup;
    CanonicalReservation cleanupReservation;
    RuntimeException cleanupFailure;

    Slot(ContentSlotKey key, String encodingKey) {
      this.key = key;
      this.encodingKey = encodingKey;
    }
  }

  private static final class Flight {
    final EncodedSourcePlan firstPlan;
    final EncodedSourceGateway gateway;
    final CanonicalReservation firstReservation;
    final CompletableFuture<ContentRepresentation> result = new CompletableFuture<>();
    final Map<ContentSlotKey, CanonicalReservation> reservations = new LinkedHashMap<>();
    final Set<Slot> heldSlots = new HashSet<>();
    final Map<ContentSlotKey, ContentWorkerPermit> permits = new LinkedHashMap<>();
    int consumers;
    int enrollments;
    volatile boolean cancelled;
    boolean accepting = true;
    boolean publicationClosing;
    Thread worker;
    final SourceDemandRegistration scheduling;
    volatile CancellationToken schedulerCancellation = CancellationToken.none();

    Flight(
        EncodedSourcePlan plan,
        EncodedSourceGateway gateway,
        CanonicalReservation reservation,
        PrioritySourceScheduler scheduler,
        ResolvedSourceIdentity source) {
      scheduling = scheduler == null ? null : new SourceDemandRegistration(scheduler, source);
      firstPlan = plan;
      this.gateway = gateway;
      firstReservation = reservation;
    }
  }

  public EncodedSourceAcquisition(
      EncodedSourceCatalog files, Executor acquisitions, PrioritySourceScheduler scheduler) {
    this.files = Objects.requireNonNull(files);
    this.acquisitions = Objects.requireNonNull(acquisitions);
    this.scheduler = scheduler;
  }

  /** Local consumer lifetime only; this lease grants no transfer or membership authority. */
  public final class ConsumerLease implements AutoCloseable {
    private final Slot slot;
    private boolean retired;
    private boolean sourceBound;
    private boolean acquisitionStarted;
    private final CompletableFuture<Void> sourceRetirement = new CompletableFuture<>();

    /** Actual joined source retirement; closing this consumer never retires a running source. */
    public CompletionStage<Void> retirement() {
      return sourceRetirement.minimalCompletionStage();
    }

    private ConsumerLease(Slot slot) {
      this.slot = slot;
    }

    private EncodedSourceAcquisition owner() {
      return EncodedSourceAcquisition.this;
    }

    @Override
    public void close() {
      synchronized (coordinator) {
        if (retired) return;
        retired = true;
      }
      leaveSlot(slot);
      if (!sourceBound) sourceRetirement.complete(null);
    }
  }

  public ConsumerLease enter(ContentSlotKey slot, String encodingKey) {
    Objects.requireNonNull(slot);
    if (encodingKey == null || !encodingKey.matches("[0-9a-f]{32}"))
      throw error(INVALID_REQUEST, "Full actual encoding key required");
    if (closed.get()) throw error(CLOSED, "Encoded source acquisition closed");
    return new ConsumerLease(enterSlot(slot, encodingKey));
  }

  public ContentRepresentation acquire(
      ConsumerLease lease,
      EncodedSourcePlan plan,
      EncodedSourceGateway gateway,
      PrioritySourceScheduler.Demand demand,
      CancellationToken consumerCancellation) {
    Objects.requireNonNull(lease);
    Objects.requireNonNull(plan);
    Objects.requireNonNull(gateway);
    Objects.requireNonNull(demand);
    Objects.requireNonNull(consumerCancellation);
    CancellationToken token =
        () -> consumerCancellation.isCancellationRequested() || demand.isCancellationRequested();
    token.throwIfCancellationRequested();
    synchronized (coordinator) {
      if (lease.owner() != this
          || lease.retired
          || lease.acquisitionStarted
          || !lease.slot.key.equals(plan.slot())
          || !lease.slot.encodingKey.equals(plan.encodingKey()))
        throw error(INVALID_REQUEST, "Actual source differs from its live consumer lease");
      if (closed.get()) throw error(CLOSED, "Encoded source acquisition closed");
      lease.acquisitionStarted = true;
    }
    if (scheduler == null && !demand.isForeground())
      throw error(UNSUPPORTED, "Priority source admission is not configured");
    gateway.validate(plan, token);
    var slot = lease.slot;
    var length = plan.encodedBytes();
    var current = files.lookup(slot.key, token);
    if (current.isPresent()) {
      checkReservation(current.get(), slot.key, slot.encodingKey, length);
      if (current.get().status() == CanonicalReservation.Status.RETAINED) {
        var local =
            inspectAvailable(plan, gateway, current.get().representation().orElseThrow(), token);
        if (local.isPresent()) {
          lease.sourceRetirement.complete(null);
          return local.get();
        }
      }
    }
    var reservation = files.reserve(slot.key, slot.encodingKey, length, token);
    checkReservation(reservation, slot.key, slot.encodingKey, length);
    if (reservation.status() == CanonicalReservation.Status.RETAINED
        && reservation.representation().isPresent()) {
      var local = inspectAvailable(plan, gateway, reservation.representation().get(), token);
      if (local.isPresent()) {
        lease.sourceRetirement.complete(null);
        return local.get();
      }
    }
    FlightJoin joined;
    try {
      joined = join(lease, reservation, plan, gateway, demand);
    } catch (RuntimeException rejected) {
      scheduleUnownedReservationCleanup(slot, reservation, rejected);
      throw rejected;
    }
    try {
      await(joined.result(), token);
    } catch (RuntimeException failure) {
      if (!joined.enrolled()) scheduleUnownedReservationCleanup(slot, reservation, failure);
      throw failure;
    } finally {
      if (joined.scheduling() != null) joined.scheduling().close();
      if (joined.enrolled()) detach(joined.flight());
    }
    if (!joined.enrolled()) {
      try {
        var adopted = files.reserve(slot.key, slot.encodingKey, length, token);
        checkReservation(adopted, slot.key, slot.encodingKey, length);
        if (!adopted.token().equals(reservation.token()))
          throw error(INTEGRITY_FAILURE, "Retirement handoff changed reservation token");
        if (adopted.status() != CanonicalReservation.Status.RETAINED)
          throw error(RECOVERY_REQUIRED, "Retired publication has no canonical receipt");
        return inspect(plan, gateway, adopted.representation().orElseThrow(), token);
      } catch (RuntimeException failure) {
        scheduleUnownedReservationCleanup(slot, reservation, failure);
        throw failure;
      }
    }
    var completed =
        files
            .lookup(slot.key, token)
            .orElseThrow(() -> error(RECOVERY_REQUIRED, "Canonical publication missing"));
    checkReservation(completed, slot.key, slot.encodingKey, length);
    if (completed.status() != CanonicalReservation.Status.RETAINED)
      throw error(RECOVERY_REQUIRED, "Publication has not been reconciled");
    return completed.representation().orElseThrow();
  }

  private ContentRepresentation inspect(
      EncodedSourcePlan plan,
      EncodedSourceGateway gateway,
      ContentRepresentation value,
      CancellationToken token) {
    gateway.validate(plan, token);
    var checked = gateway.inspectAvailable(plan, value, token);
    if (!value.equals(checked))
      throw error(INTEGRITY_FAILURE, "Encoded source reuse changed receipt");
    return checked;
  }

  private Optional<ContentRepresentation> inspectAvailable(
      EncodedSourcePlan plan,
      EncodedSourceGateway gateway,
      ContentRepresentation value,
      CancellationToken token) {
    try {
      return Optional.of(inspect(plan, gateway, value, token));
    } catch (EncodedSourceException missing) {
      if (missing.code() == MISSING_LOCAL) return Optional.empty();
      throw missing;
    }
  }

  private void scheduleUnownedReservationCleanup(
      Slot slot, CanonicalReservation reservation, RuntimeException failure) {
    synchronized (coordinator) {
      if (slot.workers == 0) {
        slot.cleanupReservation = reservation;
        slot.cleanupFailure = failure;
      }
    }
  }

  private static void checkReservation(
      CanonicalReservation reservation, ContentSlotKey slot, String key, long length) {
    if (!reservation.slot().equals(slot)
        || !reservation.actualEncodingKey().equals(key)
        || reservation.encodedBytes() != length)
      throw error(INTEGRITY_FAILURE, "Reservation changed content slot, actual encoding or size");
  }

  private Slot enterSlot(ContentSlotKey key, String encodingKey) {
    synchronized (coordinator) {
      if (closed.get()) throw error(CLOSED, "Encoded source acquisition closed");
      if (slots.values().stream().mapToInt(value -> value.consumers).sum() >= 256)
        throw error(CAPACITY_EXCEEDED, "Encoded source consumer capacity exceeded");
      var slot = slots.get(key);
      if (slot != null && slot.consumers >= 32)
        throw error(CAPACITY_EXCEEDED, "Content slot consumer capacity exceeded");
      if (slot != null && slot.cleanup)
        throw error(BUSY, "Unpublished reservation cleanup is still active");
      if (slot != null && !slot.encodingKey.equals(encodingKey))
        throw error(BUSY, "Another candidate worker has not retired");
      if (slot == null) {
        slot = new Slot(key, encodingKey);
        slots.put(key, slot);
      }
      slot.consumers++;
      return slot;
    }
  }

  private void leaveSlot(Slot slot) {
    CanonicalReservation cleanupReservation;
    RuntimeException cleanupFailure;
    synchronized (coordinator) {
      slot.consumers--;
      if (slot.consumers != 0 || slot.workers != 0 || slot.cleanupReservation == null) {
        removeSlot(slot);
        return;
      }
      slot.cleanup = true;
      cleanupReservation = slot.cleanupReservation;
      cleanupFailure = slot.cleanupFailure;
    }
    try {
      // The repository refuses prospective, retained or recovery proof. Actual
      // source workers are independently protected by their slot worker holds.
      var permit = files.claimWorker(cleanupReservation.token(), CancellationToken.none());
      files.finishWorker(
          permit, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, CancellationToken.none());
    } catch (RuntimeException failure) {
      cleanupFailure.addSuppressed(failure);
    } finally {
      synchronized (coordinator) {
        slot.cleanup = false;
        removeSlot(slot);
      }
    }
  }

  private void removeSlot(Slot slot) {
    if (slot.consumers == 0 && slot.workers == 0) slots.remove(slot.key, slot);
  }

  private record FlightJoin(
      Flight flight,
      boolean enrolled,
      SourceDemandRegistration.Subscription scheduling,
      CompletableFuture<ContentRepresentation> result) {}

  private FlightJoin join(
      ConsumerLease lease,
      CanonicalReservation reservation,
      EncodedSourcePlan plan,
      EncodedSourceGateway gateway,
      PrioritySourceScheduler.Demand demand) {
    var slot = lease.slot;
    Flight flight;
    boolean start = false;
    var source = ContentRepresentation.encodedIdentity(slot.encodingKey);
    synchronized (coordinator) {
      if (closed.get()) throw error(CLOSED, "Encoded source acquisition closed");
      if (lease.retired) throw error(INVALID_REQUEST, "Consumer lease has retired");
      flight = flights.get(source);
      if (flight != null && flight.cancelled)
        throw error(BUSY, "Previous source worker has not retired");
      if (flight != null && flight.enrollments >= 32)
        throw error(CAPACITY_EXCEEDED, "Shared source enrollment capacity exceeded");
      if (flight != null && !flight.accepting) {
        if (!flight.publicationClosing) throw error(BUSY, "Previous source worker has not retired");
        if (flight.firstPlan.encodedBytes() != plan.encodedBytes())
          throw error(INTEGRITY_FAILURE, "Shared EKey length conflict");
        // The bounded orchestration jobs already account for these waiters. They
        // own no source subscription and cannot cancel or extend frozen publication.
        flight.enrollments++;
        bindRetirement(lease, flight);
        return new FlightJoin(flight, false, null, flight.result);
      }
      if (flight == null) {
        if (flights.size() >= (scheduler == null ? 4 : 68))
          throw error(CAPACITY_EXCEEDED, "Source worker capacity exceeded");
        flight = new Flight(plan, gateway, reservation, scheduler, source);
        flights.put(source, flight);
        start = true;
      } else if (flight.firstPlan.encodedBytes() != plan.encodedBytes())
        throw error(INTEGRITY_FAILURE, "Shared EKey length conflict");
      if (flight.enrollments >= 32)
        throw error(CAPACITY_EXCEEDED, "Shared source enrollment capacity exceeded");
      var existingReservation = flight.reservations.get(slot.key);
      if (existingReservation != null && !existingReservation.token().equals(reservation.token()))
        throw error(INTEGRITY_FAILURE, "Shared content slot changed its reservation token");
      if (!flight.reservations.containsKey(slot.key) && flight.reservations.size() >= 32)
        throw error(CAPACITY_EXCEEDED, "Shared source has reached its bounded content-slot fanout");
      if (!start && existingReservation == null) {
        var permit = files.claimWorker(reservation.token(), CancellationToken.none());
        flight.permits.put(slot.key, permit);
      }
      flight.consumers++;
      flight.enrollments++;
      bindRetirement(lease, flight);
      flight.reservations.putIfAbsent(slot.key, reservation);
      if (flight.heldSlots.add(slot)) slot.workers++;
    }
    var shared = flight;
    var consumerResult = new CompletableFuture<ContentRepresentation>();
    shared.result.whenComplete(
        (value, failure) -> {
          if (failure == null) consumerResult.complete(value);
          else consumerResult.completeExceptionally(failure);
        });
    SourceDemandRegistration.Subscription subscription =
        shared.scheduling == null
            ? null
            : shared.scheduling.register(demand, consumerResult::completeExceptionally);
    if (start && shared.scheduling != null) {
      shared.scheduling.start(
          cancellation -> {
            shared.schedulerCancellation = cancellation;
            runSource(source, shared);
          },
          rejected -> retireUnstarted(source, shared, rejected));
    } else if (start) {
      try {
        acquisitions.execute(() -> runSource(source, shared));
      } catch (RuntimeException rejected) {
        RuntimeException failure =
            new EncodedSourceException(
                CAPACITY_EXCEEDED, "Encoded source executor rejected work", rejected);
        try {
          claimFirst(shared);
        } catch (RuntimeException denied) {
          failure.addSuppressed(denied);
        }
        finish(source, shared, null, failure, true);
      }
    }
    return new FlightJoin(shared, true, subscription, consumerResult);
  }

  private void bindRetirement(ConsumerLease lease, Flight flight) {
    if (lease.sourceBound) throw error(INVALID_REQUEST, "Consumer already joined a source");
    lease.sourceBound = true;
    flight.result.whenComplete((value, failure) -> lease.sourceRetirement.complete(null));
  }

  private void retireUnstarted(ResolvedSourceIdentity source, Flight shared, Throwable rejected) {
    RuntimeException failure = classify(rejected);
    try {
      claimFirst(shared);
    } catch (RuntimeException denied) {
      failure.addSuppressed(denied);
    }
    finish(source, shared, null, failure, true);
  }

  private void detach(Flight flight) {
    synchronized (coordinator) {
      if (--flight.consumers == 0 && !flight.result.isDone()) {
        flight.cancelled = true;
        if (flight.worker != null) flight.worker.interrupt();
      }
    }
  }

  private ContentWorkerPermit claimFirst(Flight flight) {
    synchronized (coordinator) {
      var slot = flight.firstReservation.slot();
      if (!flight.permits.containsKey(slot))
        flight.permits.put(
            slot, files.claimWorker(flight.firstReservation.token(), CancellationToken.none()));
      return flight.permits.get(slot);
    }
  }

  private void freezeAndRecord(
      Flight flight, ContentRepresentation prospective, CancellationToken token) {
    List<Map.Entry<ContentSlotKey, ContentWorkerPermit>> owned;
    synchronized (coordinator) {
      flight.accepting = false;
      flight.publicationClosing = true;
      owned = List.copyOf(flight.permits.entrySet());
    }
    for (var entry : owned) {
      var rebound =
          new ContentRepresentation(
              entry.getKey(),
              prospective.encoded(),
              prospective.scope(),
              prospective.sourceReceipt());
      files.markPublishing(entry.getValue(), token);
      files.recordVerifiedCompletion(entry.getValue(), rebound, token);
    }
  }

  private void runSource(ResolvedSourceIdentity source, Flight flight) {
    synchronized (coordinator) {
      flight.worker = Thread.currentThread();
    }
    CancellationToken token =
        () ->
            flight.cancelled
                || closed.get()
                || Thread.currentThread().isInterrupted()
                || flight.schedulerCancellation.isCancellationRequested();
    ContentRepresentation ready = null;
    Throwable failure = null;
    boolean enteredGateway = false;
    var proof = new AtomicReference<ContentRepresentation>();
    try {
      var firstPermit = claimFirst(flight);
      token.throwIfCancellationRequested();
      // A consumer may have reserved while an older flight was publishing, then joined
      // only after that flight retired. Re-read the durable actual EKey under this new
      // source-worker ownership before deciding that any payload is still missing.
      var refreshed =
          files.reserve(
              flight.firstReservation.slot(),
              flight.firstReservation.actualEncodingKey(),
              flight.firstReservation.encodedBytes(),
              token);
      checkReservation(
          refreshed,
          flight.firstReservation.slot(),
          flight.firstReservation.actualEncodingKey(),
          flight.firstReservation.encodedBytes());
      if (!refreshed.token().equals(flight.firstReservation.token()))
        throw error(INTEGRITY_FAILURE, "Source worker reservation token changed");
      files.markPublishing(firstPermit, token);
      Optional<ContentRepresentation> local = Optional.empty();
      if (refreshed.representation().isPresent())
        local =
            inspectAvailable(
                flight.firstPlan, flight.gateway, refreshed.representation().get(), token);
      ContentRepresentation acquired;
      if (local.isPresent()) acquired = local.get();
      else {
        enteredGateway = true;
        acquired =
            flight.gateway.acquire(
                flight.firstPlan,
                prospective -> {
                  checkRepresentation(flight.firstPlan, prospective);
                  if (!proof.compareAndSet(null, prospective))
                    throw error(INTEGRITY_FAILURE, "Completion gate invoked twice");
                  freezeAndRecord(flight, prospective, token);
                },
                token);
        checkRepresentation(flight.firstPlan, acquired);
        if (proof.get() == null || !proof.get().equals(acquired))
          throw error(INTEGRITY_FAILURE, "Publication lacks its exact verified completion receipt");
      }
      if (local.isPresent()) freezeAndRecord(flight, acquired, token);
      List<CanonicalReservation> reservations;
      synchronized (coordinator) {
        reservations = List.copyOf(flight.reservations.values());
      }
      ready = files.completePublication(firstPermit, acquired, CancellationToken.none());
      checkSameEncoded(acquired, ready);
      if (!ready.slot().equals(flight.firstReservation.slot()))
        throw error(INTEGRITY_FAILURE, "Canonical completion changed the content slot");
      for (var reservation : reservations) {
        if (reservation.token().equals(flight.firstReservation.token())) continue;
        var rebound =
            new ContentRepresentation(
                reservation.slot(), ready.encoded(), ready.scope(), ready.sourceReceipt());
        var committed =
            files.completePublication(
                flight.permits.get(reservation.slot()), rebound, CancellationToken.none());
        checkSameEncoded(ready, committed);
        if (!committed.slot().equals(reservation.slot()))
          throw error(INTEGRITY_FAILURE, "Shared completion changed its rebound content slot");
      }
    } catch (Throwable problem) {
      failure = problem;
    } finally {
      boolean unpublished =
          !enteredGateway
              || failure instanceof EncodedSourceGateway.AcquisitionFailure scoped
                  && scoped.publication()
                      == EncodedSourceGateway.AcquisitionFailure.Publication
                          .CONCLUSIVELY_UNPUBLISHED
                  && proof.get() == null;
      finish(source, flight, ready, failure, unpublished);
    }
  }

  private void finish(
      ResolvedSourceIdentity source,
      Flight flight,
      ContentRepresentation ready,
      Throwable failure,
      boolean unpublished) {
    List<CanonicalReservation> reservations;
    synchronized (coordinator) {
      flight.accepting = false;
      flight.publicationClosing = failure == null && flight.publicationClosing;
      reservations = List.copyOf(flight.reservations.values());
    }
    var outcome =
        failure == null
            ? ContentWorkerFinishOutcome.RETAINED
            : unpublished
                ? ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED
                : ContentWorkerFinishOutcome.UNCERTAIN;
    for (var permit : List.copyOf(flight.permits.values())) {
      try {
        files.finishWorker(permit, outcome, CancellationToken.none());
      } catch (RuntimeException cleanup) {
        if (failure == null) failure = cleanup;
        else failure.addSuppressed(cleanup);
      }
    }
    var finalFailure = failure;
    Runnable complete =
        () -> {
          synchronized (coordinator) {
            flights.remove(source, flight);
            flight.worker = null;
            for (var slot : flight.heldSlots) {
              slot.workers--;
              removeSlot(slot);
            }
          }
          if (flight.scheduling != null) flight.scheduling.close();
          if (finalFailure == null) flight.result.complete(ready);
          else flight.result.completeExceptionally(classify(finalFailure));
        };
    if (flight.scheduling == null) complete.run();
    else flight.scheduling.retirement().whenComplete((ignored, retiredFailure) -> complete.run());
  }

  private static void checkRepresentation(EncodedSourcePlan plan, ContentRepresentation value) {
    if (value == null)
      throw error(INTEGRITY_FAILURE, "Source verifier returned no complete representation");
    if (!value.slot().equals(plan.slot())
        || !value
            .encoded()
            .source()
            .equals(ContentRepresentation.encodedIdentity(plan.encodingKey()))
        || value.encoded().object().length() != plan.encodedBytes())
      throw error(INTEGRITY_FAILURE, "Verified representation changed selected source or size");
    var observed = value.sourceReceipt();
    var locator = plan.location();
    if (!observed.actualResourceUrl().equals(locator.resourceUrl())
        || !observed.resourceKey().equals(locator.resourceKey())
        || observed.parentWholeBytes() != locator.resourceBytes()
        || observed.childOffset() != locator.childOffset()
        || observed.childEncodedBytes() != locator.childLength()
        || !observed.archiveMapping().equals(locator.archiveProof())
        || (observed.kind() == EncodedSourceReceipt.Kind.LOOSE)
            != (locator.kind() == EncodedSourcePlan.LocationKind.LOOSE)
        || observed.payloadRequestCount() > plan.limits().maximumRequests()
        || observed.payloadResponseBytes() > plan.cost().maximumPayloadWireBytes())
      throw error(
          INTEGRITY_FAILURE,
          "Actual publication receipt disagrees with approved locator or budget");
  }

  private static void checkSameEncoded(
      ContentRepresentation expected, ContentRepresentation actual) {
    if (actual == null) throw error(INTEGRITY_FAILURE, "Canonical publication returned no receipt");
    if (!expected.encoded().source().equals(actual.encoded().source())
        || !expected.encoded().object().equals(actual.encoded().object())
        || expected.scope() != actual.scope())
      throw error(INTEGRITY_FAILURE, "Canonical publication changed encoded identity");
  }

  private static <T> T await(CompletableFuture<T> work, CancellationToken token) {
    try {
      while (true) {
        token.throwIfCancellationRequested();
        try {
          return work.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
        }
      }
    } catch (InterruptedException cancelled) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Encoded source consumer cancelled");
    } catch (ExecutionException failure) {
      throw classify(failure.getCause());
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    synchronized (coordinator) {
      for (var flight : flights.values()) {
        flight.cancelled = true;
        if (flight.worker != null) flight.worker.interrupt();
      }
    }
  }

  private static RuntimeException classify(Throwable failure) {
    while ((failure instanceof ExecutionException || failure instanceof CompletionException)
        && failure.getCause() != null) failure = failure.getCause();
    if (failure instanceof EncodedSourceGateway.AcquisitionFailure scoped) return scoped.failure();
    if (failure instanceof PrioritySourceException scheduling) {
      if (scheduling.code() == PrioritySourceException.Code.BULK_DISABLED)
        return new CancellationException("Bulk generation disabled");
      return error(
          switch (scheduling.code()) {
            case BUSY -> BUSY;
            case CLOSED -> CLOSED;
            case CAPACITY_EXCEEDED -> CAPACITY_EXCEEDED;
            case INVALID_REQUEST -> INVALID_REQUEST;
            case BULK_DISABLED -> throw new IllegalStateException("Handled cancellation");
          },
          scheduling.getMessage());
    }
    if (failure instanceof RuntimeException runtime) return runtime;
    return new EncodedSourceException(STORAGE_UNAVAILABLE, "Encoded source action failed", failure);
  }

  private static EncodedSourceException error(EncodedSourceException.Code code, String message) {
    return new EncodedSourceException(code, message);
  }
}
