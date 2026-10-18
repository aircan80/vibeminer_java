package io.vibeminer.application.content;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * Bounded explicit repair policy. All outbound dependencies and the executor remain caller-owned.
 */
public final class DefaultMetadataRepairService implements MetadataRepairService {
  private final MetadataRepairGateway issuer;
  private final MetadataRepairExecutionGateway execution;
  private final MetadataLocalVerificationGateway local;
  private final MetadataReuseGateway declarations;
  private final BuildObjectCatalog builds;
  private final CanonicalContentRepository contents;
  private final MetadataRepairAuditRepository audits;
  private final MetadataAssociationRepository associations;
  private final ObjectStore store;
  private final Executor executor;
  private final Clock clock;
  private final LongSupplier nanos;
  private final Map<UUID, Context> plans = new LinkedHashMap<>();
  private final Set<CompletableFuture<?>> active = new HashSet<>();
  private final Set<UUID> executing = new HashSet<>();
  private volatile boolean closed;

  private record Context(
      MetadataRepairPlan plan,
      InstallPlan install,
      RootSelectionPlan root,
      MetadataDeclaration declaration,
      LookupBudgets budgets,
      Instant admittedAt,
      long admittedNanos) {}

  @FunctionalInterface
  private interface Work<T> {
    T run(CancellationToken cancellation);
  }

  public DefaultMetadataRepairService(
      MetadataRepairGateway issuer,
      MetadataRepairExecutionGateway execution,
      MetadataLocalVerificationGateway local,
      MetadataReuseGateway declarations,
      BuildObjectCatalog builds,
      CanonicalContentRepository contents,
      MetadataRepairAuditRepository audits,
      MetadataAssociationRepository associations,
      ObjectStore store,
      Executor executor,
      Clock clock) {
    this(
        issuer,
        execution,
        local,
        declarations,
        builds,
        contents,
        audits,
        associations,
        store,
        executor,
        clock,
        System::nanoTime);
  }

  public DefaultMetadataRepairService(
      MetadataRepairGateway issuer,
      MetadataRepairExecutionGateway execution,
      MetadataLocalVerificationGateway local,
      MetadataReuseGateway declarations,
      BuildObjectCatalog builds,
      CanonicalContentRepository contents,
      MetadataRepairAuditRepository audits,
      MetadataAssociationRepository associations,
      ObjectStore store,
      Executor executor,
      Clock clock,
      LongSupplier nanos) {
    this.issuer = Objects.requireNonNull(issuer);
    this.execution = Objects.requireNonNull(execution);
    this.local = Objects.requireNonNull(local);
    this.declarations = Objects.requireNonNull(declarations);
    this.builds = Objects.requireNonNull(builds);
    this.contents = Objects.requireNonNull(contents);
    this.audits = Objects.requireNonNull(audits);
    this.associations = Objects.requireNonNull(associations);
    this.store = Objects.requireNonNull(store);
    this.executor = Objects.requireNonNull(executor);
    this.clock = Objects.requireNonNull(clock);
    this.nanos = Objects.requireNonNull(nanos);
  }

  @Override
  public CompletionStage<MetadataRepairPlan> prepareInstall(
      InstallPlan ordinary, LookupBudgets budgets) {
    return submit(
        token -> {
          Objects.requireNonNull(ordinary);
          Objects.requireNonNull(budgets);
          if (ordinary.repair().isPresent())
            throw failure(REPLAN_REQUIRED, "Use the original ordinary install plan");
          return prepare(ordinary, null, budgets, token);
        });
  }

  @Override
  public CompletionStage<MetadataRepairPlan> prepareRoot(RootSelectionPlan ordinary) {
    return submit(
        token -> {
          Objects.requireNonNull(ordinary);
          if (ordinary.plan().repair().isPresent())
            throw failure(REPLAN_REQUIRED, "Use the original ordinary root selection");
          return prepare(null, ordinary, ordinary.plan().budgets(), token);
        });
  }

  private MetadataRepairPlan prepare(
      InstallPlan install, RootSelectionPlan root, LookupBudgets budgets, CancellationToken token) {
    var build = install != null ? install.build() : root.plan().build();
    var kind =
        install != null
            ? MetadataStructureAttestation.Kind.INSTALL
            : MetadataStructureAttestation.Kind.ROOT;
    var config = configuration(build, token);
    var declaration = declarations.readDeclaration(build, config, kind, budgets, token);
    checkDeclaration(declaration, build, config, kind);
    declarations.validateDeclaration(declaration, budgets, token);
    String content = install != null ? install.contentKey() : root.plan().contentKey();
    String encoding = install != null ? install.encodingKey() : root.candidate().encodingKey();
    long encoded = install != null ? install.encodedBytes() : root.candidate().encodedBytes();
    long decoded = install != null ? install.decodedBytes() : root.plan().decodedBytes();
    if (!declaration.slot().contentKey().equals(content)
        || declaration.declaredDecodedBytes().isPresent()
            && declaration.declaredDecodedBytes().getAsLong() != decoded)
      throw failure(
          INTEGRITY_FAILURE, "Ordinary metadata identity differs from retained declaration");
    if (encoded > budgets.maximumRootEncodedBytes()
        || decoded > budgets.maximumRootDecodedBytes()
        || decoded > budgets.maximumDecodedWorkBytes() / 2)
      throw new MetadataRepairException(
          MetadataRepairException.Code.LIMIT,
          "Repair exceeds approved byte or decoded-work limits");
    if (declaration.preferredEncodingKey().isPresent()
            && !declaration.preferredEncodingKey().get().equals(encoding)
        || declaration.declaredEncodedBytes().isPresent()
            && declaration.declaredEncodedBytes().getAsLong() != encoded)
      throw failure(
          REPLAN_REQUIRED,
          "This ordinary plan does not match its actual configuration declaration");
    var request =
        new MetadataContentRequest(
            build,
            config,
            kind,
            new ContentSlotKey("wow", content),
            encoding,
            encoded,
            OptionalLong.of(decoded));
    var claim = current(request, token);
    var original = (MetadataContentProof) claim.proof().orElseThrow();
    if (physical(original, token))
      throw failure(
          REPLAN_REQUIRED,
          "Original bytes are present; use explicit local replay instead of repair");
    var issued =
        install != null
            ? issuer.prepareInstall(install, config, claim, token)
            : issuer.prepareRoot(root, claim, token);
    if (!issued.reservationToken().equals(claim.token())
        || !issued.request().equals(request)
        || !issued.originalProof().equals(original)
        || !issued.isUsableAt(clock.instant()))
      throw failure(
          INTEGRITY_FAILURE, "Repair issuer changed exact original authority or cost context");
    if (issued.cost().maximumPayloadRequests()
        > (install != null ? 1 : (long) budgets.maximumChunks() + 2))
      throw failure(INTEGRITY_FAILURE, "Repair issuer widened approved payload requests");
    if (issued.cost().maximumDecodedWorkBytes() > budgets.maximumDecodedWorkBytes())
      throw failure(INVALID_REQUEST, "Repair exceeds approved decoded work");
    synchronized (this) {
      token.throwIfCancellationRequested();
      plans.put(
          issued.issuanceId(),
          new Context(
              issued, install, root, declaration, budgets, clock.instant(), nanos.getAsLong()));
      while (plans.size() > 16) plans.remove(plans.keySet().iterator().next());
    }
    return issued;
  }

  @Override
  public CompletionStage<MetadataBuildAssociation> execute(MetadataRepairPlan issued) {
    return submit(
        token -> {
          Context context;
          synchronized (this) {
            context = issued == null ? null : plans.get(issued.issuanceId());
            if (context == null || !context.plan().equals(issued) || expired(context))
              throw failure(REPLAN_REQUIRED, "Repair plan is unknown, altered or expired");
            if (!executing.add(issued.issuanceId()))
              throw failure(BUSY, "This repair plan has an active actual worker");
          }
          try {
            validate(context, token);
            var initial = current(issued.request(), token);
            exact(initial, issued);
            var completed = owned(context, initial, token);
            // owned() returns only after its source finally has retired the actual permit.
            token.throwIfCancellationRequested();
            declarations.validateDeclaration(context.declaration(), context.budgets(), token);
            var requested =
                new MetadataBuildAssociation(
                    context.declaration(), issued.reservationToken(), completed, clock.instant());
            var attached = associations.attach(requested, scope(requested), token);
            if (!sameDeclaration(attached.declaration(), requested.declaration())
                || !attached
                    .canonicalReservationToken()
                    .equals(requested.canonicalReservationToken())
                || !attached.actualRepresentation().equals(completed))
              throw failure(
                  INTEGRITY_FAILURE, "Consumer attachment changed immutable metadata facts");
            return attached;
          } finally {
            synchronized (this) {
              executing.remove(issued.issuanceId());
            }
          }
        });
  }

  private MetadataContentProof owned(
      Context context, ContentClaim initial, CancellationToken token) {
    var permit = contents.claimWorker(initial.token(), token);
    if (!permit.reservationToken().equals(initial.token()))
      throw failure(INTEGRITY_FAILURE, "Worker admission changed original reservation");
    Observer observer = null;
    boolean published = false;
    Throwable primary = null;
    var certainty = MetadataAcquisitionFailure.Publication.UNCERTAIN;
    try {
      token.throwIfCancellationRequested();
      validate(context, token);
      var current = current(context.plan().request(), token);
      exact(current, context.plan());
      var original = (MetadataContentProof) current.proof().orElseThrow();
      MetadataContentProof fresh;
      if (physical(original, token))
        fresh = local.verifyOriginal(context.plan().request(), original, context.budgets(), token);
      else {
        validate(context, token);
        var attempt = audits.begin(permit, context.plan(), token);
        observer = new Observer(context.plan(), permit, attempt);
        var result =
            context.install() != null
                ? execution.repairInstall(
                    context.install(), context.plan(), attempt, observer, token)
                : execution.repairRoot(context.root(), context.plan(), attempt, observer, token);
        if (observer.complete == null || !observer.complete.equals(result))
          throw failure(
              INTEGRITY_FAILURE, "Repair result omitted or changed completing verifier facts");
        token.throwIfCancellationRequested();
        var audit = audits.complete(permit, attempt, result, token);
        if (!audit.attemptId().equals(attempt)
            || !sameIssued(audit.issued(), context.plan())
            || audit.events().isEmpty()
            || audit.events().getLast().kind() != MetadataRepairAuditEvent.Kind.COMPLETED
            || !audit.events().getLast().observation().equals(Optional.of(result)))
          throw failure(INTEGRITY_FAILURE, "Repair audit completion changed exact attempt facts");
        observer.auditCompleted = true;
        fresh =
            new MetadataContentProof(
                original.originalAlias(),
                original.actualEncodingKey(),
                original.declaredContentKey(),
                original.declaredDecodedBytes(),
                Optional.of(result.decodedAttestation()),
                result.structuralAttestation());
      }
      token.throwIfCancellationRequested();
      var merged = addMissing(original, fresh);
      var done = contents.completePublication(permit, merged, token);
      if (!done.token().equals(initial.token())
          || !done.slot().equals(initial.slot())
          || done.status() != CanonicalReservation.Status.RETAINED
          || !done.proof().equals(Optional.of(merged)))
        throw failure(INTEGRITY_FAILURE, "Canonical completion changed original representation");
      published = true;
      return merged;
    } catch (RuntimeException failure) {
      if (failure instanceof MetadataAcquisitionFailure scoped) certainty = scoped.publication();
      var unwrapped =
          failure instanceof MetadataAcquisitionFailure scoped ? scoped.failure() : failure;
      primary = unwrapped;
      throw unwrapped;
    } catch (Error failure) {
      primary = failure;
      throw failure;
    } finally {
      RuntimeException cleanup = null;
      if (observer != null && !observer.auditCompleted) {
        try {
          audits.fail(
              permit,
              observer.attempt,
              observer.prospective == null
                  ? certainty
                  : MetadataAcquisitionFailure.Publication.UNCERTAIN,
              CancellationToken.none());
        } catch (RuntimeException failure) {
          cleanup = failure;
        }
      }
      try {
        contents.finishWorker(
            permit,
            published ? ContentWorkerFinishOutcome.RETAINED : ContentWorkerFinishOutcome.UNCERTAIN,
            CancellationToken.none());
      } catch (RuntimeException failure) {
        if (cleanup == null) cleanup = failure;
        else cleanup.addSuppressed(failure);
      }
      if (cleanup != null) {
        if (primary != null) primary.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }

  private final class Observer implements MetadataRepairObserver {
    final MetadataRepairPlan plan;
    final ContentWorkerPermit permit;
    final UUID attempt;
    MetadataRepairObservation prospective, complete;
    boolean auditCompleted;

    Observer(MetadataRepairPlan plan, ContentWorkerPermit permit, UUID attempt) {
      this.plan = plan;
      this.permit = permit;
      this.attempt = attempt;
    }

    public void beforePromotion(MetadataRepairObservation observation, CancellationToken token) {
      token.throwIfCancellationRequested();
      checkObservation(observation);
      boolean root = plan.request().kind() == MetadataStructureAttestation.Kind.ROOT;
      if (prospective != null
          || complete != null
          || observation.structuralAttestation().isPresent() == root)
        throw failure(INTEGRITY_FAILURE, "Repair prospective callback ordering differs");
      prospective = observation;
      audits.recordProspective(permit, attempt, observation, token);
      token.throwIfCancellationRequested();
    }

    public void afterStructureValidation(
        MetadataRepairObservation observation, CancellationToken token) {
      token.throwIfCancellationRequested();
      checkObservation(observation);
      if (prospective == null
          || complete != null
          || observation.structuralAttestation().isEmpty()
          || !prospective
              .withStructuralAttestation(observation.structuralAttestation().get())
              .equals(observation))
        throw failure(INTEGRITY_FAILURE, "Repair completing callback changed original transfer");
      complete = observation;
      if (plan.request().kind() == MetadataStructureAttestation.Kind.ROOT)
        audits.recordStructure(permit, attempt, observation, token);
      else if (!prospective.equals(observation))
        throw failure(INTEGRITY_FAILURE, "Install completion differs from in-gate grammar");
      token.throwIfCancellationRequested();
    }

    private void checkObservation(MetadataRepairObservation value) {
      if (!value.repairAttemptId().equals(attempt)
          || !value.originalProof().equals(plan.originalProof())
          || !value
              .actualTransfer()
              .evidence()
              .sourceUrl()
              .equals(plan.sourceObservation().resourceUrl())
          || value.payloadRequestCount() > plan.cost().maximumPayloadRequests())
        throw failure(
            INTEGRITY_FAILURE, "Repair verifier changed consented source or original attempt");
    }
  }

  private static MetadataContentProof addMissing(
      MetadataContentProof original, MetadataContentProof fresh) {
    ContentProofPolicy.requirePublicationReady(fresh);
    var originalBase =
        new MetadataContentProof(
            original.originalAlias(),
            original.actualEncodingKey(),
            original.declaredContentKey(),
            original.declaredDecodedBytes(),
            Optional.empty(),
            Optional.empty());
    var freshBase =
        new MetadataContentProof(
            fresh.originalAlias(),
            fresh.actualEncodingKey(),
            fresh.declaredContentKey(),
            fresh.declaredDecodedBytes(),
            Optional.empty(),
            Optional.empty());
    if (!originalBase.equals(freshBase))
      throw failure(INTEGRITY_FAILURE, "Local verification changed original acquisition evidence");
    var merged =
        original.withAttestations(
            original.decodedAttestation().isPresent()
                ? original.decodedAttestation()
                : fresh.decodedAttestation(),
            original.structuralAttestation().isPresent()
                ? original.structuralAttestation()
                : fresh.structuralAttestation());
    ContentProofPolicy.requireMonotonicUpgrade(original, merged);
    return merged;
  }

  private void validate(Context context, CancellationToken token) {
    synchronized (this) {
      if (expired(context)) throw failure(REPLAN_REQUIRED, "Repair consent expired");
    }
    var plan = context.plan();
    if (!configuration(plan.request().build(), token).equals(plan.request().configuration()))
      throw failure(INTEGRITY_FAILURE, "Retained configuration changed");
    declarations.validateDeclaration(context.declaration(), context.budgets(), token);
    if (context.install() != null) issuer.validateInstall(context.install(), plan, token);
    else issuer.validateRoot(context.root(), plan, token);
    token.throwIfCancellationRequested();
  }

  private VerifiedSourceAlias configuration(BuildSnapshot build, CancellationToken token) {
    var reference =
        builds
            .findVerifiedObject(build.snapshotId(), "BUILD_CONFIG", token)
            .orElseThrow(
                () ->
                    failure(MISSING_LOCAL, "Exact retained configuration is required for repair"));
    if (!reference.snapshotId().equals(build.snapshotId())
        || !reference.role().equals("BUILD_CONFIG"))
      throw failure(INTEGRITY_FAILURE, "Configuration reference differs from exact build");
    return new VerifiedSourceAlias(reference.source(), reference.object(), reference.evidence());
  }

  private ContentClaim current(MetadataContentRequest request, CancellationToken token) {
    var claim =
        contents
            .lookup(request.slot(), token)
            .orElseThrow(
                () -> failure(REPLAN_REQUIRED, "Original canonical reservation is absent"));
    if (claim.status() != CanonicalReservation.Status.RETAINED
        && claim.status() != CanonicalReservation.Status.RECOVERY)
      throw failure(BUSY, "Original has an unfinished source worker");
    if (!(claim.proof().orElse(null) instanceof MetadataContentProof proof))
      throw failure(UNSUPPORTED_REUSE, "Encoded-only history cannot authorize metadata repair");
    String namespace =
        request.kind() == MetadataStructureAttestation.Kind.ROOT
            ? "wow.root.tsfm.v1v2"
            : "tact.install.v1";
    if (!claim.slot().equals(request.slot())
        || !claim.actualEncodingKey().equals(request.requestedEncodingKey())
        || claim.encodedBytes() != request.requestedEncodedBytes()
        || !proof.originalAlias().source().namespace().equals(namespace)
        || !proof.contentKey().equals(request.slot().contentKey()))
      throw failure(REPLAN_REQUIRED, "Ordinary plan does not name original canonical A");
    return claim;
  }

  private static void exact(ContentClaim claim, MetadataRepairPlan plan) {
    if (!claim.token().equals(plan.reservationToken())
        || !claim.proof().equals(Optional.of(plan.originalProof())))
      throw failure(REPLAN_REQUIRED, "Canonical original proof changed; issue new repair consent");
  }

  private boolean physical(MetadataContentProof proof, CancellationToken token) {
    try {
      var stored = store.verify(proof.object().digest(), token);
      if (stored.isEmpty()) return false;
      if (!stored.get().equals(proof.object()))
        throw failure(INTEGRITY_FAILURE, "Original physical length disagrees with its ledger");
      return true;
    } catch (ObjectStoreException failure) {
      if (failure.code() == ObjectStoreException.Code.INTEGRITY_FAILURE) return false;
      throw failure;
    }
  }

  private static void checkDeclaration(
      MetadataDeclaration d,
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      MetadataStructureAttestation.Kind kind) {
    if (!d.build().snapshotId().equals(build.snapshotId())
        || !d.configuration().equals(configuration)
        || d.kind() != kind)
      throw failure(INTEGRITY_FAILURE, "Raw configuration declaration changed its exact context");
  }

  private boolean expired(Context context) {
    long elapsed = nanos.getAsLong() - context.admittedNanos();
    return elapsed < 0
        || elapsed
            >= Duration.between(context.plan().issuedAt(), context.plan().expiresAt()).toNanos()
        || clock.instant().isBefore(context.admittedAt())
        || !context.plan().isUsableAt(clock.instant());
  }

  private static boolean sameIssued(MetadataRepairPlan a, MetadataRepairPlan b) {
    var x = a.request();
    var y = b.request();
    return a.issuanceId().equals(b.issuanceId())
        && a.reservationToken().equals(b.reservationToken())
        && x.build().snapshotId().equals(y.build().snapshotId())
        && x.configuration().equals(y.configuration())
        && x.kind() == y.kind()
        && x.slot().equals(y.slot())
        && x.requestedEncodingKey().equals(y.requestedEncodingKey())
        && x.requestedEncodedBytes() == y.requestedEncodedBytes()
        && x.declaredDecodedBytes().equals(y.declaredDecodedBytes())
        && a.originalProof().equals(b.originalProof())
        && a.sourceObservation().equals(b.sourceObservation())
        && a.cost().equals(b.cost())
        && a.issuedAt().equals(b.issuedAt())
        && a.expiresAt().equals(b.expiresAt());
  }

  private static boolean sameDeclaration(MetadataDeclaration a, MetadataDeclaration b) {
    return a.build().snapshotId().equals(b.build().snapshotId())
        && a.configuration().equals(b.configuration())
        && a.kind() == b.kind()
        && a.slot().equals(b.slot())
        && a.preferredEncodingKey().equals(b.preferredEncodingKey())
        && a.declaredEncodedBytes().equals(b.declaredEncodedBytes())
        && a.declaredDecodedBytes().equals(b.declaredDecodedBytes());
  }

  private static RetentionScope scope(MetadataBuildAssociation a) {
    var configuration = a.declaration().configuration();
    var proof = a.actualRepresentation();
    boolean root = a.declaration().kind() == MetadataStructureAttestation.Kind.ROOT;
    return new RetentionScope(
        a.declaration().build().snapshotId(),
        root ? ScopeKind.ROOT_METADATA : ScopeKind.INSTALL_METADATA,
        List.of(
            new ScopeRequirement(
                "BUILD_CONFIG",
                Optional.of(configuration.source()),
                OptionalLong.of(configuration.object().length()),
                OptionalLong.of(configuration.object().length())),
            new ScopeRequirement(
                root ? "ROOT_MANIFEST" : "INSTALL_MANIFEST",
                Optional.of(proof.originalAlias().source()),
                OptionalLong.of(proof.encodedBytes()),
                OptionalLong.of(proof.decodedAttestation().orElseThrow().actualDecodedBytes()))));
  }

  private <T> CompletionStage<T> submit(Work<T> work) {
    var future = new CompletableFuture<T>();
    synchronized (this) {
      if (closed)
        return CompletableFuture.failedFuture(
            failure(INVALID_STATE, "Metadata repair service is closed"));
      if (active.size() >= 4)
        return CompletableFuture.failedFuture(
            failure(BUSY, "Actual repair job capacity is occupied"));
      active.add(future);
    }
    try {
      executor.execute(
          () -> {
            CancellationToken token = () -> future.isCancelled() || closed;
            try {
              token.throwIfCancellationRequested();
              future.complete(work.run(token));
            } catch (Throwable failure) {
              future.completeExceptionally(failure);
            } finally {
              synchronized (this) {
                active.remove(future);
              }
            }
          });
    } catch (RuntimeException failure) {
      synchronized (this) {
        active.remove(future);
      }
      future.completeExceptionally(failure);
    }
    return future;
  }

  @Override
  public void close() {
    List<CompletableFuture<?>> pending;
    synchronized (this) {
      if (closed) return;
      closed = true;
      plans.clear();
      pending = List.copyOf(active);
    }
    for (var future : pending) future.cancel(false);
  }

  private static ContentClaimException failure(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }
}
