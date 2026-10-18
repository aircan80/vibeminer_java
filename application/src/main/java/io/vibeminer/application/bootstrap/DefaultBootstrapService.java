package io.vibeminer.application.bootstrap;

import io.vibeminer.application.*;
import io.vibeminer.application.acquisition.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.application.retention.RetentionException;
import io.vibeminer.application.retention.RetentionRepository;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.retention.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Install-scope use cases: explicit jobs, shared acquisition, retention and verified offline
 * reopening.
 */
public final class DefaultBootstrapService implements BootstrapService {
    private static final String CONFIG_ROLE = "BUILD_CONFIG", INSTALL_ROLE = "INSTALL_MANIFEST";
    private final BootstrapArtifactGateway gateway;
    private final Optional<RetentionRepository> retention;
    private final Optional<MetadataContentAcquisition> content;
    private final BuildObjectCatalog catalog;
    private final Optional<MetadataAssociationRepository> metadataAssociations;
    private final Optional<MetadataReuseGateway> metadataReuse;
    private final Executor executor;
    private final CoalescingAcquisitionService acquisitions;
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(4);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<ResolvedSourceIdentity, SourceContext> sources = new HashMap<>();

    private static final class SourceContext {
        final InstallPlan firstPlan;
        int consumers;

        SourceContext(InstallPlan plan) {
            firstPlan = plan;
        }
    }

    /**
     * Executors and storage are caller-owned. Use distinct pools; shut workers down before closing
     * storage.
     */
    public DefaultBootstrapService(
            BootstrapArtifactGateway gateway,
            BuildObjectCatalog catalog,
            ObjectStore store,
            Executor executor,
            Executor acquisitionExecutor) {
        this(gateway, catalog, store, executor, acquisitionExecutor, null);
    }

    /** Optional retention integration; executors and repository remain caller-owned. */
    public DefaultBootstrapService(
            BootstrapArtifactGateway gateway,
            BuildObjectCatalog catalog,
            ObjectStore store,
            Executor executor,
            Executor acquisitionExecutor,
            RetentionRepository retention) {
        this(gateway, catalog, store, executor, acquisitionExecutor, retention, null);
    }

    /** Opt-in guard; ContentClaimException preserves canonical contention/recovery outcomes. Runtime wiring is separate. */
    public DefaultBootstrapService(
            BootstrapArtifactGateway gateway,
            BuildObjectCatalog catalog,
            ObjectStore store,
            Executor executor,
            Executor acquisitionExecutor,
            RetentionRepository retention,
            CanonicalContentRepository content) {
        this(gateway, catalog, store, executor, acquisitionExecutor, retention, content, null, null);
    }

    /** Local associated reopen is opt-in; both collaborators remain caller-owned. */
    public DefaultBootstrapService(
            BootstrapArtifactGateway gateway,
            BuildObjectCatalog catalog,
            ObjectStore store,
            Executor executor,
            Executor acquisitionExecutor,
            RetentionRepository retention,
            CanonicalContentRepository content,
            MetadataAssociationRepository metadataAssociations,
            MetadataReuseGateway metadataReuse) {
        if ((metadataAssociations == null) != (metadataReuse == null))
            throw new IllegalArgumentException("Associated reopen requires repository and verifier");
        this.metadataAssociations = Optional.ofNullable(metadataAssociations);
        this.metadataReuse = Optional.ofNullable(metadataReuse);
        this.content = Optional.ofNullable(content).map(MetadataContentAcquisition::new);
        this.retention = Optional.ofNullable(retention);
        this.gateway = Objects.requireNonNull(gateway);
        this.catalog = Objects.requireNonNull(catalog);
        this.executor = Objects.requireNonNull(executor);
        acquisitions =
                new CoalescingAcquisitionService(
                        store, this::acquireVerified, acquisitionExecutor, 4, 256, 1024, content == null);
    }

    @Override
    public CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot build) {
        Objects.requireNonNull(build);
        return submit(
                token -> {
                    var retained =
                            catalog.findVerifiedSourceAlias(
                                    gateway.configurationIdentity(build), token);
                    InstallPlan plan;
                    VerifiedSourceAlias artifact;
                    if (retained.isPresent()) {
                        artifact = retained.get();
                        plan = gateway.restoreConfiguration(build, artifact, token);
                    } else {
                        plan = gateway.inspectConfiguration(build, token);
                        artifact = gateway.retainConfiguration(plan, token);
                    }
                    if (!plan.build().equals(build)
                            || !artifact.source().equals(gateway.configurationIdentity(build)))
                        throw failure(
                                BootstrapException.Reason.INTEGRITY,
                                "Resolved configuration does not match selected exact build");
                    token.throwIfCancellationRequested();
                    catalog.registerSnapshot(build);
                    attach(build, CONFIG_ROLE, artifact, token);
                    return plan;
                });
    }

    @Override
    public CompletionStage<InstallInspection> download(InstallPlan plan) {
        Objects.requireNonNull(plan);
        return submit(
                token -> {
                    var retainedConfiguration =
                            catalog.findVerifiedSourceAlias(
                                    gateway.configurationIdentity(plan.build()), token);
                    VerifiedSourceAlias checkedConfiguration = null;
                    if (retainedConfiguration.isPresent()
                            && gateway.restoreConfiguration(
                                            plan.build(), retainedConfiguration.get(), token)
                                    .equals(plan))
                        checkedConfiguration = retainedConfiguration.get();
                    else {
                        var membership =
                                catalog.findVerifiedObject(
                                        plan.build().snapshotId(), CONFIG_ROLE, token);
                        if (membership.isPresent()) {
                            var candidate = receipt(membership.get());
                            if (gateway.restoreConfiguration(plan.build(), candidate, token)
                                    .equals(plan)) checkedConfiguration = candidate;
                        }
                    }
                    if (checkedConfiguration == null)
                        throw failure(
                                BootstrapException.Reason.INTEGRITY,
                                "Configuration plan has no matching retained verification proof;"
                                        + " inspect the configuration first");
                    var identity = gateway.installIdentity(plan);
                    var existing =
                            catalog.findVerifiedObject(
                                    plan.build().snapshotId(), INSTALL_ROLE, token);
                    if (existing.isPresent() && !existing.get().source().equals(identity))
                        throw failure(
                                BootstrapException.Reason.INTEGRITY,
                                "Exact build already retains a different install manifest");
                    token.throwIfCancellationRequested();
                    catalog.registerSnapshot(plan.build());
                    var configurationProof = checkedConfiguration;
                    retention.ifPresent(
                            repository ->
                                    repository.pin(
                                            new RetentionScope(
                                                    plan.build().snapshotId(),
                                                    ScopeKind.INSTALL_METADATA,
                                                    List.of(
                                                            new ScopeRequirement(
                                                                    CONFIG_ROLE,
                                                                    Optional.of(
                                                                            configurationProof
                                                                                    .source()),
                                                                    OptionalLong.of(
                                                                            configurationProof
                                                                                    .object()
                                                                                    .length()),
                                                                    OptionalLong.of(
                                                                            configurationProof
                                                                                    .object()
                                                                                    .length())),
                                                            new ScopeRequirement(
                                                                    INSTALL_ROLE,
                                                                    Optional.of(identity),
                                                                    OptionalLong.of(
                                                                            plan.encodedBytes()),
                                                                    OptionalLong.of(
                                                                            plan.decodedBytes())))),
                                            token));
                    token.throwIfCancellationRequested();
                    SourceContext context;
                    synchronized (sources) {
                        context =
                                sources.computeIfAbsent(
                                        identity, ignored -> new SourceContext(plan));
                        context.consumers++;
                    }
                    VerifiedSourceAlias artifact;
                    try {
                        var shared = acquisitions.acquire(identity);
                        artifact =
                                await(
                                        shared.completion().toCompletableFuture(),
                                        token,
                                        shared::cancel);
                    } finally {
                        synchronized (sources) {
                            if (--context.consumers == 0) sources.remove(identity, context);
                        }
                    }
                    var inspection = gateway.inspectRetained(plan, artifact, token);
                    var configuration = checkedConfiguration;
                    token.throwIfCancellationRequested();
                    catalog.registerSnapshot(plan.build());
                    attach(plan.build(), CONFIG_ROLE, configuration, token);
                    attach(plan.build(), INSTALL_ROLE, artifact, token);
                    return inspection;
                });
    }

    @Override
    public CompletionStage<InstallInspection> reopen(BuildSnapshot build) {
        Objects.requireNonNull(build);
        return submit(
                token -> {
                    var config =
                            catalog.findVerifiedObject(build.snapshotId(), CONFIG_ROLE, token)
                                    .orElseThrow(
                                            () ->
                                                    failure(
                                                            BootstrapException.Reason.MISSING_LOCAL,
                                                            "Pinned configuration is not retained"
                                                                    + " locally"));
                    if (!config.source().equals(gateway.configurationIdentity(build)))
                        throw failure(
                                BootstrapException.Reason.INTEGRITY,
                                "Retained configuration identity mismatch");
                    var plan = gateway.restoreConfiguration(build, receipt(config), token);
                    if (metadataAssociations.isPresent()) {
                        var association =
                                metadataAssociations
                                        .get()
                                        .find(build.snapshotId(), MetadataStructureAttestation.Kind.INSTALL, token);
                        if (association.isPresent()) {
                            var historical = association.get();
                            validateAssociatedContext(build, plan, receipt(config), historical);
                            var inspection =
                                    metadataReuse
                                            .orElseThrow()
                                            .inspectAssociatedInstall(plan, historical, LookupBudgets.defaults(), token);
                            token.throwIfCancellationRequested();
                            if (!inspection.plan().equals(plan)
                                    || !inspection.object().equals(historical.actualRepresentation().object())
                                    || !inspection
                                            .verification()
                                            .equals(historical.actualRepresentation().originalAlias().evidence())
                                    || !inspection.association().equals(Optional.of(historical)))
                                throw failure(
                                        BootstrapException.Reason.INTEGRITY,
                                        "Associated inspection changed declared or actual source facts");
                            return inspection;
                        }
                    }
                    var manifest =
                            catalog.findVerifiedObject(build.snapshotId(), INSTALL_ROLE, token)
                                    .orElseThrow(
                                            () ->
                                                    failure(
                                                            BootstrapException.Reason.MISSING_LOCAL,
                                                            "Install-manifest content is not"
                                                                    + " retained locally"));
                    if (!manifest.source().equals(gateway.installIdentity(plan)))
                        throw failure(
                                BootstrapException.Reason.INTEGRITY,
                                "Retained install-manifest identity mismatch");
                    return gateway.inspectRetained(plan, receipt(manifest), token);
                });
    }

    private static void validateAssociatedContext(
            BuildSnapshot build,
            InstallPlan plan,
            VerifiedSourceAlias configuration,
            MetadataBuildAssociation association) {
        var declaration = association.declaration();
        var actual = association.actualRepresentation();
        if (!plan.build().equals(build)
                || !declaration.build().snapshotId().equals(plan.build().snapshotId())
                || declaration.kind() != MetadataStructureAttestation.Kind.INSTALL
                || !declaration.configuration().equals(configuration)
                || !declaration.slot().contentKey().equals(plan.contentKey())
                || !declaration.preferredEncodingKey().equals(Optional.of(plan.encodingKey()))
                || declaration.declaredEncodedBytes().orElse(-1) != plan.encodedBytes()
                || declaration.declaredDecodedBytes().orElse(-1) != plan.decodedBytes()
                || actual.decodedAttestation().isEmpty()
                || actual.structuralAttestation().isEmpty()
                || actual.structuralAttestation().get().kind() != MetadataStructureAttestation.Kind.INSTALL)
            throw failure(
                    BootstrapException.Reason.INTEGRITY,
                    "Historical association differs from exact declared install configuration");
    }

    private VerifiedSourceAlias acquireVerified(
            ResolvedSourceIdentity source, CancellationToken token) {
        token.throwIfCancellationRequested();
        if (content.isEmpty()) {
            var retained = catalog.findVerifiedSourceAlias(source, token);
            if (retained.isPresent()) return retained.get();
        }
        InstallPlan plan;
        synchronized (sources) {
            var context = sources.get(source);
            if (context == null)
                throw failure(BootstrapException.Reason.CANCELLED, "Pinned source consumer detached");
            plan = context.firstPlan;
        }
        if (content.isPresent()) {
            if (!plan.build().productId().equals("wow"))
                throw failure(
                        BootstrapException.Reason.UNSUPPORTED,
                        "Metadata content namespace is not reviewed for this product");
            var configuration =
                    catalog
                            .findVerifiedSourceAlias(gateway.configurationIdentity(plan.build()), token)
                            .orElseThrow(
                                    () ->
                                            failure(
                                                    BootstrapException.Reason.INTEGRITY,
                                                    "Guarded source lost exact configuration"));
            if (!gateway.restoreConfiguration(plan.build(), configuration, token).equals(plan))
                throw failure(BootstrapException.Reason.INTEGRITY, "Guarded source configuration changed");
            var request =
                    new MetadataContentRequest(
                            plan.build(),
                            configuration,
                            MetadataStructureAttestation.Kind.INSTALL,
                            new ContentSlotKey("wow", plan.contentKey()),
                            plan.encodingKey(),
                            plan.encodedBytes(),
                            OptionalLong.of(plan.decodedBytes()));
            return content
                    .get()
                    .acquire(
                            request,
                            source,
                            () -> catalog.findVerifiedSourceAlias(source, token),
                            (observer, cancellation) -> gateway.acquireInstall(plan, observer, cancellation),
                            (artifact, observer, cancellation) ->
                                    gateway.inspectRetained(plan, artifact, observer, cancellation),
                            token);
        }
        return gateway.acquireInstall(plan, token);
    }

    private void attach(
            BuildSnapshot build,
            String role,
            VerifiedSourceAlias artifact,
            CancellationToken token) {
        catalog.attachVerifiedObject(
                build.snapshotId(),
                role,
                artifact.source(),
                artifact.object(),
                artifact.evidence(),
                token);
    }

    private static VerifiedSourceAlias receipt(BuildObjectReference ref) {
        return new VerifiedSourceAlias(ref.source(), ref.object(), ref.evidence());
    }

    private static <T> T await(
            CompletableFuture<T> future, CancellationToken token, Runnable cancel) {
        try {
            while (true) {
                token.throwIfCancellationRequested();
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waiting) {
                    /* bounded cancellation observation */
                }
            }
        } catch (InterruptedException interrupted) {
            cancel.run();
            Thread.currentThread().interrupt();
            throw new CancellationException("Install scope cancelled");
        } catch (CancellationException cancelled) {
            cancel.run();
            throw cancelled;
        } catch (ExecutionException error) {
            throw classify(error.getCause());
        }
    }

    private <T> CompletionStage<T> submit(Function<CancellationToken, T> work) {
        if (closed.get())
            return CompletableFuture.failedFuture(
                    failure(BootstrapException.Reason.CLOSED, "Install scope service is closed"));
        if (!capacity.tryAcquire())
            return CompletableFuture.failedFuture(
                    failure(
                            BootstrapException.Reason.CAPACITY,
                            "Too many install scope actions are active"));
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
            executor.execute(
                    () -> {
                        worker.set(Thread.currentThread());
                        try {
                            if (result.isCancelled() || closed.get())
                                throw new CancellationException("Install scope cancelled");
                            result.complete(
                                    work.apply(
                                            () ->
                                                    result.isCancelled()
                                                            || closed.get()
                                                            || Thread.currentThread()
                                                                    .isInterrupted()));
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
                    new BootstrapException(
                            BootstrapException.Reason.CAPACITY,
                            "Install scope executor rejected work",
                            rejected));
        }
        if (closed.get()) result.cancel(true);
        return result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(future -> future.cancel(true));
            acquisitions.close();
        }
    }

    private static RuntimeException classify(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        if (error instanceof ContentClaimException scoped) return scoped;
        if (error instanceof MetadataAcquisitionFailure scoped) return classify(scoped.failure());
        if (error instanceof BootstrapException scoped) return scoped;
        if (error instanceof CancellationException)
            return new BootstrapException(
                    BootstrapException.Reason.CANCELLED, "Install scope cancelled", error);
        if (error instanceof RetentionException retention)
            return new BootstrapException(
                    switch (retention.code()) {
                        case INTEGRITY_FAILURE -> BootstrapException.Reason.INTEGRITY;
                        case CLOSED -> BootstrapException.Reason.CLOSED;
                        case CAPACITY_EXCEEDED -> BootstrapException.Reason.CAPACITY;
                        case NOT_FOUND -> BootstrapException.Reason.MISSING_LOCAL;
                        case STORAGE_UNAVAILABLE -> BootstrapException.Reason.STORAGE;
                    },
                    retention.getMessage(),
                    retention);
        if (error instanceof CatalogException catalog)
            return new BootstrapException(
                    catalog.code() == CatalogException.Code.INTEGRITY_FAILURE
                                    || catalog.code() == CatalogException.Code.CORRUPT_METADATA
                            ? BootstrapException.Reason.INTEGRITY
                            : BootstrapException.Reason.STORAGE,
                    catalog.getMessage(),
                    catalog);
        if (error instanceof ObjectStoreException storage)
            return new BootstrapException(
                    storage.code() == ObjectStoreException.Code.INTEGRITY_FAILURE
                            ? BootstrapException.Reason.INTEGRITY
                            : BootstrapException.Reason.STORAGE,
                    storage.getMessage(),
                    storage);
        if (error instanceof AcquisitionException shared) {
            if (shared.getCause() != null) return classify(shared.getCause());
            return new BootstrapException(
                    shared.code() == AcquisitionException.Code.CLOSED
                            ? BootstrapException.Reason.CLOSED
                            : BootstrapException.Reason.CAPACITY,
                    shared.getMessage(),
                    shared);
        }
        return new BootstrapException(
                BootstrapException.Reason.STORAGE, "Install scope action failed", error);
    }

    private static BootstrapException failure(BootstrapException.Reason reason, String message) {
        return new BootstrapException(reason, message);
    }
}
