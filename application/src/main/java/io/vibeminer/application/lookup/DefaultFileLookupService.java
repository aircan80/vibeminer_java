package io.vibeminer.application.lookup;

import static io.vibeminer.application.lookup.FileLookupException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.acquisition.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.retention.RetentionException;
import io.vibeminer.application.retention.RetentionRepository;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Exact-root policy. Source mechanics stay behind the gateway; physical verification stays in
 * CAS/catalog.
 */
public final class DefaultFileLookupService implements FileLookupService {
  public static final String CONFIGURATION_ROLE = "BUILD_CONFIG", ROOT_ROLE = "ROOT_MANIFEST";
  private final FileLookupGateway gateway;
  private final Optional<RetentionRepository> retention;
  private final Optional<MetadataContentAcquisition> content;
  private final BuildObjectCatalog catalog;
  private final Executor jobs;
  private final CoalescingAcquisitionService acquisitions;
  private final Map<ResolvedSourceIdentity, SourceContext> sources = new HashMap<>();
  private final Map<String, CandidateContext> activeCandidates = new HashMap<>();

  private static final class CandidateContext {
    final ResolvedSourceIdentity identity;
    int consumers;

    CandidateContext(ResolvedSourceIdentity identity) {
      this.identity = identity;
    }
  }

  private CandidateContext enterCandidate(String snapshotId, ResolvedSourceIdentity identity) {
    synchronized (activeCandidates) {
      var current = activeCandidates.get(snapshotId);
      if (current != null && !current.identity.equals(identity))
        throw failure(
            CAPACITY_EXCEEDED,
            "Another root candidate is still active for this exact build; retry after it finishes");
      if (current == null) {
        current = new CandidateContext(identity);
        activeCandidates.put(snapshotId, current);
      }
      current.consumers++;
      return current;
    }
  }

  private void leaveCandidate(String snapshotId, CandidateContext context) {
    synchronized (activeCandidates) {
      if (--context.consumers == 0) activeCandidates.remove(snapshotId, context);
    }
  }

  private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
  private final Semaphore capacity = new Semaphore(4);
  private final AtomicBoolean closed = new AtomicBoolean();

  private static final class SourceContext {
    final RootSelectionPlan selection;
    int consumers;

    SourceContext(RootSelectionPlan selection) {
      this.selection = selection;
    }
  }

  /**
   * Distinct worker pools avoid workers blocking on their own queued transfers. Executors, gateway,
   * catalog and store remain caller-owned. Stop workers before closing storage.
   */
  public DefaultFileLookupService(
      FileLookupGateway gateway,
      BuildObjectCatalog catalog,
      ObjectStore store,
      Executor jobs,
      Executor acquisitionExecutor) {
    this(gateway, catalog, store, jobs, acquisitionExecutor, null);
  }

  /** Optional retention integration; legacy callers retain their existing acquisition behavior. */
  public DefaultFileLookupService(
      FileLookupGateway gateway,
      BuildObjectCatalog catalog,
      ObjectStore store,
      Executor jobs,
      Executor acquisitionExecutor,
      RetentionRepository retention) {
    this(gateway, catalog, store, jobs, acquisitionExecutor, retention, null);
  }

  /** Opt-in guard; ContentClaimException preserves canonical contention/recovery outcomes. Production composition is separate. */
  public DefaultFileLookupService(
      FileLookupGateway gateway,
      BuildObjectCatalog catalog,
      ObjectStore store,
      Executor jobs,
      Executor acquisitionExecutor,
      RetentionRepository retention,
      CanonicalContentRepository content) {
    this.content = Optional.ofNullable(content).map(MetadataContentAcquisition::new);
    this.retention = Optional.ofNullable(retention);
    this.gateway = Objects.requireNonNull(gateway);
    this.catalog = Objects.requireNonNull(catalog);
    this.jobs = Objects.requireNonNull(jobs);
    this.acquisitions =
        new CoalescingAcquisitionService(
            Objects.requireNonNull(store),
            this::acquire,
            Objects.requireNonNull(acquisitionExecutor),
            4,
            256,
            1024,
            content == null);
  }

  @Override
  public CompletionStage<RootPlan> planRoot(BuildSnapshot build, LookupBudgets budgets) {
    Objects.requireNonNull(build);
    Objects.requireNonNull(budgets);
    return submit(
        token -> {
          var configuration =
              catalog.findVerifiedSourceAlias(gateway.configurationIdentity(build), token);
          var plan = gateway.prepareRoot(build, configuration, budgets, token);
          if (!plan.build().equals(build)
              || !plan.budgets().equals(budgets)
              || !plan.configuration().source().equals(gateway.configurationIdentity(build))
              || plan.configuration().object().length() > budgets.maximumConfigurationBytes())
            throw failure(
                INTEGRITY_FAILURE, "Root proposal does not match exact build/configuration/budget");
          if (configuration.isPresent() && !configuration.get().equals(plan.configuration()))
            throw failure(INTEGRITY_FAILURE, "Root proposal replaced retained configuration proof");
          token.throwIfCancellationRequested();
          catalog.registerSnapshot(build);
          attach(build, CONFIGURATION_ROLE, plan.configuration(), token);
          return plan;
        });
  }

  @Override
  public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan selection) {
    Objects.requireNonNull(selection);
    return submit(
        token -> {
          var plan = selection.plan();
          var configuration =
              catalog
                  .findVerifiedSourceAlias(gateway.configurationIdentity(plan.build()), token)
                  .orElseThrow(
                      () ->
                          failure(
                              INVALID_PLAN,
                              "Plan configuration is no longer retained; plan again"));
          if (!configuration.equals(plan.configuration()))
            throw failure(INVALID_PLAN, "Plan configuration proof has changed");
          // This replay is mandatory even when the content already has a durable alias.
          gateway.validateSelection(selection, token);
          if (selection.candidate().encodedBytes() > plan.budgets().maximumRootEncodedBytes()
              || plan.decodedBytes() > plan.budgets().maximumRootDecodedBytes()
              || plan.decodedBytes() > plan.budgets().maximumDecodedWorkBytes() / 2)
            throw failure(
                BUDGET_EXCEEDED, "Selected root exceeds retention/verification work budget");
          var identity = gateway.rootIdentity(selection);
          var candidateContext = enterCandidate(plan.build().snapshotId(), identity);
          try {
            var existing = catalog.findVerifiedObject(plan.build().snapshotId(), ROOT_ROLE, token);
            if (existing.isPresent() && !existing.get().source().equals(identity))
              throw failure(INVALID_PLAN, "Exact build already retains a different root candidate");
            token.throwIfCancellationRequested();
            catalog.registerSnapshot(plan.build());
            retention.ifPresent(
                repository ->
                    repository.replaceUnfulfilledRoot(
                        new RetentionScope(
                            plan.build().snapshotId(),
                            ScopeKind.ROOT_METADATA,
                            List.of(
                                new ScopeRequirement(
                                    CONFIGURATION_ROLE,
                                    Optional.of(configuration.source()),
                                    OptionalLong.of(configuration.object().length()),
                                    OptionalLong.of(configuration.object().length())),
                                new ScopeRequirement(
                                    ROOT_ROLE,
                                    Optional.of(identity),
                                    OptionalLong.of(selection.candidate().encodedBytes()),
                                    OptionalLong.of(plan.decodedBytes())))),
                        token));
            token.throwIfCancellationRequested();
            SourceContext context;
            synchronized (sources) {
              context = sources.computeIfAbsent(identity, ignored -> new SourceContext(selection));
              context.consumers++;
            }
            VerifiedSourceAlias root;
            try {
              var request = acquisitions.acquire(identity);
              root = await(request.completion().toCompletableFuture(), token, request::cancel);
            } finally {
              synchronized (sources) {
                if (--context.consumers == 0) sources.remove(identity, context);
              }
            }
            if (!root.source().equals(identity)
                || root.object().length() != selection.candidate().encodedBytes())
              throw failure(
                  INTEGRITY_FAILURE, "Verified root receipt does not match selected source/size");
            token.throwIfCancellationRequested();
            catalog.registerSnapshot(plan.build());
            attach(plan.build(), CONFIGURATION_ROLE, configuration, token);
            attach(plan.build(), ROOT_ROLE, root, token);
            return root;
          } finally {
            leaveCandidate(plan.build().snapshotId(), candidateContext);
          }
        });
  }

  @Override
  public CompletionStage<RootLookupResult> lookupOffline(
      BuildSnapshot build, Set<Long> ids, LookupBudgets budgets) {
    Objects.requireNonNull(build);
    Objects.requireNonNull(ids);
    Objects.requireNonNull(budgets);
    var selected = Set.copyOf(ids);
    return submit(
        token -> {
          if (selected.isEmpty()
              || selected.size() > budgets.maximumSelectedIds()
              || selected.stream().anyMatch(id -> id < 0 || id > 0xffffffffL))
            throw failure(INVALID_REQUEST, "Select a bounded nonempty set of unsigned FileDataIDs");
          var configuration = retained(build, CONFIGURATION_ROLE, token);
          if (!configuration.source().equals(gateway.configurationIdentity(build)))
            throw failure(
                INTEGRITY_FAILURE, "Retained configuration belongs to a different exact build");
          var root = retained(build, ROOT_ROLE, token);
          if (root.object().length() > budgets.maximumRootEncodedBytes())
            throw failure(BUDGET_EXCEEDED, "Retained root exceeds read budget");
          var result = gateway.lookupRetained(build, configuration, root, selected, budgets, token);
          if (!result.build().equals(build)
              || !result.root().equals(root)
              || !result.requestedIds().equals(selected)
              || result.variants().size() > budgets.maximumMatches()
              || result.scannedRecords() > budgets.maximumRootRecords())
            throw failure(
                INTEGRITY_FAILURE, "Root lookup returned inconsistent coverage or exceeded limits");
          token.throwIfCancellationRequested();
          return result;
        });
  }

  private VerifiedSourceAlias acquire(ResolvedSourceIdentity identity, CancellationToken token) {
    token.throwIfCancellationRequested();
    if (content.isEmpty()) {
      var retained = catalog.findVerifiedSourceAlias(identity, token);
      if (retained.isPresent()) return retained.get();
    }
    RootSelectionPlan selection;
    synchronized (sources) {
      var context = sources.get(identity);
      if (context == null) throw new CancellationException("Root consumers detached");
      selection = context.selection;
    }
    if (content.isPresent()) {
      if (!selection.plan().build().productId().equals("wow"))
        throw failure(UNSUPPORTED, "Metadata content namespace is not reviewed for this product");
      gateway.validateSelection(selection, token);
      var plan = selection.plan();
      var configuration =
          catalog
              .findVerifiedSourceAlias(gateway.configurationIdentity(plan.build()), token)
              .orElseThrow(() -> failure(INVALID_PLAN, "Guarded root lost exact configuration"));
      if (!configuration.equals(plan.configuration()))
        throw failure(INVALID_PLAN, "Guarded root configuration changed");
      var request =
          new MetadataContentRequest(
              plan.build(),
              configuration,
              MetadataStructureAttestation.Kind.ROOT,
              new ContentSlotKey("wow", plan.contentKey()),
              selection.candidate().encodingKey(),
              selection.candidate().encodedBytes(),
              OptionalLong.of(plan.decodedBytes()));
      return content
          .get()
          .acquire(
              request,
              identity,
              () -> catalog.findVerifiedSourceAlias(identity, token),
              (observer, cancellation) -> gateway.acquireRoot(selection, observer, cancellation),
              (artifact, observer, cancellation) ->
                  gateway.lookupRetained(
                      plan.build(),
                      configuration,
                      artifact,
                      Set.of(),
                      plan.budgets(),
                      observer,
                      cancellation),
              token);
    }
    return gateway.acquireRoot(selection, token);
  }

  private VerifiedSourceAlias retained(BuildSnapshot build, String role, CancellationToken token) {
    var reference =
        catalog
            .findVerifiedObject(build.snapshotId(), role, token)
            .orElseThrow(() -> failure(MISSING_LOCAL, "Exact build has no verified local " + role));
    if (!reference.snapshotId().equals(build.snapshotId()) || !reference.role().equals(role))
      throw failure(INTEGRITY_FAILURE, "Catalog returned membership for a different scope");
    return new VerifiedSourceAlias(reference.source(), reference.object(), reference.evidence());
  }

  private void attach(
      BuildSnapshot build, String role, VerifiedSourceAlias value, CancellationToken token) {
    catalog.attachVerifiedObject(
        build.snapshotId(), role, value.source(), value.object(), value.evidence(), token);
  }

  private static <T> T await(
      CompletableFuture<T> future, CancellationToken token, Runnable cancel) {
    try {
      for (; ; ) {
        token.throwIfCancellationRequested();
        try {
          return future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException waiting) {
          /* Observe consumer cancellation without aborting other waiters. */
        }
      }
    } catch (InterruptedException interrupted) {
      cancel.run();
      Thread.currentThread().interrupt();
      throw new CancellationException("Root action cancelled");
    } catch (CancellationException cancelled) {
      cancel.run();
      throw cancelled;
    } catch (ExecutionException failed) {
      throw classify(failed.getCause());
    }
  }

  private <T> CompletionStage<T> submit(Function<CancellationToken, T> work) {
    if (closed.get())
      return CompletableFuture.failedFuture(failure(CLOSED, "File lookup service is closed"));
    if (!capacity.tryAcquire())
      return CompletableFuture.failedFuture(failure(CAPACITY_EXCEEDED, "Too many root actions"));
    var result = new CompletableFuture<T>();
    var worker = new AtomicReference<Thread>();
    active.add(result);
    result.whenComplete(
        (value, error) -> {
          if (result.isCancelled()) {
            var thread = worker.get();
            if (thread != null) thread.interrupt();
          }
        });
    try {
      jobs.execute(
          () -> {
            worker.set(Thread.currentThread());
            try {
              CancellationToken token =
                  () ->
                      result.isCancelled()
                          || closed.get()
                          || Thread.currentThread().isInterrupted();
              token.throwIfCancellationRequested();
              result.complete(work.apply(token));
            } catch (Throwable error) {
              result.completeExceptionally(classify(error));
              if (error instanceof Error fatal) throw fatal;
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
          new FileLookupException(CAPACITY_EXCEEDED, "Root job executor rejected work", rejected));
    }
    if (closed.get()) result.cancel(true);
    return result;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      active.forEach(job -> job.cancel(true));
      acquisitions.close();
    }
  }

  private static RuntimeException classify(Throwable error) {
    while ((error instanceof ExecutionException || error instanceof CompletionException)
        && error.getCause() != null) error = error.getCause();
    if (error instanceof ContentClaimException scoped) return scoped;
    if (error instanceof MetadataAcquisitionFailure scoped) return classify(scoped.failure());
    if (error instanceof FileLookupException scoped) return scoped;
    if (error instanceof CancellationException cancelled) return cancelled;
    if (error instanceof RetentionException retention)
      return new FileLookupException(
          switch (retention.code()) {
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
            case CLOSED -> CLOSED;
            case CAPACITY_EXCEEDED -> CAPACITY_EXCEEDED;
            case NOT_FOUND -> MISSING_LOCAL;
            case STORAGE_UNAVAILABLE -> STORAGE_FAILURE;
          },
          retention.getMessage(),
          retention);
    if (error instanceof CatalogException catalog) {
      boolean integrity =
          catalog.code() == CatalogException.Code.INTEGRITY_FAILURE
              || catalog.code() == CatalogException.Code.CORRUPT_METADATA
              || catalog.code() == CatalogException.Code.IMMUTABLE_CONFLICT;
      return new FileLookupException(
          integrity ? INTEGRITY_FAILURE : STORAGE_FAILURE, catalog.getMessage(), catalog);
    }
    if (error instanceof ObjectStoreException storage)
      return new FileLookupException(
          storage.code() == ObjectStoreException.Code.INTEGRITY_FAILURE
              ? INTEGRITY_FAILURE
              : STORAGE_FAILURE,
          storage.getMessage(),
          storage);
    if (error instanceof AcquisitionException shared) {
      if (shared.getCause() != null) return classify(shared.getCause());
      return new FileLookupException(
          switch (shared.code()) {
            case CLOSED -> CLOSED;
            case CAPACITY_EXCEEDED -> CAPACITY_EXCEEDED;
            case INVALID_VERIFIED_OBJECT -> INTEGRITY_FAILURE;
            case SOURCE_FAILURE -> UNAVAILABLE;
          },
          shared.getMessage(),
          shared);
    }
    return new FileLookupException(STORAGE_FAILURE, "Root action failed", error);
  }

  private static FileLookupException failure(FileLookupException.Code code, String message) {
    return new FileLookupException(code, message);
  }
}
