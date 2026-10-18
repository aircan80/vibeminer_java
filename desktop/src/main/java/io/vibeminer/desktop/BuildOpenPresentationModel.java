package io.vibeminer.desktop;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildopening.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Presentation-thread-confined observation of one explicit metadata-opening operation. */
public final class BuildOpenPresentationModel implements AutoCloseable {
  public record State(
      BuildLibraryReference reference,
      String locale,
      boolean busy,
      BuildOpenProgress progress,
      BuildMetadataAdmission admission,
      String error) {
    public State {
      Objects.requireNonNull(error);
    }
  }

  private final BuildOpeningService service;
  private final Executor presentation;
  private final Consumer<State> observer;
  private State state = new State(null, null, false, null, null, "");
  private BuildOpeningService.OpenOperation operation;
  private long generation;
  private long overviewBinding;
  private boolean closed;
  private boolean discoveryActive = true;

  public BuildOpenPresentationModel(
      BuildOpeningService service, Executor presentation, Consumer<State> observer) {
    this.service = Objects.requireNonNull(service);
    this.presentation = Objects.requireNonNull(presentation);
    this.observer = Objects.requireNonNull(observer);
  }

  public State state() {
    return state;
  }

  /** Settings preserves the operation but defers the request to show its admitted home. */
  public void setDiscoveryActive(boolean active) {
    if (closed || discoveryActive == active) return;
    discoveryActive = active;
    observer.accept(state);
  }

  public boolean overviewRequested() {
    return !closed && discoveryActive && state.admission() != null;
  }

  /** Changing the exact receipt or locale retires its UI operation, even if commit has won. */
  public void select(BuildLibraryReference reference, String locale) {
    Objects.requireNonNull(reference);
    Objects.requireNonNull(locale);
    if (locale.isBlank()) throw new IllegalArgumentException("Research locale required");
    if (closed || reference.equals(state.reference()) && locale.equals(state.locale())) return;
    retire();
    publish(new State(reference, locale, false, null, null, ""));
  }

  public void clear() {
    if (closed) return;
    retire();
    publish(new State(null, null, false, null, null, ""));
  }

  public void open() {
    if (closed || state.reference() == null || state.busy()) return;
    long epoch = ++generation;
    var reference = state.reference();
    var locale = state.locale();
    try {
      var next = Objects.requireNonNull(service.open(reference, locale));
      if (closed
          || epoch != generation
          || !reference.equals(state.reference())
          || !locale.equals(state.locale())) {
        cancelBestEffort(next);
        return;
      }
      operation = next;
      publish(new State(reference, locale, true, next.progress(), null, ""));
      next.completion()
          .whenComplete(
              (admission, failure) ->
                  presentation.execute(
                      () -> {
                        if (closed || epoch != generation || operation != next) return;
                        operation = null;
                        if (failure != null) {
                          publish(
                              new State(
                                  reference,
                                  locale,
                                  false,
                                  state.progress(),
                                  null,
                                  message(failure)));
                        } else if (admission == null
                            || !reference.equals(admission.reference())
                            || !locale.equals(admission.locale())) {
                          publish(
                              new State(
                                  reference,
                                  locale,
                                  false,
                                  state.progress(),
                                  null,
                                  "Metadata admission did not match the selected source and locale."
                                      + " Retry Open build."));
                        } else {
                          publish(
                              new State(reference, locale, false, next.progress(), admission, ""));
                        }
                      }));
    } catch (RuntimeException failure) {
      if (epoch != generation || closed) return;
      operation = null;
      publish(new State(reference, locale, false, null, null, message(failure)));
    }
  }

  /** Samples only the service's nonblocking immutable observation; never starts network work. */
  public void pollProgress() {
    if (closed || operation == null) return;
    var next = operation.progress();
    if (!Objects.equals(next, state.progress()))
      publish(new State(state.reference(), state.locale(), true, next, null, ""));
  }

  public void cancel() {
    if (closed || !state.busy()) return;
    retire();
    publish(
        new State(
            state.reference(),
            state.locale(),
            false,
            state.progress(),
            null,
            "Opening canceled. Retained metadata remains available for retry."));
  }

  private void retire() {
    generation++;
    var previous = operation;
    operation = null;
    if (previous != null) cancelBestEffort(previous);
  }

  private static void cancelBestEffort(BuildOpeningService.OpenOperation previous) {
    try {
      previous.requestCancellation();
    } catch (RuntimeException ignored) {
      // Local fencing remains authoritative even when adapter cancellation fails.
    }
  }

  public record OverviewActions(Runnable source, Runnable back) {}

  /** Each rendered admitted pane gets a separate action binding; retired pane events are inert. */
  public OverviewActions bindOverviewActions(
      BuildMetadataAdmission admission, Runnable source, Runnable back) {
    Objects.requireNonNull(admission);
    Objects.requireNonNull(source);
    Objects.requireNonNull(back);
    long binding = ++overviewBinding;
    long epoch = generation;
    return new OverviewActions(
        () -> {
          if (!closed
              && epoch == generation
              && binding == overviewBinding
              && state.admission() == admission) source.run();
        },
        () -> {
          if (!closed
              && epoch == generation
              && binding == overviewBinding
              && state.admission() == admission) back.run();
        });
  }

  private void publish(State next) {
    state = next;
    observer.accept(next);
  }

  private static String message(Throwable failure) {
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) failure = failure.getCause();
    if (failure instanceof CancellationException)
      return "Opening canceled. Retry Open build when ready.";
    if (failure instanceof BuildOpeningException typed)
      return "Unable to open build (" + typed.code() + "). Retry Open build.";
    return "Unable to open build. Retry Open build.";
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
  }
}
