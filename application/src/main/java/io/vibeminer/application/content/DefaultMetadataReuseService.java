package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.catalog.BuildObjectCatalog;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.CanonicalReservation;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Bounded local replay policy. All dependencies, including the executor, remain caller-owned. */
public final class DefaultMetadataReuseService implements MetadataReuseService {
  private static final Duration LIFETIME = Duration.ofMinutes(10);
  private final MetadataReuseGateway gateway;
  private final BuildObjectCatalog builds;
  private final CanonicalContentRepository contents;
  private final MetadataAssociationRepository associations;
  private final Executor executor;
  private final Clock clock;
  private final LongSupplier nanos;
  private final Map<UUID, Issued> issued = new LinkedHashMap<>();
  private final Set<CompletableFuture<?>> active = new HashSet<>();
  private boolean closed;

  public DefaultMetadataReuseService(
      MetadataReuseGateway gateway,
      BuildObjectCatalog builds,
      CanonicalContentRepository contents,
      MetadataAssociationRepository associations,
      Executor executor,
      Clock clock) {
    this(gateway, builds, contents, associations, executor, clock, System::nanoTime);
  }

  public DefaultMetadataReuseService(
      MetadataReuseGateway gateway,
      BuildObjectCatalog builds,
      CanonicalContentRepository contents,
      MetadataAssociationRepository associations,
      Executor executor,
      Clock clock,
      LongSupplier nanos) {
    this.gateway = Objects.requireNonNull(gateway);
    this.builds = Objects.requireNonNull(builds);
    this.contents = Objects.requireNonNull(contents);
    this.associations = Objects.requireNonNull(associations);
    this.executor = Objects.requireNonNull(executor);
    this.clock = Objects.requireNonNull(clock);
    this.nanos = Objects.requireNonNull(nanos);
  }

  @Override
  public CompletionStage<MetadataReusePlan> planRetained(
      BuildSnapshot build, MetadataStructureAttestation.Kind kind, LookupBudgets budgets) {
    return submit(
        token -> {
          Objects.requireNonNull(build);
          Objects.requireNonNull(kind);
          Objects.requireNonNull(budgets);
          var configuration = configuration(build, token);
          var declaration = gateway.readDeclaration(build, configuration, kind, budgets, token);
          if (!declaration.build().equals(build)
              || !declaration.configuration().equals(configuration)
              || declaration.kind() != kind)
            throw failure(
                ContentClaimException.Code.INTEGRITY_FAILURE,
                "Declaration differs from the requested exact configuration");
          gateway.validateDeclaration(declaration, budgets, token);
          var claim = retained(declaration, token);
          if (!(claim.proof().orElseThrow() instanceof MetadataContentProof proof))
            throw failure(
                ContentClaimException.Code.UNSUPPORTED_REUSE,
                "Canonical content has no metadata capability");
          MetadataReusePlan plan;
          try {
            plan = new MetadataReusePlan(UUID.randomUUID(), claim.token(), declaration, proof);
          } catch (IllegalArgumentException invalid) {
            throw failure(ContentClaimException.Code.UNSUPPORTED_REUSE, invalid.getMessage());
          }
          synchronized (this) {
            token.throwIfCancellationRequested();
            issued.put(
                plan.issuanceId(), new Issued(plan, budgets, clock.instant(), nanos.getAsLong()));
            while (issued.size() > 16) issued.remove(issued.keySet().iterator().next());
          }
          return plan;
        });
  }

  @Override
  public CompletionStage<MetadataBuildAssociation> apply(MetadataReusePlan plan) {
    return submit(
        token -> {
          Issued issuance;
          synchronized (this) {
            issuance = plan == null ? null : issued.get(plan.issuanceId());
            if (issuance == null || !issuance.plan.equals(plan) || expired(issuance))
              throw failure(
                  ContentClaimException.Code.REPLAN_REQUIRED,
                  "Local reuse plan is unknown or expired");
          }
          if (!configuration(plan.declaration().build(), token)
              .equals(plan.declaration().configuration()))
            throw failure(
                ContentClaimException.Code.INTEGRITY_FAILURE, "Retained configuration changed");
          gateway.validateDeclaration(plan.declaration(), issuance.budgets, token);
          var claim = retained(plan.declaration(), token);
          if (!claim.token().equals(plan.canonicalReservationToken())
              || !claim.proof().orElseThrow().equals(plan.actualRepresentation()))
            throw failure(
                ContentClaimException.Code.REPLAN_REQUIRED, "Canonical representation changed");
          var actual = gateway.replay(plan, issuance.budgets, token);
          if (!actual.equals(plan.actualRepresentation()))
            throw failure(
                ContentClaimException.Code.INTEGRITY_FAILURE,
                "Replay changed the original representation");
          token.throwIfCancellationRequested();
          var requested =
              new MetadataBuildAssociation(
                  plan.declaration(), plan.canonicalReservationToken(), actual, clock.instant());
          var attached = associations.attach(requested, scope(requested), token);
          if (!sameDeclaration(attached.declaration(), requested.declaration())
              || !attached.canonicalReservationToken().equals(requested.canonicalReservationToken())
              || !attached.actualRepresentation().equals(actual))
            throw failure(
                ContentClaimException.Code.INTEGRITY_FAILURE,
                "Attachment changed immutable association facts");
          return attached;
        });
  }

  private static boolean sameDeclaration(MetadataDeclaration left, MetadataDeclaration right) {
    return left.build().snapshotId().equals(right.build().snapshotId())
        && left.configuration().equals(right.configuration())
        && left.kind() == right.kind()
        && left.slot().equals(right.slot())
        && left.preferredEncodingKey().equals(right.preferredEncodingKey())
        && left.declaredEncodedBytes().equals(right.declaredEncodedBytes())
        && left.declaredDecodedBytes().equals(right.declaredDecodedBytes());
  }

  private VerifiedSourceAlias configuration(BuildSnapshot build, CancellationToken token) {
    token.throwIfCancellationRequested();
    var reference =
        builds
            .findVerifiedObject(build.snapshotId(), "BUILD_CONFIG", token)
            .orElseThrow(
                () ->
                    failure(
                        ContentClaimException.Code.MISSING_LOCAL,
                        "Exact build configuration is not retained"));
    if (!reference.snapshotId().equals(build.snapshotId())
        || !reference.role().equals("BUILD_CONFIG"))
      throw failure(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          "Configuration membership differs from request");
    return new VerifiedSourceAlias(reference.source(), reference.object(), reference.evidence());
  }

  private ContentClaim retained(MetadataDeclaration declaration, CancellationToken token) {
    var claim =
        contents
            .lookup(declaration.slot(), token)
            .orElseThrow(
                () ->
                    failure(
                        ContentClaimException.Code.REPLAN_REQUIRED,
                        "No canonical representation is retained"));
    if (!claim.slot().equals(declaration.slot()))
      throw failure(ContentClaimException.Code.INTEGRITY_FAILURE, "Canonical slot changed");
    if (claim.status() == CanonicalReservation.Status.RECOVERY)
      throw failure(
          ContentClaimException.Code.RECOVERY_REQUIRED,
          "Canonical representation requires recovery");
    if (claim.status() != CanonicalReservation.Status.RETAINED)
      throw failure(ContentClaimException.Code.BUSY, "Canonical source worker is active");
    return claim;
  }

  private static RetentionScope scope(MetadataBuildAssociation association) {
    var declaration = association.declaration();
    var config = declaration.configuration();
    var actual = association.actualRepresentation();
    boolean root = declaration.kind() == MetadataStructureAttestation.Kind.ROOT;
    return new RetentionScope(
        declaration.build().snapshotId(),
        root ? ScopeKind.ROOT_METADATA : ScopeKind.INSTALL_METADATA,
        List.of(
            new ScopeRequirement(
                "BUILD_CONFIG",
                Optional.of(config.source()),
                OptionalLong.of(config.object().length()),
                OptionalLong.of(config.object().length())),
            new ScopeRequirement(
                root ? "ROOT_MANIFEST" : "INSTALL_MANIFEST",
                Optional.of(actual.originalAlias().source()),
                OptionalLong.of(actual.object().length()),
                OptionalLong.of(actual.decodedAttestation().orElseThrow().actualDecodedBytes()))));
  }

  private boolean expired(Issued value) {
    long elapsed = nanos.getAsLong() - value.nanoTime;
    return elapsed < 0
        || elapsed >= LIFETIME.toNanos()
        || clock.instant().isBefore(value.created)
        || !clock.instant().isBefore(value.created.plus(LIFETIME));
  }

  private <T> CompletionStage<T> submit(Work<T> work) {
    var future = new CompletableFuture<T>();
    synchronized (this) {
      if (closed)
        return CompletableFuture.failedFuture(
            failure(ContentClaimException.Code.INVALID_STATE, "Metadata reuse service is closed"));
      if (active.size() >= 4)
        return CompletableFuture.failedFuture(
            failure(ContentClaimException.Code.BUSY, "Local replay capacity is full"));
      active.add(future);
    }
    CancellationToken token = future::isCancelled;
    try {
      executor.execute(
          () -> {
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
    } catch (RuntimeException rejected) {
      synchronized (this) {
        active.remove(future);
      }
      future.completeExceptionally(
          failure(ContentClaimException.Code.BUSY, "Local replay executor rejected work"));
    }
    return future;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    issued.clear();
    for (var future : active) future.cancel(false);
  }

  private static ContentClaimException failure(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }

  private record Issued(
      MetadataReusePlan plan, LookupBudgets budgets, Instant created, long nanoTime) {}

  @FunctionalInterface
  private interface Work<T> {
    T run(CancellationToken token);
  }
}
