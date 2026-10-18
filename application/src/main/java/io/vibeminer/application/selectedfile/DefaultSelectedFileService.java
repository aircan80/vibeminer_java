package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.application.scheduler.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** Selected encoded-file policy. Executors and all outbound resources remain caller-owned. */
public final class DefaultSelectedFileService implements SelectedFileService {
  private final SelectedFileGateway gateway;
  private final BuildObjectCatalog builds;
  private final SelectedFileCatalog files;
  private final RetentionRepository retention;
  private final Executor jobs, acquisitions;
  private final PrioritySourceScheduler scheduler;
  private final Semaphore capacity = new Semaphore(4);
  private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
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
    final LocatedFilePlan firstPlan;
    final CanonicalReservation firstReservation;
    final CompletableFuture<ContentRepresentation> result = new CompletableFuture<>();
    final Map<ContentSlotKey, CanonicalReservation> reservations = new LinkedHashMap<>();
    final Set<Slot> heldSlots = new HashSet<>();
    final Map<ContentSlotKey, ContentWorkerPermit> permits = new LinkedHashMap<>();
    int consumers;
    volatile boolean cancelled;
    boolean accepting = true;
    boolean publicationClosing;
    Thread worker;
    final SourceDemandRegistration scheduling;
    volatile CancellationToken schedulerCancellation = CancellationToken.none();

    Flight(
        LocatedFilePlan plan,
        CanonicalReservation reservation,
        PrioritySourceScheduler scheduler,
        ResolvedSourceIdentity source) {
      scheduling = scheduler == null ? null : new SourceDemandRegistration(scheduler, source);
      firstPlan = plan;
      firstReservation = reservation;
    }
  }

  public DefaultSelectedFileService(
      SelectedFileGateway gateway,
      BuildObjectCatalog builds,
      SelectedFileCatalog files,
      RetentionRepository retention,
      Executor jobs,
      Executor acquisitions) {
    this(gateway, builds, files, retention, jobs, acquisitions, null);
  }

  public DefaultSelectedFileService(
      SelectedFileGateway gateway,
      BuildObjectCatalog builds,
      SelectedFileCatalog files,
      RetentionRepository retention,
      Executor jobs,
      Executor acquisitions,
      PrioritySourceScheduler scheduler) {
    this.scheduler = scheduler;
    this.gateway = Objects.requireNonNull(gateway);
    this.builds = Objects.requireNonNull(builds);
    this.files = Objects.requireNonNull(files);
    this.retention = Objects.requireNonNull(retention);
    this.jobs = Objects.requireNonNull(jobs);
    this.acquisitions = Objects.requireNonNull(acquisitions);
  }

  @Override
  public CompletionStage<CandidatePlan> planCandidates(
      BuildSnapshot build, FileVariantAssociation variant, SelectedFileBudgets budgets) {
    Objects.requireNonNull(build);
    Objects.requireNonNull(variant);
    Objects.requireNonNull(budgets);
    return submit(
        token -> {
          if (!variant.build().equals(build))
            throw error(INVALID_REQUEST, "Variant belongs to another exact build");
          var configuration = retained(build, "BUILD_CONFIG", token);
          var root = retained(build, "ROOT_MANIFEST", token);
          if (!root.source().equals(variant.rootSource()))
            throw error(INVALID_REQUEST, "Variant root source differs from retained root");
          var plan = gateway.prepareCandidates(build, configuration, root, variant, budgets, token);
          if (!plan.variant().equals(variant)
              || !plan.configuration().equals(configuration)
              || !plan.root().equals(root)
              || !plan.budgets().equals(budgets))
            throw error(INTEGRITY_FAILURE, "Candidate result changed exact selected context");
          gateway.validateCandidatePlan(plan, token);
          if (plan.proof().declaredDecodedBytes().isEmpty()
              || !plan.proof().authenticatedCandidates().equals(plan.candidates()))
            throw error(
                INTEGRITY_FAILURE,
                "Current candidate proof lacks authenticated EN declaration facts");
          return plan;
        });
  }

  @Override
  public CompletionStage<FileSelectionProbe> probeLoose(CandidateChoice choice) {
    Objects.requireNonNull(choice);
    return submit(
        token -> {
          validateChoice(choice, token);
          var ready = canonical(choice, token);
          if (ready.isPresent()) {
            var verified = inspectAvailable(choice, ready.get(), token);
            if (verified.isPresent())
              return new FileSelectionProbe(
                  Optional.of(reuse(choice, verified.get())), Optional.empty());
          }
          var result = gateway.probeLoose(choice, token);
          if (result.located().isPresent()) {
            var located = result.located().get();
            if (located.locator().kind() != FileLocator.Kind.LOOSE)
              throw error(INTEGRITY_FAILURE, "Loose probe cannot authorize an archive transfer");
            validateLocated(choice, located, token);
            return new FileSelectionProbe(Optional.of(acquire(located)), Optional.empty());
          }
          var permit = result.missing().orElseThrow();
          if (!permit.choice().equals(choice))
            throw error(INTEGRITY_FAILURE, "Loose observation changed selected candidate");
          gateway.validateLoose404Permit(permit, token);
          return new FileSelectionProbe(Optional.empty(), Optional.of(permit));
        });
  }

  @Override
  public CompletionStage<FileSelectionSearch> searchArchives(
      Loose404Permit permit, ArchiveSearchBudgets budgets) {
    Objects.requireNonNull(permit);
    Objects.requireNonNull(budgets);
    return submit(
        token -> {
          validateChoice(permit.choice(), token);
          gateway.validateLoose404Permit(permit, token);
          var ready = canonical(permit.choice(), token);
          if (ready.isPresent()) {
            var verified = inspectAvailable(permit.choice(), ready.get(), token);
            if (verified.isPresent())
              return new FileSelectionSearch(
                  Optional.of(reuse(permit.choice(), verified.get())), 0, 0, false);
          }
          var result = gateway.searchArchives(permit, budgets, token);
          if (result.checkedArchives() > budgets.maximumArchives())
            throw error(INTEGRITY_FAILURE, "Archive result exceeded authorized scope");
          Optional<SelectedFilePlan> plan = Optional.empty();
          if (result.located().isPresent()) {
            var located = result.located().get();
            if (located.locator().kind() != FileLocator.Kind.ARCHIVE)
              throw error(INTEGRITY_FAILURE, "Archive search returned an unrelated route");
            validateLocated(permit.choice(), located, token);
            plan = Optional.of(acquire(located));
          }
          return new FileSelectionSearch(
              plan, result.checkedArchives(), result.advertisedArchives(), result.complete());
        });
  }

  @Override
  public CompletionStage<SelectedFileAssociation> retain(SelectedFilePlan plan) {
    return retain(plan, PrioritySourceScheduler.Demand.foreground());
  }

  @Override
  public CompletionStage<SelectedFileAssociation> retain(
      SelectedFilePlan plan, PrioritySourceScheduler.Demand demand) {
    Objects.requireNonNull(plan);
    Objects.requireNonNull(demand);
    if (scheduler == null && !demand.isForeground())
      return CompletableFuture.failedFuture(
          error(UNSUPPORTED, "Priority source admission not configured"));
    return submit(
        operationToken -> {
          CancellationToken token =
              () -> operationToken.isCancellationRequested() || demand.isCancellationRequested();
          token.throwIfCancellationRequested();
          var choice = plan.choice();
          validateChoice(choice, token);
          long length;
          if (plan.acquisition().isPresent()) {
            var located = plan.acquisition().get();
            validateLocated(choice, located, token);
            if (!located.cost().equals(plan.cost()))
              throw error(INVALID_PLAN, "Payload cost consent changed");
            length = located.encodedBytes();
          } else {
            if (!plan.cost().equals(new AcquisitionCost(0, 0)))
              throw error(INVALID_PLAN, "Retained reuse cost changed");
            length = plan.reuse().orElseThrow().encoded().object().length();
            checkLength(choice, length);
          }
          var current = canonical(choice, token);
          if (plan.reuse().isPresent()
              && (current.isEmpty() || !current.get().equals(plan.reuse().get())))
            throw error(INVALID_PLAN, "Retained representation changed; plan again");
          var slot = enterSlot(choice);
          try {
            var scope = scope(choice, length);
            builds.registerSnapshot(choice.plan().variant().build());
            retention.pin(scope, token);
            var representation = retainBytes(plan, current, slot, length, demand, token);
            token.throwIfCancellationRequested();
            var expected = association(choice, representation);
            var attached = files.attachAssociation(expected, scope, token);
            if (attached == null
                || !attached.snapshotId().equals(expected.snapshotId())
                || !attached.role().equals(expected.role())
                || !attached.configuration().equals(expected.configuration())
                || !attached.root().equals(expected.root())
                || !attached.proof().equals(expected.proof())
                || !attached.representation().equals(expected.representation()))
              throw error(
                  INTEGRITY_FAILURE, "Association commit changed exact consumer provenance");
            return attached;
          } finally {
            leaveSlot(slot);
          }
        });
  }

  private ContentRepresentation retainBytes(
      SelectedFilePlan plan,
      Optional<ContentRepresentation> current,
      Slot slot,
      long length,
      PrioritySourceScheduler.Demand demand,
      CancellationToken token) {
    var choice = plan.choice();
    if (current.isPresent()) {
      var local = inspectAvailable(choice, current.get(), token);
      if (local.isPresent()) return local.get();
    }
    var located =
        plan.acquisition()
            .orElseThrow(
                () ->
                    error(
                        MISSING_LOCAL,
                        "Previously verified bytes are missing; approve a new exact-source payload"
                            + " plan"));
    var reservation = files.reserve(slot.key, slot.encodingKey, length, token);
    checkReservation(reservation, slot.key, slot.encodingKey, length);
    if (reservation.status() == CanonicalReservation.Status.RETAINED
        && reservation.representation().isPresent()) {
      var local = inspectAvailable(choice, reservation.representation().get(), token);
      if (local.isPresent()) return local.get();
    }
    FlightJoin joined;
    try {
      joined = join(slot, reservation, located, demand);
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
      // Publication froze its fanout before this consumer arrived. Its result is
      // completed only after worker retirement; adopt the durable original receipt
      // without starting another source worker or mutating the frozen fanout.
      try {
        var adopted = files.reserve(slot.key, slot.encodingKey, length, token);
        checkReservation(adopted, slot.key, slot.encodingKey, length);
        if (!adopted.token().equals(reservation.token()))
          throw error(INTEGRITY_FAILURE, "Retirement handoff changed reservation token");
        if (adopted.status() != CanonicalReservation.Status.RETAINED)
          throw error(RECOVERY_REQUIRED, "Retired publication has no canonical receipt");
        return inspect(choice, adopted.representation().orElseThrow(), token);
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

  private void scheduleUnownedReservationCleanup(
      Slot slot, CanonicalReservation reservation, RuntimeException failure) {
    synchronized (coordinator) {
      // Defer cleanup until the final orchestration consumer retires. Several
      // canceled waiters can all reach here before any one leaves its slot.
      if (slot.workers == 0) {
        slot.cleanupReservation = reservation;
        slot.cleanupFailure = failure;
      }
    }
  }

  private Optional<ContentRepresentation> inspectAvailable(
      CandidateChoice choice, ContentRepresentation value, CancellationToken token) {
    try {
      return Optional.of(inspect(choice, value, token));
    } catch (SelectedFileException unavailable) {
      if (unavailable.code() == MISSING_LOCAL) return Optional.empty();
      throw unavailable;
    }
  }

  @Override
  public CompletionStage<SelectedFileAssociation> inspectOffline(
      SelectedFileDescriptor descriptor, SelectedFileBudgets budgets) {
    Objects.requireNonNull(descriptor);
    Objects.requireNonNull(budgets);
    return submit(
        token -> inspectOfflineBlocking(descriptor, budgets, token, MISSING_LOCAL, false));
  }

  @Override
  public CompletionStage<SelectedFileAssociation> inspectOffline(
      FileVariantAssociation variant, SelectedFileBudgets budgets) {
    Objects.requireNonNull(variant);
    Objects.requireNonNull(budgets);
    return submit(
        token -> {
          var canonical =
              files
                  .lookup(variant.slot(), token)
                  .orElseThrow(
                      () ->
                          error(
                              NOT_FOUND, "No retained representation for this exact root variant"));
          if (!canonical.slot().equals(variant.slot()))
            throw error(
                INTEGRITY_FAILURE, "Canonical lookup returned another declared content slot");
          switch (canonical.status()) {
            case PENDING, PUBLISHING ->
                throw error(BUSY, "The selected content is still being retained");
            case RECOVERY ->
                throw error(
                    RECOVERY_REQUIRED,
                    "Interrupted publication must be reconciled before offline inspection");
            case RETAINED -> {}
          }
          var descriptor =
              new SelectedFileDescriptor(
                  variant,
                  canonical.actualEncodingKey(),
                  canonical
                      .representation()
                      .orElseThrow()
                      .encoded()
                      .source()
                      .verificationSemantics());
          return inspectOfflineBlocking(descriptor, budgets, token, NOT_FOUND, true);
        });
  }

  private SelectedFileAssociation inspectOfflineBlocking(
      SelectedFileDescriptor descriptor,
      SelectedFileBudgets budgets,
      CancellationToken token,
      SelectedFileException.Code absentCode,
      boolean requireCanonicalSelection) {
    var value =
        files
            .findOriginalAssociation(descriptor, token)
            .orElseThrow(() -> error(absentCode, "Selected-file association is not retained"));
    if (!value.descriptor().role().equals(descriptor.role())
        || !value.snapshotId().equals(descriptor.association().build().snapshotId()))
      throw error(INTEGRITY_FAILURE, "Stored descriptor differs from exact selection");
    var build = descriptor.association().build();
    if (!retained(build, "BUILD_CONFIG", token).equals(value.configuration())
        || !retained(build, "ROOT_MANIFEST", token).equals(value.root()))
      throw error(INTEGRITY_FAILURE, "Stored dependency provenance changed");
    if (requireCanonicalSelection) {
      var canonical =
          files
              .lookup(value.representation().slot(), token)
              .orElseThrow(() -> error(RECOVERY_REQUIRED, "Canonical representation missing"));
      if (canonical.status() != CanonicalReservation.Status.RETAINED
          || !canonical.representation().orElseThrow().equals(value.representation()))
        throw error(INTEGRITY_FAILURE, "Stored association is not the canonical representation");
    }
    if (value.representation().encoded().object().length() > budgets.maximumEncodedFileBytes())
      throw error(BUDGET_EXCEEDED, "Retained file exceeds offline read budget");
    var checked = gateway.inspectRetained(value, budgets, token);
    if (!value.representation().equals(checked))
      throw error(INTEGRITY_FAILURE, "Offline verification replaced the original receipt");
    return value;
  }

  private void validateChoice(CandidateChoice choice, CancellationToken token) {
    gateway.validateCandidatePlan(choice.plan(), token);
    if (!choice.plan().candidates().contains(choice.candidate()))
      throw error(INVALID_PLAN, "Candidate is not in the authenticated set");
    var build = choice.plan().variant().build();
    if (!retained(build, "BUILD_CONFIG", token).equals(choice.plan().configuration())
        || !retained(build, "ROOT_MANIFEST", token).equals(choice.plan().root()))
      throw error(INVALID_PLAN, "Retained dependency proof changed; plan again");
  }

  private void validateLocated(
      CandidateChoice choice, LocatedFilePlan located, CancellationToken token) {
    gateway.validateLocatedPlan(located, token);
    if (!located.choice().equals(choice))
      throw error(INVALID_PLAN, "Locator changed selected candidate");
    checkLength(choice, located.encodedBytes());
    if (!located.cost().equals(new AcquisitionCost(located.encodedBytes(), located.encodedBytes())))
      throw error(INVALID_PLAN, "Known payload cost differs from pinned encoded length");
  }

  private static void checkLength(CandidateChoice choice, long length) {
    var budgets = choice.plan().budgets();
    if (length < 1
        || choice.candidate().encodedBytes().isPresent()
            && choice.candidate().encodedBytes().getAsLong() != length)
      throw error(INTEGRITY_FAILURE, "Encoded length disagrees with authenticated candidate");
    long requests =
        length / budgets.maximumPayloadRangeBytes()
            + (length % budgets.maximumPayloadRangeBytes() == 0 ? 0 : 1);
    if (length > budgets.maximumEncodedFileBytes()
        || length > budgets.maximumPayloadWireBytes()
        || requests > budgets.maximumPayloadRequests())
      throw error(BUDGET_EXCEEDED, "Encoded file exceeds explicit payload budget");
  }

  private Optional<ContentRepresentation> canonical(
      CandidateChoice choice, CancellationToken token) {
    var state = files.lookup(choice.plan().variant().slot(), token);
    if (state.isEmpty()) return Optional.empty();
    var value = state.get();
    if (!value.slot().equals(choice.plan().variant().slot()))
      throw error(INTEGRITY_FAILURE, "Canonical lookup changed declared content slot");
    if (!value.actualEncodingKey().equals(choice.candidate().encodingKey()))
      throw error(
          value.status() == CanonicalReservation.Status.RETAINED ? REPLAN_REQUIRED : BUSY,
          "Another actual encoding already owns this declared content slot");
    checkLength(choice, value.encodedBytes());
    return value.status() == CanonicalReservation.Status.RETAINED
        ? value.representation()
        : Optional.empty();
  }

  private VerifiedSourceAlias retained(BuildSnapshot build, String role, CancellationToken token) {
    var value =
        builds
            .findVerifiedObject(build.snapshotId(), role, token)
            .orElseThrow(() -> error(MISSING_LOCAL, "Exact retained dependency missing: " + role));
    if (!value.snapshotId().equals(build.snapshotId()) || !value.role().equals(role))
      throw error(INTEGRITY_FAILURE, "Dependency context changed");
    return new VerifiedSourceAlias(value.source(), value.object(), value.evidence());
  }

  private static SelectedFilePlan acquire(LocatedFilePlan plan) {
    return new SelectedFilePlan(plan.choice(), Optional.of(plan), Optional.empty(), plan.cost());
  }

  private static SelectedFilePlan reuse(CandidateChoice choice, ContentRepresentation value) {
    return new SelectedFilePlan(
        choice, Optional.empty(), Optional.of(value), new AcquisitionCost(0, 0));
  }

  private static SelectedFileAssociation association(
      CandidateChoice choice, ContentRepresentation representation) {
    return new SelectedFileAssociation(
        new SelectedFileDescriptor(
            choice.plan().variant(),
            choice.candidate().encodingKey(),
            ContentRepresentation.VERIFIER_SEMANTICS),
        choice.plan().configuration(),
        choice.plan().root(),
        choice.plan().proof(),
        representation);
  }

  private ContentRepresentation inspect(
      CandidateChoice choice, ContentRepresentation representation, CancellationToken token) {
    var checked =
        gateway.inspectRetained(
            association(choice, representation), choice.plan().budgets(), token);
    if (!representation.equals(checked))
      throw error(INTEGRITY_FAILURE, "Reused acquisition receipt changed");
    return checked;
  }

  private static RetentionScope scope(CandidateChoice choice, long length) {
    var plan = choice.plan();
    var descriptor =
        new SelectedFileDescriptor(
            plan.variant(),
            choice.candidate().encodingKey(),
            ContentRepresentation.VERIFIER_SEMANTICS);
    return new RetentionScope(
        plan.variant().build().snapshotId(),
        ScopeKind.ENCODED_FILE,
        List.of(
            new ScopeRequirement(
                "BUILD_CONFIG",
                Optional.of(plan.configuration().source()),
                OptionalLong.of(plan.configuration().object().length()),
                OptionalLong.of(plan.configuration().object().length())),
            new ScopeRequirement(
                "ROOT_MANIFEST",
                Optional.of(plan.root().source()),
                OptionalLong.of(plan.root().object().length()),
                OptionalLong.empty()),
            new ScopeRequirement(
                descriptor.role(),
                Optional.of(
                    ContentRepresentation.encodedIdentity(choice.candidate().encodingKey())),
                OptionalLong.of(length),
                OptionalLong.empty())));
  }

  private static void checkReservation(
      CanonicalReservation reservation, ContentSlotKey slot, String key, long length) {
    if (!reservation.slot().equals(slot)
        || !reservation.actualEncodingKey().equals(key)
        || reservation.encodedBytes() != length)
      throw error(INTEGRITY_FAILURE, "Reservation changed content slot, actual encoding or size");
  }

  private Slot enterSlot(CandidateChoice choice) {
    synchronized (coordinator) {
      var key = choice.plan().variant().slot();
      var slot = slots.get(key);
      if (slot != null && slot.cleanup)
        throw error(BUSY, "Unpublished reservation cleanup is still active");
      if (slot != null && !slot.encodingKey.equals(choice.candidate().encodingKey()))
        throw error(BUSY, "Another candidate worker has not retired");
      if (slot == null) {
        slot = new Slot(key, choice.candidate().encodingKey());
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
      Slot slot,
      CanonicalReservation reservation,
      LocatedFilePlan plan,
      PrioritySourceScheduler.Demand demand) {
    Flight flight;
    boolean start = false;
    var source = ContentRepresentation.encodedIdentity(slot.encodingKey);
    synchronized (coordinator) {
      if (closed.get()) throw error(CLOSED, "Selected-file service closed");
      flight = flights.get(source);
      if (flight != null && flight.cancelled)
        throw error(BUSY, "Previous source worker has not retired");
      if (flight != null && !flight.accepting) {
        if (!flight.publicationClosing) throw error(BUSY, "Previous source worker has not retired");
        if (flight.firstPlan.encodedBytes() != plan.encodedBytes())
          throw error(INTEGRITY_FAILURE, "Shared EKey length conflict");
        // The bounded orchestration jobs already account for these waiters. They
        // own no source subscription and cannot cancel or extend frozen publication.
        return new FlightJoin(flight, false, null, flight.result);
      }
      if (flight == null) {
        if (flights.size() >= (scheduler == null ? 4 : 68))
          throw error(CAPACITY_EXCEEDED, "Source worker capacity exceeded");
        flight = new Flight(plan, reservation, scheduler, source);
        flights.put(source, flight);
        start = true;
      } else if (flight.firstPlan.encodedBytes() != plan.encodedBytes())
        throw error(INTEGRITY_FAILURE, "Shared EKey length conflict");
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
            new SelectedFileException(
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
            inspectAvailable(flight.firstPlan.choice(), refreshed.representation().get(), token);
      ContentRepresentation acquired;
      if (local.isPresent()) acquired = local.get();
      else {
        enteredGateway = true;
        acquired =
            gateway.acquireEncoded(
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
              || failure instanceof EncodedAcquisitionFailure scoped
                  && scoped.publication()
                      == EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED
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

  private static void checkRepresentation(LocatedFilePlan plan, ContentRepresentation value) {
    if (value == null)
      throw error(INTEGRITY_FAILURE, "Source verifier returned no complete representation");
    if (!value.slot().equals(plan.choice().plan().variant().slot())
        || !value
            .encoded()
            .source()
            .equals(ContentRepresentation.encodedIdentity(plan.choice().candidate().encodingKey()))
        || value.encoded().object().length() != plan.encodedBytes())
      throw error(INTEGRITY_FAILURE, "Verified representation changed selected source or size");
    var observed = value.sourceReceipt();
    var locator = plan.locator();
    if (!observed.actualResourceUrl().equals(locator.resourceUrl())
        || !observed.resourceKey().equals(locator.resourceKey())
        || observed.parentWholeBytes() != locator.resourceBytes()
        || observed.childOffset() != locator.childOffset()
        || observed.childEncodedBytes() != locator.childLength()
        || !observed.archiveMapping().equals(locator.archiveProof())
        || (observed.kind() == EncodedSourceReceipt.Kind.LOOSE)
            != (locator.kind() == FileLocator.Kind.LOOSE)
        || observed.payloadRequestCount() > plan.choice().plan().budgets().maximumPayloadRequests()
        || observed.payloadResponseBytes()
            > plan.choice().plan().budgets().maximumPayloadWireBytes())
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
      throw new CancellationException("Selected-file consumer cancelled");
    } catch (ExecutionException failure) {
      throw classify(failure.getCause());
    }
  }

  private <T> CompletionStage<T> submit(Function<CancellationToken, T> action) {
    if (closed.get())
      return CompletableFuture.failedFuture(error(CLOSED, "Selected-file service closed"));
    if (!capacity.tryAcquire())
      return CompletableFuture.failedFuture(
          error(CAPACITY_EXCEEDED, "Too many selected-file actions"));
    var result = new CompletableFuture<T>();
    var worker = new AtomicReference<Thread>();
    active.add(result);
    result.whenComplete(
        (value, failure) -> {
          if (result.isCancelled()) {
            var thread = worker.get();
            if (thread != null && thread != Thread.currentThread()) thread.interrupt();
          }
        });
    try {
      jobs.execute(
          () -> {
            worker.set(Thread.currentThread());
            CancellationToken token =
                () ->
                    result.isCancelled() || closed.get() || Thread.currentThread().isInterrupted();
            try {
              token.throwIfCancellationRequested();
              result.complete(action.apply(token));
            } catch (Throwable failure) {
              result.completeExceptionally(classify(failure));
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
          new SelectedFileException(CAPACITY_EXCEEDED, "Selected-file job rejected", rejected));
    }
    if (closed.get()) result.cancel(true);
    return result;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    active.forEach(future -> future.cancel(true));
    synchronized (coordinator) {
      for (var flight : flights.values()) {
        flight.cancelled = true;
        if (flight.worker != null) flight.worker.interrupt();
      }
    }
  }

  private static RuntimeException classify(Throwable problem) {
    while ((problem instanceof ExecutionException || problem instanceof CompletionException)
        && problem.getCause() != null) problem = problem.getCause();
    if (problem instanceof SelectedFileException scoped) return scoped;
    if (problem instanceof PrioritySourceException scheduling)
      return switch (scheduling.code()) {
        case BULK_DISABLED -> new CancellationException("Bulk generation disabled");
        case BUSY -> error(BUSY, scheduling.getMessage());
        case CLOSED -> error(CLOSED, scheduling.getMessage());
        case INVALID_REQUEST -> error(INVALID_REQUEST, scheduling.getMessage());
        case CAPACITY_EXCEEDED -> error(CAPACITY_EXCEEDED, scheduling.getMessage());
      };
    if (problem instanceof EncodedAcquisitionFailure scoped) return scoped.failure();
    if (problem instanceof CancellationException cancelled) return cancelled;
    if (problem instanceof RetentionException retention)
      return new SelectedFileException(
          switch (retention.code()) {
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
            case NOT_FOUND -> NOT_FOUND;
            case CLOSED -> CLOSED;
            case CAPACITY_EXCEEDED -> CAPACITY_EXCEEDED;
            case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
          },
          retention.getMessage(),
          retention);
    if (problem instanceof CatalogException catalog)
      return new SelectedFileException(
          catalog.code() == CatalogException.Code.INTEGRITY_FAILURE
                  || catalog.code() == CatalogException.Code.CORRUPT_METADATA
              ? INTEGRITY_FAILURE
              : STORAGE_UNAVAILABLE,
          catalog.getMessage(),
          catalog);
    return new SelectedFileException(STORAGE_UNAVAILABLE, "Selected-file action failed", problem);
  }

  private static SelectedFileException error(SelectedFileException.Code code, String message) {
    return new SelectedFileException(code, message);
  }
}
