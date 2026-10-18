package io.vibeminer.desktop;

import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Candidate-metadata presentation only. No locator, acquisition, catalog or adapter work. */
public final class SelectedFilePresentationModel implements AutoCloseable {
  public enum Stage {
    NO_SELECTION,
    UNCHECKED,
    CHECKING,
    CANDIDATES,
    FAILED,
    CANCELLED
  }

  public record State(
      FileVariantAssociation variant,
      String locale,
      Stage stage,
      CandidatePlan plan,
      EncodedCandidate candidate,
      String message,
      SelectedFileException.Code code) {
    public boolean busy() {
      return stage == Stage.CHECKING;
    }
  }

  private final SelectedFileService service;
  private final Executor ui;
  private final Consumer<State> observer;
  private final SelectedFileBudgets budgets = SelectedFileBudgets.defaults();
  private State state =
      new State(
          null,
          "enUS",
          Stage.NO_SELECTION,
          null,
          null,
          "Select a verified file variant first.",
          null);
  private CompletableFuture<?> pending;
  private long generation;
  private boolean closed;

  public SelectedFilePresentationModel(
      SelectedFileService service, Executor ui, Consumer<State> observer) {
    this.service = Objects.requireNonNull(service);
    this.ui = Objects.requireNonNull(ui);
    this.observer = Objects.requireNonNull(observer);
  }

  public State state() {
    return state;
  }

  public SelectedFileBudgets budgets() {
    return budgets;
  }

  public void select(FileVariantAssociation variant, String locale) {
    Objects.requireNonNull(locale);
    if (closed || Objects.equals(variant, state.variant()) && locale.equals(state.locale())) return;
    retire();
    publish(
        new State(
            variant,
            locale,
            variant == null ? Stage.NO_SELECTION : Stage.UNCHECKED,
            null,
            null,
            variant == null
                ? "Select a verified file variant first."
                : "Not checked. No representation has been selected or downloaded.",
            null));
  }

  public void check() {
    if (closed || state.busy() || state.variant() == null) return;
    retire();
    final long request = generation;
    final FileVariantAssociation selected = state.variant();
    publish(
        new State(
            selected,
            state.locale(),
            Stage.CHECKING,
            null,
            null,
            "Checking representation metadata within the stated limits. No file payload is"
                + " downloaded.",
            null));
    if (closed || generation != request) return;
    try {
      CompletableFuture<CandidatePlan> future =
          Objects.requireNonNull(service.planCandidates(selected.build(), selected, budgets))
              .toCompletableFuture();
      pending = future;
      future.whenComplete(
          (result, failure) ->
              ui.execute(
                  () -> {
                    if (closed || generation != request) return;
                    pending = null;
                    if (failure != null) {
                      fail(failure);
                      return;
                    }
                    if (result == null
                        || !selected.equals(result.variant())
                        || !budgets.equals(result.budgets())) {
                      fail(
                          new SelectedFileException(
                              SelectedFileException.Code.INVALID_PLAN,
                              "Mismatched candidate context"));
                      return;
                    }
                    publish(
                        new State(
                            selected,
                            state.locale(),
                            Stage.CANDIDATES,
                            result,
                            null,
                            "All returned representations are shown. Choose one explicitly; no file"
                                + " payload is downloaded.",
                            null));
                  }));
    } catch (RuntimeException failure) {
      if (generation == request) fail(failure);
    }
  }

  public void selectCandidate(EncodedCandidate candidate) {
    if (closed || state.busy() || state.plan() == null) return;
    if (candidate != null && !state.plan().candidates().contains(candidate)) return;
    publish(
        new State(
            state.variant(),
            state.locale(),
            Stage.CANDIDATES,
            state.plan(),
            candidate,
            candidate == null
                ? "Choose a representation explicitly."
                : candidate.encodedBytes().isEmpty()
                    ? "Selected representation has unknown encoded size. It cannot authorize"
                          + " acquisition."
                    : "Representation selected for inspection only. No location or payload request"
                          + " was made.",
            null));
  }

  public void cancel() {
    if (closed || !state.busy()) return;
    retire();
    publish(
        new State(
            state.variant(),
            state.locale(),
            Stage.CANCELLED,
            null,
            null,
            "Your request was cancelled. Shared requests may continue; no payload was downloaded by"
                + " this view.",
            null));
  }

  private void fail(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    if (error instanceof CancellationException) {
      publish(
          new State(
              state.variant(),
              state.locale(),
              Stage.CANCELLED,
              null,
              null,
              "Your metadata request was cancelled.",
              null));
      return;
    }
    SelectedFileException.Code code =
        error instanceof SelectedFileException f
            ? f.code()
            : SelectedFileException.Code.STORAGE_UNAVAILABLE;
    String message =
        switch (code) {
          case BUSY ->
              "Another request is establishing this representation. No competing acquisition was"
                  + " started.";
          case RECOVERY_REQUIRED ->
              "An earlier retention operation requires recovery before another representation can"
                  + " be selected.";
          case REPLAN_REQUIRED ->
              "This representation requires a new plan. Previous consent is cleared.";
          case UNSUPPORTED -> "Representation lookup is not supported for this exact source.";
          case UNAVAILABLE -> "Representation metadata is unavailable from this source.";
          case CAPACITY_EXCEEDED ->
              "The app is busy. Retry the metadata check when another operation finishes.";
          case BUDGET_EXCEEDED -> "Representation lookup exceeded its supported limits.";
          case INCOMPLETE_SEARCH -> "Search incomplete; availability is not established.";
          case INTEGRITY_FAILURE -> "Representation metadata did not pass verification.";
          case MISSING_LOCAL -> "Required verified lookup metadata is not retained locally.";
          case INVALID_REQUEST, INVALID_PLAN ->
              "The exact selected variant or metadata plan could not be validated.";
          case CANCELLED -> "Your metadata request was cancelled. Shared requests may continue.";
          case CLOSED -> "This metadata operation is no longer available.";
          case NOT_FOUND -> "No representation was established for the exact selected variant.";
          case STORAGE_UNAVAILABLE -> "Representation metadata could not be accessed.";
        };
    publish(
        new State(
            state.variant(),
            state.locale(),
            code == SelectedFileException.Code.CANCELLED ? Stage.CANCELLED : Stage.FAILED,
            null,
            null,
            message,
            code));
  }

  private void retire() {
    generation++;
    if (pending != null) {
      var old = pending;
      pending = null;
      old.cancel(true);
    }
  }

  private void publish(State next) {
    state = next;
    observer.accept(next);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
  }
}
