package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.encodedsource.*;
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
  private final Executor jobs;
  private final EncodedSourceAcquisition sourceAcquisition;
  private final boolean ownsSourceAcquisition;
  private final PrioritySourceScheduler scheduler;
  private final Semaphore capacity = new Semaphore(4);
  private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

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
    this(gateway, builds, files, retention, jobs, acquisitions, scheduler, null);
  }

  /** The shared encoded core is borrowed; only the runtime may close it. */
  public DefaultSelectedFileService(
      SelectedFileGateway gateway,
      BuildObjectCatalog builds,
      SelectedFileCatalog files,
      RetentionRepository retention,
      Executor jobs,
      Executor acquisitions,
      PrioritySourceScheduler scheduler,
      EncodedSourceAcquisition sharedCore) {
    this.scheduler = scheduler;
    this.ownsSourceAcquisition = sharedCore == null;
    this.sourceAcquisition =
        sharedCore == null
            ? new EncodedSourceAcquisition(files, acquisitions, scheduler)
            : sharedCore;
    this.gateway = Objects.requireNonNull(gateway);
    this.builds = Objects.requireNonNull(builds);
    this.files = Objects.requireNonNull(files);
    this.retention = Objects.requireNonNull(retention);
    this.jobs = Objects.requireNonNull(jobs);
    Objects.requireNonNull(acquisitions);
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
          try (var consumer =
              sourceAcquisition.enter(
                  choice.plan().variant().slot(), choice.candidate().encodingKey())) {
            var scope = scope(choice, length);
            builds.registerSnapshot(choice.plan().variant().build());
            retention.pin(scope, token);
            var representation = retainBytes(plan, current, consumer, demand, token);
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
          }
        });
  }

  private ContentRepresentation retainBytes(
      SelectedFilePlan plan,
      Optional<ContentRepresentation> current,
      EncodedSourceAcquisition.ConsumerLease consumer,
      PrioritySourceScheduler.Demand demand,
      CancellationToken token) {
    if (current.isPresent()) {
      var local = inspectAvailable(plan.choice(), current.get(), token);
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
    var authorized = gateway.authorizeEncodedSource(located, token);
    return sourceAcquisition.acquire(
        consumer, authorized.plan(), authorized.gateway(), demand, token);
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
    if (ownsSourceAcquisition) sourceAcquisition.close();
  }

  private static RuntimeException classify(Throwable problem) {
    while ((problem instanceof ExecutionException || problem instanceof CompletionException)
        && problem.getCause() != null) problem = problem.getCause();
    if (problem instanceof SelectedFileException scoped) return scoped;
    if (problem instanceof EncodedSourceException source)
      return new SelectedFileException(
          SelectedFileException.Code.valueOf(source.code().name()), source.getMessage(), source);
    if (problem instanceof EncodedSourceGateway.AcquisitionFailure source)
      return classify(source.failure());
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
