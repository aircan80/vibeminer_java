package io.vibeminer.desktop;

import io.vibeminer.application.content.ContentClaimException;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.BuildSnapshot;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Presentation-only exact-ID root lookup. All protocol and retained-byte work belongs to use cases.
 */
public final class FileLookupPresentationModel implements AutoCloseable {
  public enum Stage {
    NO_BUILD,
    UNCHECKED,
    INVALID,
    CHECKING,
    PLANNED,
    RETAINING,
    LOOKING,
    READY,
    FAILED,
    CANCELLED
  }

  public record State(
      BuildSnapshot build,
      String locale,
      String input,
      Long fileDataId,
      Stage stage,
      RootPlan plan,
      RootCandidate candidate,
      RootLookupResult result,
      String message,
      FileLookupException.Code code,
      ContentClaimException.Code claimCode) {
    public State(
        BuildSnapshot build,
        String locale,
        String input,
        Long fileDataId,
        Stage stage,
        RootPlan plan,
        RootCandidate candidate,
        RootLookupResult result,
        String message,
        FileLookupException.Code code) {
      this(build, locale, input, fileDataId, stage, plan, candidate, result, message, code, null);
    }

    public boolean busy() {
      return stage == Stage.CHECKING || stage == Stage.RETAINING || stage == Stage.LOOKING;
    }
  }

  private final FileLookupService service;
  private final Executor executor;
  private final Consumer<State> observer;
  private final LookupBudgets budgets = LookupBudgets.defaults();
  private State state =
      new State(
          null,
          "enUS",
          "",
          null,
          Stage.NO_BUILD,
          null,
          null,
          null,
          "Choose an exact build to look up root membership metadata.",
          null);
  private io.vibeminer.domain.VerifiedSourceAlias historicalRoot;

  public java.util.Optional<io.vibeminer.domain.VerifiedSourceAlias> historicalRoot() {
    return java.util.Optional.ofNullable(historicalRoot);
  }

  /**
   * A positive repair proposal disproves current readiness without identifying a physical cause.
   */
  public void invalidateForRepair() {
    if (closed) return;
    retire();
    if (state.result() != null) historicalRoot = state.result().root();
    publish(
        new State(
            state.build(),
            state.locale(),
            state.input(),
            state.fileDataId(),
            Stage.FAILED,
            state.plan(),
            state.candidate(),
            null,
            "Original retained root metadata needs verification or repair; historical receipts do"
                + " not establish current readiness.",
            null,
            null));
  }

  public void invalidateRetainedEvidence(ContentClaimException.Code code) {
    if (closed
        || code != ContentClaimException.Code.MISSING_LOCAL
            && code != ContentClaimException.Code.INTEGRITY_FAILURE) return;
    retire();
    if (state.result() != null) historicalRoot = state.result().root();
    publish(
        new State(
            state.build(),
            state.locale(),
            state.input(),
            state.fileDataId(),
            Stage.FAILED,
            state.plan(),
            state.candidate(),
            null,
            MetadataClaimMessages.message(code),
            null,
            code));
  }

  private CompletableFuture<?> pending;
  private long generation;
  private boolean closed;

  public FileLookupPresentationModel(
      FileLookupService service, Executor executor, Consumer<State> observer) {
    this.service = Objects.requireNonNull(service);
    this.executor = Objects.requireNonNull(executor);
    this.observer = Objects.requireNonNull(observer);
  }

  public State state() {
    return state;
  }

  public void selectContext(BuildSnapshot build, String locale) {
    if (closed) return;
    if (sameBuild(build) && Objects.equals(locale, state.locale())) return;
    retire();
    historicalRoot = null;
    publish(
        new State(
            build,
            Objects.requireNonNull(locale),
            "",
            null,
            build == null ? Stage.NO_BUILD : Stage.UNCHECKED,
            null,
            null,
            null,
            build == null
                ? "Choose an exact build first."
                : "Not checked. Enter a FileDataID; no network request has been made.",
            null));
  }

  public void setInput(String input) {
    if (closed || Objects.equals(state.input(), input)) return;
    retire();
    publish(
        new State(
            state.build(),
            state.locale(),
            Objects.requireNonNull(input),
            null,
            state.build() == null ? Stage.NO_BUILD : Stage.UNCHECKED,
            null,
            null,
            null,
            "ID changed. Previous lookup evidence and download consent are cleared.",
            null));
  }

  public void check() {
    if (!ready() || !validate()) return;
    BuildSnapshot build = state.build();
    run(
        Stage.CHECKING,
        null,
        null,
        "Checking lookup requirements · at most 8 MiB metadata wire traffic. The root map and game"
            + " file contents are not being downloaded.",
        () -> service.planRoot(build, budgets),
        plan -> {
          if (plan == null || !sameBuild(plan.build()) || !budgets.equals(plan.budgets())) {
            scopeFailure();
            return;
          }
          publish(
              new State(
                  state.build(),
                  state.locale(),
                  state.input(),
                  state.fileDataId(),
                  Stage.PLANNED,
                  plan,
                  null,
                  null,
                  "Plan ready. Review every root encoding candidate and its transfer bound before"
                      + " authorizing the whole metadata map.",
                  null));
        });
  }

  public void selectCandidate(RootCandidate candidate) {
    if (!ready() || state.plan() == null) return;
    if (candidate != null && !state.plan().candidates().contains(candidate)) return;
    publish(
        new State(
            state.build(),
            state.locale(),
            state.input(),
            state.fileDataId(),
            state.stage(),
            state.plan(),
            candidate,
            state.result(),
            state.message(),
            state.code()));
  }

  public void download() {
    if (!ready() || state.plan() == null || state.candidate() == null) return;
    RootPlan plan = state.plan();
    RootCandidate candidate = state.candidate();
    if (candidate.encodedBytes() < 1
        || candidate.encodedBytes() > budgets.maximumRootEncodedBytes()
        || plan.decodedBytes() > budgets.maximumRootDecodedBytes()) return;
    RootSelectionPlan selection = new RootSelectionPlan(plan, candidate);
    run(
        Stage.RETAINING,
        plan,
        candidate,
        "Obtaining and verifying the complete lookup map. No selected game file contents are"
            + " acquired.",
        () -> service.retainRoot(selection),
        proof -> {
          if (proof == null) {
            scopeFailure();
            return;
          }
          lookup();
        });
  }

  public void lookupOffline() {
    if (ready() && validate()) lookup();
  }

  private void lookup() {
    BuildSnapshot build = state.build();
    long id = state.fileDataId();
    run(
        Stage.LOOKING,
        state.plan(),
        state.candidate(),
        "Verifying retained root metadata and looking up this ID locally. No network request.",
        () -> service.lookupOffline(build, Set.of(id), budgets),
        result -> {
          if (result == null
              || !sameBuild(result.build())
              || !result.requestedIds().equals(Set.of(id))) {
            scopeFailure();
            return;
          }
          String message =
              result.variants().isEmpty()
                  ? "No matching FileDataID in this exact, completely verified root. This is not a"
                      + " filename or item search."
                  : result.variants().size()
                      + " membership variants found in the completely verified root. Names and game"
                      + " file contents are not acquired.";
          publish(
              new State(
                  state.build(),
                  state.locale(),
                  state.input(),
                  id,
                  Stage.READY,
                  state.plan(),
                  state.candidate(),
                  result,
                  message,
                  null));
        });
  }

  public void cancel() {
    if (closed || !state.busy()) return;
    retire();
    publish(
        new State(
            state.build(),
            state.locale(),
            state.input(),
            state.fileDataId(),
            Stage.CANCELLED,
            state.plan(),
            state.candidate(),
            null,
            "This request was cancelled. Other requests sharing acquisition may continue. No"
                + " unverified result was opened.",
            null));
  }

  private boolean ready() {
    return !closed && state.build() != null && !state.busy();
  }

  private boolean validate() {
    String input = state.input().strip();
    Long id = null;
    if (input.matches("[0-9]{1,10}"))
      try {
        long parsed = Long.parseLong(input);
        if (parsed <= 0xffffffffL) id = parsed;
      } catch (NumberFormatException ignored) {
      }
    if (id == null) {
      publish(
          new State(
              state.build(),
              state.locale(),
              state.input(),
              null,
              Stage.INVALID,
              null,
              null,
              null,
              "Enter one unsigned decimal FileDataID from 0 to 4294967295.",
              FileLookupException.Code.INVALID_REQUEST));
      return false;
    }
    state =
        new State(
            state.build(),
            state.locale(),
            state.input(),
            id,
            state.stage(),
            state.plan(),
            state.candidate(),
            state.result(),
            state.message(),
            state.code());
    return true;
  }

  private boolean sameBuild(BuildSnapshot build) {
    return build != null
        && state.build() != null
        && build.snapshotId().equals(state.build().snapshotId());
  }

  private void scopeFailure() {
    publish(
        new State(
            state.build(),
            state.locale(),
            state.input(),
            state.fileDataId(),
            Stage.FAILED,
            null,
            null,
            null,
            "Returned lookup metadata does not match this exact request. No result was opened.",
            FileLookupException.Code.INTEGRITY_FAILURE));
  }

  private <T> void run(
      Stage stage,
      RootPlan plan,
      RootCandidate candidate,
      String message,
      Supplier<CompletionStage<T>> action,
      Consumer<T> success) {
    long request = ++generation;
    publish(
        new State(
            state.build(),
            state.locale(),
            state.input(),
            state.fileDataId(),
            stage,
            plan,
            candidate,
            null,
            message,
            null));
    try {
      CompletionStage<T> work = action.get();
      pending = work.toCompletableFuture();
      work.whenComplete(
          (result, error) ->
              executor.execute(
                  () -> {
                    if (closed || generation != request) return;
                    pending = null;
                    if (error != null) failed(error);
                    else success.accept(result);
                  }));
    } catch (RuntimeException error) {
      pending = null;
      failed(error);
    }
  }

  private void failed(Throwable error) {
    Throwable cause = error;
    for (int i = 0;
        i < 8
            && (cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null;
        i++) cause = cause.getCause();
    if (cause instanceof CancellationException) {
      cancel();
      return;
    }
    if (cause instanceof ContentClaimException claim) {
      publish(
          new State(
              state.build(),
              state.locale(),
              state.input(),
              state.fileDataId(),
              Stage.FAILED,
              null,
              null,
              null,
              MetadataClaimMessages.message(claim.code()),
              null,
              claim.code()));
      return;
    }
    FileLookupException.Code code =
        cause instanceof FileLookupException failure ? failure.code() : null;
    String message =
        code == null
            ? "Lookup could not finish. The ID has not been ruled out. Retry an explicit action."
            : switch (code) {
              case INVALID_REQUEST ->
                  "The lookup request is invalid. Check the FileDataID and exact source.";
              case INVALID_PLAN ->
                  "The lookup plan is no longer valid. Check requirements again before"
                      + " downloading.";
              case MISSING_LOCAL ->
                  "Required metadata is not retained for this exact source. The ID has not been"
                      + " ruled out. Check requirements to acquire it.";
              case INTEGRITY_FAILURE ->
                  "Lookup metadata failed verification. No variants are shown; the ID has not been"
                      + " ruled out.";
              case UNAVAILABLE ->
                  "Lookup metadata is unavailable from the selected source. The ID has not been"
                      + " ruled out.";
              case UNSUPPORTED ->
                  "This metadata format or encoding is unsupported. The ID has not been ruled out.";
              case CAPACITY_EXCEEDED -> "The app is busy. Try again shortly.";
              case BUDGET_EXCEEDED ->
                  "Lookup exceeds the disclosed operation limits. The ID has not been ruled out.";
              case STORAGE_FAILURE -> "App-owned metadata storage could not be accessed.";
              case CLOSED -> "This lookup service is no longer available.";
            };
    RootPlan plan = code == FileLookupException.Code.INVALID_PLAN ? null : state.plan();
    publish(
        new State(
            state.build(),
            state.locale(),
            state.input(),
            state.fileDataId(),
            Stage.FAILED,
            plan,
            plan == null ? null : state.candidate(),
            null,
            message,
            code));
  }

  private void publish(State next) {
    state = next;
    observer.accept(next);
  }

  private void retire() {
    generation++;
    CompletableFuture<?> old = pending;
    pending = null;
    if (old != null) old.cancel(true);
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      retire();
    }
  }
}
