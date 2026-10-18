package io.vibeminer.desktop;

import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Explicit HEAD review and separate original-A repair consent. This borrows its service and never
 * opens metadata, refreshes a source selection, or invokes the separate local-reuse workflow.
 * Callers serialize actions and completion dispatch on their presentation executor.
 */
public final class MetadataRepairPresentationModel implements AutoCloseable {
  public enum Stage {
    NO_CONTEXT,
    NEEDS_SOURCE,
    CHECK_REQUIRED,
    CHECKING,
    PLAN_READY,
    REPAIRING,
    REPAIRED,
    VERIFIED_LOCAL,
    EXPIRED,
    UNAVAILABLE,
    UNSUPPORTED,
    FAILED,
    CANCELLED,
    CLOSED
  }

  public enum Reason {
    CONFIRMED_MISSING,
    CONFIRMED_CORRUPT,
    REPAIR_REQUIRED
  }

  public record ReadinessRevocation(
      String snapshotId, Kind kind, String locale, ContentClaimException.Code code, Reason reason) {
    public ReadinessRevocation(
        String snapshotId, Kind kind, String locale, ContentClaimException.Code code) {
      this(
          snapshotId,
          kind,
          locale,
          code,
          code == ContentClaimException.Code.MISSING_LOCAL
              ? Reason.CONFIRMED_MISSING
              : Reason.CONFIRMED_CORRUPT);
    }

    public ReadinessRevocation {
      Objects.requireNonNull(snapshotId);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(locale);
      Objects.requireNonNull(reason);
      if ((reason == Reason.REPAIR_REQUIRED && code != null)
          || (reason == Reason.CONFIRMED_MISSING
              && code != ContentClaimException.Code.MISSING_LOCAL)
          || (reason == Reason.CONFIRMED_CORRUPT
              && code != ContentClaimException.Code.INTEGRITY_FAILURE))
        throw new IllegalArgumentException("Scoped readiness reason/code mismatch");
    }
  }

  /** Historical original proof, HEAD and completed repair audit remain separate DTOs. */
  public record State(
      BuildSnapshot build,
      String locale,
      Kind kind,
      Stage stage,
      MetadataRepairPlan plan,
      MetadataRepairResult result,
      String message,
      ContentClaimException.Code claimCode,
      MetadataRepairException.Code repairCode,
      MetadataAcquisitionFailure.Publication publication) {
    public boolean busy() {
      return stage == Stage.CHECKING || stage == Stage.REPAIRING;
    }
  }

  private final MetadataRepairService service;
  private final Kind kind;
  private final Executor dispatch;
  private final Consumer<State> observer;
  private final Consumer<ReadinessRevocation> revoke;
  private final Clock clock;
  private State state;
  private InstallPlan install;
  private RootSelectionPlan root;
  private LookupBudgets budgets;
  private CompletableFuture<?> pending;
  private long generation;
  private boolean closed;

  public MetadataRepairPresentationModel(
      MetadataRepairService service, Kind kind, Executor dispatch, Consumer<State> observer) {
    this(service, kind, dispatch, observer, ignored -> {}, Clock.systemUTC());
  }

  public MetadataRepairPresentationModel(
      MetadataRepairService service,
      Kind kind,
      Executor dispatch,
      Consumer<State> observer,
      Consumer<ReadinessRevocation> revoke,
      Clock clock) {
    this.service = Objects.requireNonNull(service);
    this.kind = Objects.requireNonNull(kind);
    this.dispatch = Objects.requireNonNull(dispatch);
    this.observer = Objects.requireNonNull(observer);
    this.revoke = Objects.requireNonNull(revoke);
    this.clock = Objects.requireNonNull(clock);
    state =
        new State(
            null,
            "enUS",
            kind,
            Stage.NO_CONTEXT,
            null,
            null,
            "Choose an exact build before checking original metadata availability.",
            null,
            null,
            null);
  }

  public State state() {
    return state;
  }

  public void selectContext(BuildSnapshot build, String locale) {
    if (closed) return;
    if (locale == null || !locale.matches("[a-z]{2}[A-Z]{2}"))
      throw new IllegalArgumentException("Exact workspace locale required");
    retire();
    install = null;
    root = null;
    budgets = null;
    Stage stage =
        build == null
            ? Stage.NO_CONTEXT
            : build.productId().equals("wow") ? Stage.NEEDS_SOURCE : Stage.UNSUPPORTED;
    publish(
        new State(
            build,
            locale,
            kind,
            stage,
            null,
            null,
            stage == Stage.NO_CONTEXT
                ? "Choose an exact build."
                : stage == Stage.UNSUPPORTED
                    ? "Original metadata repair is not supported for this product."
                    : "An exact supported original-source plan is required. No network request has"
                        + " started.",
            null,
            null,
            null));
  }

  public void offerInstall(InstallPlan ordinary, LookupBudgets localBudgets) {
    if (closed) return;
    if (kind != Kind.INSTALL) throw new IllegalStateException("This is a root repair model");
    Objects.requireNonNull(ordinary);
    Objects.requireNonNull(localBudgets);
    retire();
    install = null;
    root = null;
    budgets = null;
    if (!matchesBuild(ordinary.build()) || ordinary.repair().isPresent()) {
      fail(
          new ContentClaimException(
              ContentClaimException.Code.REPLAN_REQUIRED,
              "Ordinary exact install source required"));
      return;
    }
    install = ordinary;
    budgets = localBudgets;
    sourceOffered();
  }

  public void offerRoot(RootSelectionPlan ordinary) {
    if (closed) return;
    if (kind != Kind.ROOT) throw new IllegalStateException("This is an install repair model");
    Objects.requireNonNull(ordinary);
    retire();
    install = null;
    root = null;
    budgets = null;
    if (!matchesBuild(ordinary.plan().build()) || ordinary.plan().repair().isPresent()) {
      fail(
          new ContentClaimException(
              ContentClaimException.Code.REPLAN_REQUIRED, "Ordinary exact root source required"));
      return;
    }
    root = ordinary;
    budgets = ordinary.plan().budgets();
    sourceOffered();
  }

  private void sourceOffered() {
    update(
        Stage.CHECK_REQUIRED,
        null,
        null,
        "Check original source availability: one HEAD request, zero response-body bytes, up to 15"
            + " seconds. This does not download or verify the metadata.",
        null,
        null);
  }

  public void check() {
    if (closed || state.busy() || state.build() == null || install == null && root == null) return;
    retire();
    long ticket = generation;
    update(
        Stage.CHECKING,
        null,
        null,
        "Checking original source availability with one bounded HEAD request. Payload bytes are not"
            + " requested.",
        null,
        null);
    if (!current(ticket)) return;
    try {
      receive(
          kind == Kind.INSTALL
              ? service.prepareInstall(install, budgets)
              : service.prepareRoot(root),
          ticket,
          plan -> {
            validatePlan(plan);
            if (!plan.isUsableAt(clock.instant())) {
              expire();
              return;
            }
            revoke.accept(
                new ReadinessRevocation(
                    state.build().snapshotId(),
                    kind,
                    state.locale(),
                    null,
                    Reason.REPAIR_REQUIRED));
            if (!current(ticket)) return;
            update(
                Stage.PLAN_READY,
                plan,
                null,
                "Availability was observed at the recorded HEAD time; it does not guarantee a later"
                    + " download. Review the exact original encoding, positive transfer and"
                    + " temporary-space limits before consenting.",
                null,
                null);
          });
    } catch (RuntimeException failure) {
      if (current(ticket)) fail(failure);
    }
  }

  /** Only this explicit action submits positive payload consent. */
  public void download() {
    if (closed || state.stage() != Stage.PLAN_READY || state.plan() == null) return;
    var plan = state.plan();
    if (!plan.isUsableAt(clock.instant())) {
      expire();
      return;
    }
    retire();
    long ticket = generation;
    update(
        Stage.REPAIRING,
        plan,
        null,
        "Downloading the consented original representation and verifying its exact bytes and"
            + " metadata. Cancelling this request does not imply that shared source work has"
            + " retired.",
        null,
        null);
    if (!current(ticket)) return;
    try {
      receive(
          service.execute(plan),
          ticket,
          result -> {
            validateResult(plan, result);
            update(
                result.completedAudit().isPresent() ? Stage.REPAIRED : Stage.VERIFIED_LOCAL,
                plan,
                result,
                result.completedAudit().isPresent()
                    ? "Original metadata repaired and verified. Fresh transfer evidence is separate"
                        + " from original history. Open offline explicitly to inspect it."
                    : "Original metadata was verified locally during execution. No payload download"
                        + " was needed. Open offline explicitly to inspect it.",
                null,
                null);
          });
    } catch (RuntimeException failure) {
      if (current(ticket)) fail(failure);
    }
  }

  /** Presentation clocks may call this without issuing a replacement HEAD or payload request. */
  public void refreshExpiry() {
    if (!closed && state.stage() == Stage.PLAN_READY && !state.plan().isUsableAt(clock.instant()))
      expire();
  }

  private void expire() {
    retire();
    update(
        Stage.EXPIRED,
        null,
        null,
        "Repair consent has expired. Check original source availability again before downloading.",
        null,
        MetadataRepairException.Code.INVALID_PLAN);
  }

  public void cancel() {
    if (closed || !state.busy()) return;
    retire();
    update(
        Stage.CANCELLED,
        null,
        null,
        "This request was cancelled. Shared work may continue; no new completion or readiness is"
            + " assumed.",
        null,
        null);
  }

  private void validatePlan(MetadataRepairPlan plan) {
    if (plan == null
        || !matchesBuild(plan.request().build())
        || plan.request().kind() != kind
        || !plan.request().slot().contentKey().equals(content())
        || !plan.request().requestedEncodingKey().equals(encoding())
        || plan.request().requestedEncodedBytes() != encoded()
        || plan.request().declaredDecodedBytes().orElse(-1) != decoded()
        || plan.cost().encodedTransferBytes() > budgets.maximumRootEncodedBytes()
        || decoded() > budgets.maximumRootDecodedBytes()
        || plan.cost().maximumDecodedWorkBytes() > budgets.maximumDecodedWorkBytes()
        || plan.cost().maximumPayloadRequests()
            > (kind == Kind.INSTALL ? 1L : (long) budgets.maximumChunks() + 2))
      throw inconsistent("Repair plan changed exact source, cost or work bounds");
    var config = plan.request().configuration();
    if (root != null && !config.equals(root.plan().configuration()))
      throw inconsistent("Repair plan changed retained root configuration evidence");
    if (install != null
        && (!config.evidence().sourceUrl().equals(install.configuration().sourceUrl())
            || !config.evidence().verifiedAt().equals(install.configuration().capturedAt())
            || !config.object().digest().value().equals(install.configuration().sha256())
            || config.object().length() != install.configuration().bytes().length))
      throw inconsistent("Repair plan changed retained install configuration evidence");
  }

  private void validateResult(MetadataRepairPlan plan, MetadataRepairResult result) {
    if (result == null) throw inconsistent("Repair result required");
    var association = result.association();
    var declaration = association.declaration();
    if (!matchesBuild(declaration.build())
        || declaration.kind() != kind
        || !declaration.configuration().equals(plan.request().configuration())
        || !declaration.slot().equals(plan.request().slot())
        || !association.canonicalReservationToken().equals(plan.reservationToken())
        || declaration.preferredEncodingKey().isPresent()
            && !declaration
                .preferredEncodingKey()
                .get()
                .equals(plan.request().requestedEncodingKey())
        || declaration.declaredEncodedBytes().isPresent()
            && declaration.declaredEncodedBytes().getAsLong()
                != plan.request().requestedEncodedBytes()
        || declaration.declaredDecodedBytes().isPresent()
            && !declaration.declaredDecodedBytes().equals(plan.request().declaredDecodedBytes()))
      throw inconsistent(
          "Repair result changed exact build, original representation or configuration");
    if (kind == Kind.INSTALL
        && (!declaration.preferredEncodingKey().equals(java.util.Optional.of(encoding()))
            || !declaration.declaredEncodedBytes().equals(java.util.OptionalLong.of(encoded()))
            || !declaration.declaredDecodedBytes().equals(java.util.OptionalLong.of(decoded()))))
      throw inconsistent("Install repair result omitted its known configuration declarations");
    try {
      ContentProofPolicy.requireMonotonicUpgrade(
          plan.originalProof(), association.actualRepresentation());
      ContentProofPolicy.requirePublicationReady(association.actualRepresentation());
    } catch (ContentClaimException invalid) {
      throw inconsistent("Repair result changed original provenance or required capability");
    }
    if (result.completedAudit().isPresent()) {
      var audit = result.completedAudit().get();
      if (!audit.issued().equals(plan)
          || audit.events().isEmpty()
          || audit.events().getLast().kind() != MetadataRepairAuditEvent.Kind.COMPLETED)
        throw inconsistent("Repair audit belongs to different consent or incomplete work");
    }
  }

  private boolean matchesBuild(BuildSnapshot build) {
    return state.build() != null
        && state.build().productId().equals("wow")
        && state.build().snapshotId().equals(build.snapshotId());
  }

  private String content() {
    return install != null ? install.contentKey() : root.plan().contentKey();
  }

  private String encoding() {
    return install != null ? install.encodingKey() : root.candidate().encodingKey();
  }

  private long encoded() {
    return install != null ? install.encodedBytes() : root.candidate().encodedBytes();
  }

  private long decoded() {
    return install != null ? install.decodedBytes() : root.plan().decodedBytes();
  }

  private <T> void receive(CompletionStage<T> stage, long ticket, Consumer<T> success) {
    var future = Objects.requireNonNull(stage).toCompletableFuture();
    if (!current(ticket)) {
      future.cancel(true);
      return;
    }
    pending = future;
    stage.whenComplete(
        (value, failure) ->
            dispatch.execute(
                () -> {
                  if (closed || generation != ticket) return;
                  pending = null;
                  if (failure != null) {
                    fail(failure);
                    return;
                  }
                  try {
                    success.accept(value);
                  } catch (RuntimeException invalid) {
                    fail(invalid);
                  }
                }));
  }

  private void fail(Throwable failure) {
    long ticket = generation;
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) failure = failure.getCause();
    if (failure instanceof MetadataAcquisitionFailure acquisition) {
      String message =
          acquisition.publication()
                  == MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED
              ? "Repair failed before a replacement was promoted. Payload may have been requested"
                  + " or received. No new repair completion is established. Review the exact"
                  + " source before a fresh explicit repair."
              : "Repair did not complete. Local bytes may be present or restored; their current"
                  + " readiness is unknown. Verify retained bytes explicitly before checking a"
                  + " fresh repair plan. No automatic retry or offline opening was started.";
      publish(
          new State(
              state.build(),
              state.locale(),
              kind,
              Stage.FAILED,
              null,
              null,
              message,
              null,
              null,
              acquisition.publication()));
      return;
    }
    if (failure instanceof CancellationException) {
      update(
          Stage.CANCELLED,
          null,
          null,
          "This request was cancelled. No repair completion is assumed.",
          null,
          null);
      return;
    }
    if (failure instanceof MetadataRepairException repair) {
      Stage stage =
          repair.code() == MetadataRepairException.Code.UNAVAILABLE
              ? Stage.UNAVAILABLE
              : repair.code() == MetadataRepairException.Code.UNSUPPORTED
                  ? Stage.UNSUPPORTED
                  : Stage.FAILED;
      String message =
          switch (repair.code()) {
            case UNAVAILABLE ->
                "The exact original source is unavailable. No alternate encoding, mirror or retry"
                    + " was requested.";
            case UNSUPPORTED ->
                "Repair of the original retained source is unsupported for this association."
                    + " Inspect the original receipt; no preferred alternative is downloaded.";
            case INVALID_PLAN ->
                "Repair consent is invalid or stale. Check the exact original source again before"
                    + " downloading.";
            case LIMIT ->
                "Repair exceeded its approved limits. No wider scope or automatic retry is"
                    + " permitted.";
            case INTEGRITY ->
                "Repair availability or transfer evidence failed verification. No metadata"
                    + " readiness or automatic retry follows from this check.";
            case CLOSED -> "The repair service is closed. No new repair request was started.";
          };
      update(stage, null, null, message, null, repair.code());
      return;
    }
    var code =
        failure instanceof ContentClaimException claim
            ? claim.code()
            : ContentClaimException.Code.STORAGE_UNAVAILABLE;
    if (code == ContentClaimException.Code.MISSING_LOCAL
        || code == ContentClaimException.Code.INTEGRITY_FAILURE) {
      revoke(code);
      if (!current(ticket)) return;
    }
    String message =
        switch (code) {
          case REPLAN_REQUIRED ->
              "Repair needs a supported exact original-source plan. A current alternative encoding"
                  + " cannot authorize original repair. Review the source or the separate"
                  + " local-reuse branch.";
          case BUSY -> "Another source worker owns this content. No competing repair was started.";
          case LEGACY_CONFLICT ->
              "Historical representations conflict. Inspect their original evidence before any"
                  + " repair.";
          case RECOVERY_REQUIRED ->
              "An earlier operation requires recovery. Its original ownership remains protected.";
          case UNSUPPORTED_REUSE ->
              "Repair of this retained source is unsupported. No alternative representation is"
                  + " downloaded.";
          case MISSING_LOCAL ->
              "Required retained evidence is missing. Current metadata readiness is cleared; no"
                  + " repair starts automatically.";
          case INTEGRITY_FAILURE ->
              "Metadata or repair evidence failed verification. Current readiness is cleared; check"
                  + " the exact source again.";
          case INVALID_REQUEST, INVALID_STATE ->
              "The exact repair request is no longer valid. Review its original source and check"
                  + " again.";
          case STORAGE_UNAVAILABLE ->
              "App-owned repair storage could not be accessed. No automatic repair or retry"
                  + " follows.";
        };
    update(
        code == ContentClaimException.Code.UNSUPPORTED_REUSE ? Stage.UNSUPPORTED : Stage.FAILED,
        null,
        null,
        message,
        code,
        null);
  }

  private void revoke(ContentClaimException.Code code) {
    if (state.build() != null)
      revoke.accept(
          new ReadinessRevocation(state.build().snapshotId(), kind, state.locale(), code));
  }

  private static MetadataRepairException inconsistent(String message) {
    return new MetadataRepairException(MetadataRepairException.Code.INTEGRITY, message);
  }

  private void update(
      Stage stage,
      MetadataRepairPlan plan,
      MetadataRepairResult result,
      String message,
      ContentClaimException.Code claimCode,
      MetadataRepairException.Code repairCode) {
    publish(
        new State(
            state.build(),
            state.locale(),
            kind,
            stage,
            plan,
            result,
            message,
            claimCode,
            repairCode,
            null));
  }

  private void publish(State next) {
    state = next;
    observer.accept(next);
  }

  private boolean current(long ticket) {
    return !closed && generation == ticket;
  }

  private void retire() {
    generation++;
    var previous = pending;
    pending = null;
    if (previous != null) previous.cancel(true);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
    install = null;
    root = null;
    budgets = null;
    update(Stage.CLOSED, null, null, "Repair presentation closed.", null, null);
  }
}
