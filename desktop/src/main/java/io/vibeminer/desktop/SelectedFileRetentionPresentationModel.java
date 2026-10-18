package io.vibeminer.desktop;

import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Explicit selected-file stages; no network, storage, routing or verification implementation. */
public final class SelectedFileRetentionPresentationModel implements AutoCloseable {
  public enum Stage {
    NO_CHOICE,
    UNLOCATED,
    LOCATING,
    LOOSE_MISSING,
    SEARCHING,
    SEARCH_INCOMPLETE,
    SEARCH_COMPLETE,
    PLANNED,
    RETAINING,
    RETAINED,
    REOPENING,
    OFFLINE_VERIFIED,
    FAILED,
    CANCELLED
  }

  public record State(
      CandidateChoice choice,
      String locale,
      Stage stage,
      Loose404Permit missing,
      SelectedFilePlan plan,
      SelectedFileAssociation receipt,
      String message,
      SelectedFileException.Code code,
      int checkedArchives,
      int advertisedArchives) {
    public boolean busy() {
      return Set.of(Stage.LOCATING, Stage.SEARCHING, Stage.RETAINING, Stage.REOPENING)
          .contains(stage);
    }
  }

  private final SelectedFileService service;
  private final Executor ui;
  private final Consumer<State> observer;
  private final ArchiveSearchBudgets archiveBudgets = ArchiveSearchBudgets.defaults();
  private FileVariantAssociation selectedVariant;

  public FileVariantAssociation variant() {
    return selectedVariant;
  }

  public void selectVariant(FileVariantAssociation variant, String locale) {
    Objects.requireNonNull(locale);
    if (closed || Objects.equals(variant, selectedVariant) && locale.equals(state.locale())) return;
    retire();
    selectedVariant = variant;
    state =
        new State(
            null,
            locale,
            Stage.NO_CHOICE,
            null,
            null,
            null,
            variant == null
                ? "Select a verified root variant first."
                : "Selected file not checked. Offline inspection uses only retained exact-variant"
                    + " evidence.",
            null,
            0,
            0);
    observer.accept(state);
  }

  private State state =
      new State(
          null,
          "enUS",
          Stage.NO_CHOICE,
          null,
          null,
          null,
          "Choose a representation first.",
          null,
          0,
          0);
  private CompletableFuture<?> pending;
  private long generation;
  private boolean closed;

  public SelectedFileRetentionPresentationModel(
      SelectedFileService service, Executor ui, Consumer<State> observer) {
    this.service = Objects.requireNonNull(service);
    this.ui = Objects.requireNonNull(ui);
    this.observer = Objects.requireNonNull(observer);
  }

  public State state() {
    return state;
  }

  public ArchiveSearchBudgets archiveBudgets() {
    return archiveBudgets;
  }

  public void select(CandidateChoice choice, String locale) {
    Objects.requireNonNull(locale);
    if (choice != null && !Objects.equals(choice.plan().variant(), selectedVariant))
      selectVariant(choice.plan().variant(), locale);
    if (closed || Objects.equals(choice, state.choice()) && locale.equals(state.locale())) return;
    retire();
    state =
        new State(
            choice,
            locale,
            choice == null ? Stage.NO_CHOICE : Stage.UNLOCATED,
            null,
            null,
            null,
            choice == null
                ? "Choose a representation first."
                : "Selection alone makes no request. Locate metadata and file retention require"
                    + " separate actions.",
            null,
            0,
            0);
    observer.accept(state);
  }

  public void locate() {
    if (!available()) return;
    var choice = state.choice();
    if (!choice.plan().candidates().contains(choice.candidate())) {
      fail(new SelectedFileException(SelectedFileException.Code.INVALID_PLAN, "Unknown choice"));
      return;
    }
    run(
        Stage.LOCATING,
        "Locating the selected representation within metadata limits. No file payload is acquired.",
        () -> service.probeLoose(choice),
        probe -> {
          if (probe == null) {
            invalid();
            return;
          }
          if (probe.plan().isPresent()) acceptPlan(probe.plan().get());
          else {
            var permit = probe.missing().orElseThrow();
            if (!choice.equals(permit.choice())) {
              invalid();
              return;
            }
            publish(
                Stage.LOOSE_MISSING,
                permit,
                null,
                null,
                "Direct object returned 404. Archive search requires separate approval.",
                null,
                0,
                0);
          }
        });
  }

  public void searchArchives() {
    if (!available() || state.missing() == null) return;
    var permit = state.missing();
    run(
        Stage.SEARCHING,
        "Searching archives within the separately approved metadata limits. File payload is not"
            + " acquired.",
        () -> service.searchArchives(permit, archiveBudgets),
        result -> {
          if (result == null) {
            invalid();
            return;
          }
          if (result.plan().isPresent()) acceptPlan(result.plan().get());
          else
            publish(
                result.complete() ? Stage.SEARCH_COMPLETE : Stage.SEARCH_INCOMPLETE,
                null,
                null,
                null,
                result.complete()
                    ? "No location was found in the completed permitted archive search."
                    : "Search incomplete; availability is not established.",
                null,
                result.checkedArchives(),
                result.advertisedArchives());
        });
  }

  public void retain() {
    if (!available() || state.plan() == null) return;
    var plan = state.plan();
    run(
        Stage.RETAINING,
        "Obtaining and verifying complete encoded bytes. Decoded content is not verified.",
        () -> service.retain(plan),
        receipt -> {
          if (!validReceipt(receipt, plan)) {
            invalid();
            return;
          }
          publish(
              Stage.RETAINED,
              null,
              null,
              receipt,
              "Complete encoded bytes verified and retained. Decoded content remains unverified.",
              null,
              0,
              0);
        });
  }

  public void reopen() {
    if (closed || state.busy() || selectedVariant == null) return;
    var variant = selectedVariant;
    var budgets =
        state.choice() == null ? SelectedFileBudgets.defaults() : state.choice().plan().budgets();
    run(
        Stage.REOPENING,
        "Verifying retained physical bytes offline. No network request is made.",
        () -> service.inspectOffline(variant, budgets),
        receipt -> {
          if (receipt == null || !variant.equals(receipt.descriptor().association())) {
            invalid();
            return;
          }
          publish(
              Stage.OFFLINE_VERIFIED,
              null,
              null,
              receipt,
              "Retained encoded bytes verified offline. Decoded content remains unverified.",
              null,
              0,
              0);
        });
  }

  private boolean validReceipt(SelectedFileAssociation receipt, SelectedFilePlan plan) {
    if (receipt == null
        || !receipt.descriptor().association().equals(plan.choice().plan().variant())) return false;
    String actual =
        plan.reuse().isPresent()
            ? plan.reuse().get().encoded().source().immutableSourceKey()
            : plan.choice().candidate().encodingKey();
    long expectedLength =
        plan.acquisition().isPresent()
            ? plan.acquisition().get().encodedBytes()
            : plan.reuse().orElseThrow().encoded().object().length();
    return (plan.reuse().isEmpty() || receipt.representation().equals(plan.reuse().get()))
        && actual.equals(receipt.descriptor().actualEncodingKey())
        && receipt.representation().encoded().object().length() == expectedLength
        && receipt.configuration().equals(plan.choice().plan().configuration())
        && receipt.root().equals(plan.choice().plan().root())
        && receipt.proof().equals(plan.choice().plan().proof());
  }

  private void acceptPlan(SelectedFilePlan plan) {
    if (plan == null || !state.choice().equals(plan.choice())) {
      invalid();
      return;
    }
    if (plan.reuse().isPresent()
        && (!plan.reuse().get().slot().equals(state.choice().plan().variant().slot())
            || !plan.reuse()
                .get()
                .encoded()
                .source()
                .immutableSourceKey()
                .equals(state.choice().candidate().encodingKey()))) {
      invalid();
      return;
    }
    long encoded =
        plan.acquisition().isPresent()
            ? plan.acquisition().get().encodedBytes()
            : plan.reuse().orElseThrow().encoded().object().length();
    var budgets = state.choice().plan().budgets();
    if (state.choice().candidate().encodedBytes().isPresent()
            && state.choice().candidate().encodedBytes().getAsLong() != encoded
        || plan.acquisition().isPresent() && plan.cost().encodedTransferBytes() != encoded
        || plan.reuse().isPresent()
            && (plan.cost().encodedTransferBytes() != 0
                || plan.cost().maximumAdditionalPhysicalBytes() != 0)
        || encoded < 1
        || encoded > budgets.maximumEncodedFileBytes()
        || plan.cost().encodedTransferBytes() > budgets.maximumPayloadWireBytes()
        || plan.acquisition().isPresent()
            && (!state.choice().equals(plan.acquisition().get().choice())
                || !plan.cost().equals(plan.acquisition().get().cost()))) {
      invalid();
      return;
    }
    publish(
        Stage.PLANNED,
        null,
        plan,
        null,
        plan.reuse().isPresent()
            ? "A verified retained representation can be reused. Review its actual identity and"
                + " cost before attaching this build."
            : "Location ready. Review exact encoded cost before explicitly retaining bytes.",
        null,
        0,
        0);
  }

  public void cancel() {
    if (closed || !state.busy()) return;
    retire();
    publish(
        Stage.CANCELLED,
        null,
        null,
        null,
        "Your request was cancelled. Shared requests may continue; publication status is not"
            + " inferred.",
        null,
        0,
        0);
  }

  private <T> void run(
      Stage stage, String message, Supplier<CompletionStage<T>> action, Consumer<T> success) {
    retire();
    long request = generation;
    publish(stage, null, null, null, message, null, 0, 0);
    if (closed || request != generation) return;
    try {
      var future = Objects.requireNonNull(action.get()).toCompletableFuture();
      pending = future;
      future.whenComplete(
          (value, error) ->
              ui.execute(
                  () -> {
                    if (closed || request != generation) return;
                    pending = null;
                    if (error != null) fail(error);
                    else success.accept(value);
                  }));
    } catch (RuntimeException error) {
      if (request == generation) fail(error);
    }
  }

  private boolean available() {
    return !closed && !state.busy() && state.choice() != null;
  }

  private void invalid() {
    fail(new SelectedFileException(SelectedFileException.Code.INVALID_PLAN, "Scope mismatch"));
  }

  private void fail(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    var code =
        error instanceof SelectedFileException typed
            ? typed.code()
            : error instanceof CancellationException
                ? SelectedFileException.Code.CANCELLED
                : SelectedFileException.Code.STORAGE_UNAVAILABLE;
    String message =
        switch (code) {
          case BUSY ->
              "Another request is establishing a representation. Retry after it settles; no"
                  + " alternative was acquired.";
          case REPLAN_REQUIRED ->
              "This plan is no longer usable. Check representations again; duplicate retention"
                  + " remains blocked.";
          case RECOVERY_REQUIRED ->
              "An earlier publication needs recovery before another representation can be"
                  + " retained.";
          case INVALID_PLAN, INVALID_REQUEST ->
              "The exact selected scope or current plan could not be validated. Previous consent is"
                  + " cleared.";
          case INCOMPLETE_SEARCH -> "Search incomplete; availability is not established.";
          case BUDGET_EXCEEDED ->
              "The operation reached its approved limits. No fallback was started.";
          case CAPACITY_EXCEEDED -> "The app is busy. Retry after another operation finishes.";
          case MISSING_LOCAL -> "Retained bytes are missing. No verified-local claim remains.";
          case INTEGRITY_FAILURE -> "Bytes failed verification. No verified-local claim remains.";
          case UNSUPPORTED -> "This operation is not supported for the exact selected content.";
          case NOT_FOUND -> "No retained membership exists for this exact build and raw variant.";
          case UNAVAILABLE -> "The selected source is unavailable. No alternate route was started.";
          case CANCELLED -> "Your request was cancelled. Shared requests may continue.";
          case CLOSED -> "This operation is no longer available.";
          case STORAGE_UNAVAILABLE -> "App-owned content storage could not be accessed.";
        };
    publish(
        code == SelectedFileException.Code.CANCELLED ? Stage.CANCELLED : Stage.FAILED,
        null,
        null,
        null,
        message,
        code,
        0,
        0);
  }

  private void publish(
      Stage stage,
      Loose404Permit missing,
      SelectedFilePlan plan,
      SelectedFileAssociation receipt,
      String message,
      SelectedFileException.Code code,
      int checked,
      int advertised) {
    state =
        new State(
            state.choice(),
            state.locale(),
            stage,
            missing,
            plan,
            receipt,
            message,
            code,
            checked,
            advertised);
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
