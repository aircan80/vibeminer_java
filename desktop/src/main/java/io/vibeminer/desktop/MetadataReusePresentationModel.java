package io.vibeminer.desktop;

import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Local-only retained-alternative consent. No acquisition or mutation of ordinary declared plans.
 */
public final class MetadataReusePresentationModel implements AutoCloseable {
  public enum Stage {
    NO_BUILD,
    UNSUPPORTED,
    UNCHECKED,
    CHECKING,
    PLANNED,
    APPLYING,
    ASSOCIATED,
    FAILED,
    CANCELLED
  }

  public record State(
      BuildSnapshot build,
      String locale,
      MetadataStructureAttestation.Kind kind,
      Stage stage,
      MetadataReusePlan plan,
      MetadataBuildAssociation association,
      MetadataReusePlan historicalPlan,
      String message,
      ContentClaimException.Code code) {
    public boolean busy() {
      return stage == Stage.CHECKING || stage == Stage.APPLYING;
    }
  }

  private final MetadataReuseService service;
  private final MetadataStructureAttestation.Kind kind;
  private final Executor ui;
  private final Consumer<State> observer;
  private final LookupBudgets budgets = LookupBudgets.defaults();
  private State state;
  private CompletableFuture<?> pending;
  private long generation;
  private boolean closed;

  public MetadataReusePresentationModel(
      MetadataReuseService service,
      MetadataStructureAttestation.Kind kind,
      Executor ui,
      Consumer<State> observer) {
    this.service = Objects.requireNonNull(service);
    this.kind = Objects.requireNonNull(kind);
    this.ui = Objects.requireNonNull(ui);
    this.observer = Objects.requireNonNull(observer);
    state =
        new State(
            null,
            "enUS",
            kind,
            Stage.NO_BUILD,
            null,
            null,
            null,
            "Choose an exact build first.",
            null);
  }

  public State state() {
    return state;
  }

  public LookupBudgets budgets() {
    return budgets;
  }

  public void selectContext(BuildSnapshot build, String locale) {
    Objects.requireNonNull(locale);
    if (closed || Objects.equals(build, state.build()) && locale.equals(state.locale())) return;
    retire();
    state =
        new State(
            build,
            locale,
            kind,
            build == null
                ? Stage.NO_BUILD
                : !build.productId().equals("wow") ? Stage.UNSUPPORTED : Stage.UNCHECKED,
            null,
            null,
            null,
            build == null
                ? "Choose an exact build first."
                : !build.productId().equals("wow")
                    ? "Retained alternative reuse is not supported for this raw product."
                    : "Retained alternatives are not checked. This branch reads existing local"
                        + " evidence only.",
            null);
    observer.accept(state);
  }

  public void checkRetained() {
    if (!ready()) return;
    var build = state.build();
    run(
        Stage.CHECKING,
        "Checking already retained configuration and canonical metadata locally. No payload or"
            + " configuration is fetched.",
        () -> service.planRetained(build, kind, budgets),
        plan -> {
          if (plan == null
              || !plan.declaration().build().snapshotId().equals(build.snapshotId())
              || plan.declaration().kind() != kind
              || plan.actualRepresentation().object().length() > budgets.maximumRootEncodedBytes()
              || plan.actualRepresentation().decodedAttestation().orElseThrow().actualDecodedBytes()
                  > budgets.maximumRootDecodedBytes()) {
            invalid();
            return;
          }
          state =
              new State(
                  state.build(),
                  state.locale(),
                  kind,
                  Stage.PLANNED,
                  plan,
                  null,
                  plan,
                  "Local verification required. No current readability or new build association"
                      + " follows from this proposal.",
                  null);
          observer.accept(state);
        });
  }

  public void apply() {
    if (!ready() || state.plan() == null) return;
    var plan = state.plan();
    run(
        Stage.APPLYING,
        "Verifying retained physical bytes, decoded content and supported metadata structure"
            + " locally. No payload transfer.",
        () -> service.apply(plan),
        association -> {
          if (association == null
              || !sameDeclaration(plan.declaration(), association.declaration())
              || !association.canonicalReservationToken().equals(plan.canonicalReservationToken())
              || !association.actualRepresentation().equals(plan.actualRepresentation())) {
            invalid();
            return;
          }
          state =
              new State(
                  state.build(),
                  state.locale(),
                  kind,
                  Stage.ASSOCIATED,
                  null,
                  association,
                  plan,
                  "Retained metadata was locally verified and associated with this exact build."
                      + " Opening or querying it remains explicit.",
                  null);
          observer.accept(state);
        });
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

  public void cancel() {
    if (closed || !state.busy()) return;
    retire();
    publish(
        Stage.CANCELLED,
        null,
        null,
        "This request was cancelled. Shared work or a prior publication may survive; no new current"
            + " association is claimed.",
        null);
  }

  private boolean ready() {
    return !closed
        && !state.busy()
        && state.build() != null
        && state.build().productId().equals("wow");
  }

  private <T> void run(
      Stage stage, String message, Supplier<CompletionStage<T>> action, Consumer<T> success) {
    retire();
    long request = generation;
    publish(stage, null, null, message, null);
    if (closed || generation != request) return;
    try {
      var future = Objects.requireNonNull(action.get()).toCompletableFuture();
      pending = future;
      future.whenComplete(
          (value, error) ->
              ui.execute(
                  () -> {
                    if (closed || generation != request) return;
                    pending = null;
                    if (error != null) fail(error);
                    else success.accept(value);
                  }));
    } catch (RuntimeException error) {
      if (request == generation) fail(error);
    }
  }

  private void invalid() {
    fail(
        new ContentClaimException(
            ContentClaimException.Code.INTEGRITY_FAILURE, "Returned reuse scope differs"));
  }

  private void fail(Throwable e) {
    while ((e instanceof CompletionException || e instanceof ExecutionException)
        && e.getCause() != null) e = e.getCause();
    if (e instanceof CancellationException) {
      publish(
          Stage.CANCELLED,
          null,
          null,
          "This local request was cancelled. No current reuse result is asserted.",
          null);
      return;
    }
    var code =
        e instanceof ContentClaimException c
            ? c.code()
            : ContentClaimException.Code.STORAGE_UNAVAILABLE;
    String message =
        code == ContentClaimException.Code.MISSING_LOCAL
            ? "Required local configuration or canonical metadata is missing. Use the existing"
                + " configuration Check action if needed, then check retained alternatives again."
                + " No fetch starts automatically."
            : MetadataClaimMessages.message(code);
    publish(Stage.FAILED, null, null, message, code);
  }

  private void publish(
      Stage stage,
      MetadataReusePlan plan,
      MetadataBuildAssociation association,
      String message,
      ContentClaimException.Code code) {
    state =
        new State(
            state.build(),
            state.locale(),
            kind,
            stage,
            plan,
            association,
            state.historicalPlan(),
            message,
            code);
    observer.accept(state);
  }

  private void retire() {
    generation++;
    if (pending != null) {
      var old = pending;
      pending = null;
      old.cancel(true);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
  }
}
