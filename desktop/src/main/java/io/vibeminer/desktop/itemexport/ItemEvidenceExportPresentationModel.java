package io.vibeminer.desktop.itemexport;

import io.vibeminer.application.itemexport.*;
import io.vibeminer.domain.item.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * One explicit local evidence-export dialog, borrowing its application service. No file, encoding,
 * source acquisition or source-preparation work belongs here. Callers serialize state transitions
 * on their presentation executor. A save terminal state remains observable after context
 * invalidation or close, with contextActive=false; it must never be attached to a newly selected
 * inspector.
 */
public final class ItemEvidenceExportPresentationModel implements AutoCloseable {
  public enum Stage {
    REVIEW,
    VERIFYING,
    VERIFIED,
    SAVING,
    CANCEL_PENDING,
    SAVED,
    CANCELLED,
    FAILED,
    INVALIDATED,
    COMMIT_UNCERTAIN
  }

  public record State(
      ItemEvidenceSelection selection,
      Optional<PreparedItemNames> availableNames,
      Stage stage,
      ItemEvidenceSnapshot snapshot,
      String destination,
      ItemEvidenceReceipt receipt,
      boolean contextActive,
      boolean cancellationRequested,
      boolean closed,
      String message,
      ItemEvidenceException.Code code) {
    public boolean busy() {
      return stage == Stage.VERIFYING || stage == Stage.SAVING || stage == Stage.CANCEL_PENDING;
    }
  }

  private final ItemEvidenceService service;
  private final Executor dispatch;
  private final Consumer<State> observer;
  private final Consumer<ItemEvidenceSelection> invalidation;
  private ItemEvidenceSelection selection;
  private Optional<PreparedItemNames> availableNames = Optional.empty();
  private Stage stage = Stage.REVIEW;
  private ItemEvidenceSnapshot snapshot;
  private String destination;
  private ItemEvidenceReceipt receipt;
  private boolean contextActive, cancellationRequested, closed, cancellationSent;
  private String message = "Select one inspected physical Item row to review its evidence scope.";
  private ItemEvidenceException.Code code;
  private CompletableFuture<?> captureFuture;
  private ItemEvidenceService.SaveOperation saveOperation;
  private long generation, saveTicket;

  public ItemEvidenceExportPresentationModel(
      ItemEvidenceService service, Executor dispatch, Consumer<State> observer) {
    this(service, dispatch, observer, selection -> {});
  }

  public ItemEvidenceExportPresentationModel(
      ItemEvidenceService service,
      Executor dispatch,
      Consumer<State> observer,
      Consumer<ItemEvidenceSelection> invalidation) {
    this.invalidation = Objects.requireNonNull(invalidation);
    this.service = Objects.requireNonNull(service);
    this.dispatch = Objects.requireNonNull(dispatch);
    this.observer = Objects.requireNonNull(observer);
  }

  public State state() {
    return new State(
        selection,
        availableNames,
        stage,
        snapshot,
        destination,
        receipt,
        contextActive,
        cancellationRequested,
        closed,
        message,
        code);
  }

  public long maximumUtf8Bytes() {
    return ItemEvidenceReceipt.MAXIMUM_UTF8_BYTES;
  }

  public boolean canSave() {
    return !closed
        && contextActive
        && stage == Stage.VERIFIED
        && snapshot != null
        && destination != null;
  }

  /** Opening and scope changes only pin identities. They never verify sources or create output. */
  public void open(PreparedItemProjection item, long recordId, Optional<PreparedItemNames> names) {
    if (closed) return;
    if (saving()) {
      invalidateContext();
      return;
    }
    retireCapture();
    snapshot = null;
    destination = null;
    receipt = null;
    cancellationRequested = false;
    cancellationSent = false;
    code = null;
    try {
      Objects.requireNonNull(names);
      if (names.isPresent() && !names.get().item().equals(item))
        throw new IllegalArgumentException("Exact optional pair required");
      selection = new ItemEvidenceSelection(item, recordId, Optional.empty());
      availableNames = names;
      contextActive = true;
      stage = Stage.REVIEW;
      message = "Item-only scope selected. No source verification or output has started.";
    } catch (IllegalArgumentException | NullPointerException invalid) {
      selection = null;
      availableNames = Optional.empty();
      contextActive = false;
      stage = Stage.FAILED;
      code = ItemEvidenceException.Code.INVALID_REQUEST;
      message =
          "Choose one supported physical Item record and an exact optional prepared name pair.";
    }
    emit();
  }

  public boolean includeNames(boolean include) {
    if (closed || !contextActive || state().busy() || selection == null) return false;
    if (include && availableNames.isEmpty()) return false;
    Optional<PreparedItemNames> chosen = include ? availableNames : Optional.empty();
    if (selection.names().equals(chosen)) return true;
    retireCapture();
    selection = new ItemEvidenceSelection(selection.item(), selection.recordId(), chosen);
    snapshot = null;
    destination = null;
    receipt = null;
    cancellationRequested = false;
    code = null;
    stage = Stage.REVIEW;
    message =
        "Export scope changed. Verify evidence explicitly; no preparation or acquisition starts.";
    emit();
    return true;
  }

  public void verifyEvidence() {
    if (closed || !contextActive || state().busy() || selection == null) return;
    retireCapture();
    long ticket = generation;
    var requested = selection;
    snapshot = null;
    destination = null;
    receipt = null;
    cancellationRequested = false;
    cancellationSent = false;
    code = null;
    stage = Stage.VERIFYING;
    message =
        "Verifying the selected evidence locally and capturing one immutable row. No output is"
            + " written.";
    emit();
    if (closed || !contextActive || ticket != generation || stage != Stage.VERIFYING) return;
    try {
      var future = Objects.requireNonNull(service.capture(requested)).toCompletableFuture();
      if (closed || !contextActive || ticket != generation) {
        future.cancel(true);
        return;
      }
      captureFuture = future;
      future.whenComplete(
          (value, error) ->
              dispatch.execute(
                  () -> {
                    if (closed || !contextActive || ticket != generation) return;
                    captureFuture = null;
                    if (error != null) {
                      fail(error, false);
                      return;
                    }
                    if (value == null || !value.selection().equals(requested)) {
                      fail(
                          new ItemEvidenceException(
                              ItemEvidenceException.Code.CORRUPT,
                              "Returned export selection changed"),
                          false);
                      return;
                    }
                    snapshot = value;
                    stage = Stage.VERIFIED;
                    code = null;
                    message =
                        "One immutable evidence snapshot verified. Choose a destination; JSON"
                            + " output is limited to 2 MiB, not guaranteed to fit.";
                    emit();
                  }));
    } catch (RuntimeException error) {
      if (!closed && contextActive && ticket == generation) fail(error, false);
    }
  }

  /**
   * Only destination text is retained; platform path validation belongs to the application/writer.
   */
  public boolean chooseDestination(String chosenPath) {
    if (closed
        || !contextActive
        || state().busy()
        || snapshot == null
        || stage == Stage.COMMIT_UNCERTAIN) return false;
    if (!hasDestinationText(chosenPath)) {
      destination = null;
      receipt = null;
      code = ItemEvidenceException.Code.DESTINATION_REFUSED;
      stage = Stage.VERIFIED;
      message =
          "Choose a destination. Its exact text is preserved; the application validates platform"
              + " form before admitting a save.";
      emit();
      return false;
    }
    destination = chosenPath;
    receipt = null;
    code = null;
    stage = Stage.VERIFIED;
    message =
        "Destination selected. Saving requires explicit replacement authorization for this exact"
            + " path, including a file that appears before commit.";
    emit();
    return true;
  }

  private static boolean hasDestinationText(String value) {
    return value != null && !value.isBlank();
  }

  /** The supplied string is the reviewed exact path, not a preflight existence result. */
  public void saveWithReplacementAuthorization(String displayedPath) {
    if (!canSave()) return;
    if (!Objects.equals(displayedPath, destination) || !hasDestinationText(displayedPath)) {
      code = ItemEvidenceException.Code.DESTINATION_REFUSED;
      message = "The destination changed. Review the exact path before authorizing replacement.";
      emit();
      return;
    }
    var issued = snapshot;
    var authorizedPath = destination;
    long ticket = ++saveTicket;
    stage = Stage.SAVING;
    receipt = null;
    code = null;
    cancellationRequested = false;
    cancellationSent = false;
    message =
        "Saving one JSON evidence snapshot with explicit replacement authorization for the selected"
            + " path.";
    emit();
    if (closed || !contextActive || cancellationRequested || stage != Stage.SAVING) {
      stage = Stage.CANCELLED;
      message = "Save cancelled before publication was started.";
      emit();
      return;
    }
    try {
      // Even a reentrant close/context change during this call must observe its real terminal
      // result.
      var operation = Objects.requireNonNull(service.save(issued, authorizedPath));
      saveOperation = operation;
      var completion = Objects.requireNonNull(operation.completion());
      completion.whenComplete(
          (value, error) -> dispatch.execute(() -> finishSave(ticket, value, error)));
      if (cancellationRequested && saving()) sendCancellation();
    } catch (RuntimeException error) {
      if (ticket == saveTicket && saving()) fail(error, true);
    }
  }

  private void finishSave(long ticket, ItemEvidenceReceipt value, Throwable error) {
    if (ticket != saveTicket || !saving()) return;
    saveOperation = null;
    if (error != null) {
      fail(error, true);
      return;
    }
    if (value == null) {
      fail(
          new ItemEvidenceException(
              ItemEvidenceException.Code.COMMIT_UNCERTAIN, "Missing terminal receipt"),
          true);
      return;
    }
    receipt = value;
    stage = Stage.SAVED;
    code = null;
    message =
        (contextActive && !closed
                ? "Saved JSON evidence. "
                : "Saved JSON evidence for the previous source context. ")
            + value.encodedUtf8Bytes()
            + " UTF-8 bytes; confirmed publication. A completed save can win a cancellation race.";
    emit();
  }

  public void cancel() {
    if (terminalPublication()) return;
    if (saving()) {
      cancellationRequested = true;
      stage = Stage.CANCEL_PENDING;
      message =
          "Save cancellation requested. The actual destination outcome is still pending; a"
              + " confirmed commit can win.";
      emit();
      sendCancellation();
      return;
    }
    if (closed) return;
    retireCapture();
    snapshot = null;
    destination = null;
    receipt = null;
    cancellationRequested = true;
    stage = Stage.CANCELLED;
    code = null;
    message = "Local preview cancelled. No save was requested.";
    emit();
  }

  private void sendCancellation() {
    if (!saving() || saveOperation == null || cancellationSent) return;
    cancellationSent = true;
    try {
      saveOperation.requestCancellation();
    } catch (RuntimeException failedRequest) {
      message = "Cancellation could not be confirmed. The actual save outcome is still pending.";
      emit();
    }
  }

  public void invalidateContext() {
    contextActive = false;
    if (terminalPublication()) {
      emit();
      return;
    }
    if (saving()) {
      cancel();
      return;
    }
    retireCapture();
    snapshot = null;
    destination = null;
    receipt = null;
    stage = Stage.INVALIDATED;
    code = ItemEvidenceException.Code.STALE_SELECTION;
    message =
        "Source context changed. Reopen export from the current inspected row; no output was"
            + " requested.";
    if (!closed) emit();
  }

  private boolean terminalPublication() {
    return stage == Stage.SAVED || stage == Stage.COMMIT_UNCERTAIN;
  }

  private boolean saving() {
    return stage == Stage.SAVING || stage == Stage.CANCEL_PENDING;
  }

  private void fail(Throwable error, boolean publication) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    saveOperation = null;
    receipt = null;
    if (error instanceof CancellationException) {
      stage = Stage.CANCELLED;
      code = null;
      if (!publication) {
        snapshot = null;
        destination = null;
      }
      message =
          publication
              ? "Save cancelled without a confirmed publication receipt."
              : "Evidence verification cancelled. No output was written.";
      emit();
      return;
    }
    code =
        error instanceof ItemEvidenceException typed
            ? typed.code()
            : publication
                ? ItemEvidenceException.Code.COMMIT_UNCERTAIN
                : ItemEvidenceException.Code.STORAGE_UNAVAILABLE;
    if (!publication
        || code == ItemEvidenceException.Code.STALE_SELECTION
        || code == ItemEvidenceException.Code.MISSING_LOCAL
        || code == ItemEvidenceException.Code.CORRUPT
        || code == ItemEvidenceException.Code.UNSUPPORTED) {
      snapshot = null;
      destination = null;
    }
    stage =
        code == ItemEvidenceException.Code.COMMIT_UNCERTAIN ? Stage.COMMIT_UNCERTAIN : Stage.FAILED;
    message =
        switch (code) {
          case INVALID_REQUEST ->
              "The export selection or destination is invalid. Review the exact scope and path"
                  + " before retrying.";
          case BUSY ->
              "The local export worker is busy. Retry the explicit action after the active job"
                  + " retires.";
          case CLOSED -> "The local export service is closed. No new export can be started.";
          case STALE_SELECTION ->
              "The selected source or artifact is outdated. Verify evidence again from the current"
                  + " inspected row.";
          case MISSING_LOCAL ->
              "Required evidence is missing locally. Current preview is cleared; no acquisition or"
                  + " fallback starts.";
          case CORRUPT ->
              "Required evidence failed verification. Current preview is cleared; no repair or"
                  + " Item-only downgrade starts.";
          case UNSUPPORTED ->
              "This evidence or interpretation is unsupported. No expanded scope is substituted.";
          case LIMIT_EXCEEDED ->
              "This evidence exceeds the fixed 2 MiB JSON or supported input limits. No truncation"
                  + " or proof-hash substitution is offered.";
          case STORAGE_UNAVAILABLE ->
              "Local export storage is unavailable. Review the destination before an explicit"
                  + " retry.";
          case DESTINATION_REFUSED ->
              "The selected destination was refused. Choose a valid destination and authorize that"
                  + " exact path explicitly.";
          case ATOMIC_PUBLICATION_UNSUPPORTED ->
              "This destination cannot support the required atomic publication policy. Choose"
                  + " another location; no non-atomic fallback is used.";
          case COMMIT_UNCERTAIN ->
              "File publication could not be confirmed. Inspect the exact destination before"
                  + " retrying; the target is not claimed absent or rolled back.";
        };
    if (!publication
        && (code == ItemEvidenceException.Code.MISSING_LOCAL
            || code == ItemEvidenceException.Code.CORRUPT
            || code == ItemEvidenceException.Code.STALE_SELECTION)) {
      long ticket = generation;
      invalidation.accept(selection);
      if (closed || !contextActive || ticket != generation) return;
    }
    emit();
  }

  private void retireCapture() {
    generation++;
    var old = captureFuture;
    captureFuture = null;
    if (old != null) old.cancel(true);
  }

  private void emit() {
    observer.accept(state());
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    contextActive = false;
    if (terminalPublication()) return;
    if (saving()) {
      cancel();
      return;
    }
    retireCapture();
    snapshot = null;
    destination = null;
    stage = Stage.CANCELLED;
  }
}
