package io.vibeminer.desktop;

import io.vibeminer.application.item.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.SelectedFileAssociation;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Retained-only bounded Item physical-row presentation; no acquisition or schema selection. */
public final class ItemPresentationModel implements AutoCloseable {
  public enum Stage {
    NO_SOURCE,
    UNSUPPORTED,
    UNPREPARED,
    PREPARING,
    READY,
    PAGING,
    LOOKING,
    FAILED,
    CANCELLED
  }

  public record State(
      BuildSnapshot build,
      String locale,
      SelectedFileAssociation source,
      Stage stage,
      PreparedItemProjection handle,
      ItemPage page,
      ItemLookupResult lookup,
      String input,
      ItemPhysicalRow selected,
      String mode,
      String message,
      ItemProjectionException.Code code) {
    public boolean busy() {
      return stage == Stage.PREPARING || stage == Stage.PAGING || stage == Stage.LOOKING;
    }
  }

  private final ItemProjectionService service;
  private final Executor ui;
  private final Consumer<State> observer;
  private final ItemProjectionLimits limits =
      new ItemProjectionLimits(128L << 20, 4 << 20, 100_000, 200_000, 8 << 20, 4 << 20);
  private BuildSnapshot build;
  private String locale = "enUS";
  private SelectedFileAssociation source;
  private Stage stage = Stage.NO_SOURCE;
  private PreparedItemProjection handle;
  private ItemPage page;
  private ItemLookupResult lookup;
  private String input = "", mode = "No query";
  private ItemPhysicalRow selected;
  private String message = "Choose an exact build and a verified retained Item encoded file.";
  private ItemProjectionException.Code code;
  private CompletableFuture<?> pending;
  private long generation;
  private boolean closed;

  public ItemPresentationModel(
      ItemProjectionService service, Executor ui, Consumer<State> observer) {
    this.service = Objects.requireNonNull(service);
    this.ui = Objects.requireNonNull(ui);
    this.observer = Objects.requireNonNull(observer);
  }

  public State state() {
    return new State(
        build, locale, source, stage, handle, page, lookup, input, selected, mode, message, code);
  }

  public ItemProjectionLimits limits() {
    return limits;
  }

  public int pageSize() {
    return 50;
  }

  public void selectContext(BuildSnapshot build, String locale, SelectedFileAssociation receipt) {
    Objects.requireNonNull(locale);
    var valid =
        build != null && receipt != null && receipt.snapshotId().equals(build.snapshotId())
            ? receipt
            : null;
    if (closed
        || Objects.equals(this.build, build)
            && this.locale.equals(locale)
            && Objects.equals(source, valid)) return;
    retire();
    this.build = build;
    this.locale = locale;
    source = valid;
    clearReady();
    input = "";
    mode = "No query";
    code = null;
    stage =
        build != null && !supportedContext()
            ? Stage.UNSUPPORTED
            : valid == null ? Stage.NO_SOURCE : eligible() ? Stage.UNPREPARED : Stage.UNSUPPORTED;
    message =
        stage == Stage.UNSUPPORTED
            ? "This exact build, locale or selected file is outside the admitted Item cohort. No"
                + " rows were prepared."
            : valid == null
                ? "No current verified retained Item source is selected. Inspect the exact retained"
                    + " file first."
                : stage == Stage.UNSUPPORTED
                    ? "This exact source is outside the admitted Item build, locale or file cohort."
                        + " No rows were prepared."
                    : "Supported encoded source retained. Physical rows are not prepared; no local"
                        + " work has started.";
    emit();
  }

  private boolean supportedContext() {
    return build != null
        && build.productId().equals("wow")
        && build.region().equals("us")
        && build.versionLabel().equals("12.1.5.70448")
        && locale.equals("enUS");
  }

  private boolean eligible() {
    if (source == null || build == null) return false;
    var a = source.descriptor().association();
    return build.productId().equals("wow")
        && build.region().equals("us")
        && build.versionLabel().equals("12.1.5.70448")
        && locale.equals("enUS")
        && a.fileDataId() == 841626
        && a.contentKey().equals("1df6b6dbae36878cea020a62b15b9bd6");
  }

  public void prepare() {
    if (closed || state().busy() || !eligible()) return;
    clearReady();
    var request = new ItemProjectionRequest(source.descriptor(), locale, limits);
    run(
        Stage.PREPARING,
        "Preparing supported physical rows offline. Copies and other sections are not indexed.",
        () -> service.prepare(request),
        result -> {
          if (result == null
              || !result.snapshotId().equals(build.snapshotId())
              || !result.sourceRole().equals(source.role())
              || !result.locale().equals(locale)
              || !result.evidence().association().equals(source)) {
            fail(
                new ItemProjectionException(
                    ItemProjectionException.Code.CORRUPT, "Context mismatch"));
            return;
          }
          if (result.evidence().localVerification().object().length() > limits.maxEncodedBytes()
              || result.definition().interpretation().sectionDecodedBytes()
                  > limits.maxSectionBytes()
              || result.coverage().physicalRows() > limits.maxPhysicalRows()
              || result.coverage().unindexedCopyDeclarations() > limits.maxCopyDeclarations()) {
            fail(
                new ItemProjectionException(
                    ItemProjectionException.Code.LIMIT_EXCEEDED,
                    "Returned preparation exceeded consent"));
            return;
          }
          handle = result;
          stage = Stage.READY;
          message = "Admitted physical rows ready. Choose a bounded page or exact Item record ID.";
          mode = "No query";
          emit();
        });
  }

  public void firstPage() {
    page(Optional.empty());
  }

  public void nextPage() {
    if (page != null && page.nextCursor().isPresent()) page(page.nextCursor());
  }

  private void page(Optional<ItemPageCursor> cursor) {
    if (!canQuery()) return;
    var current = handle;
    try {
      cursor.ifPresent(c -> c.requireContext(current, pageSize()));
    } catch (IllegalArgumentException e) {
      fail(new ItemProjectionException(ItemProjectionException.Code.STALE_CURSOR, "Cursor"));
      return;
    }
    page = null;
    lookup = null;
    selected = null;
    mode = "Browse physical rows · numeric Item record ID order";
    run(
        Stage.PAGING,
        "Reading a bounded physical-row page offline.",
        () -> service.page(current, cursor, pageSize()),
        result -> {
          if (result == null
              || !result.handle().equals(current)
              || result.rows().size() > pageSize()
              || result.rows().size() > current.coverage().physicalRows()
              || result.rows().isEmpty()
                  && (result.nextCursor().isPresent()
                      || cursor.isEmpty() && current.coverage().physicalRows() > 0)) {
            invalidResult();
            return;
          }
          long last = cursor.map(ItemPageCursor::afterRecordId).orElse(-1L);
          var physicalOrdinals = new HashSet<Integer>();
          for (var row : result.rows()) {
            if (!physicalOrdinals.add(row.physicalOrdinal())
                || row.recordId() <= last
                || row.sectionOrdinal() != current.coverage().sectionOrdinal()
                || row.physicalOrdinal() >= current.coverage().physicalRows()) {
              invalidResult();
              return;
            }
            last = row.recordId();
          }
          if (result.nextCursor().isPresent()) {
            try {
              result.nextCursor().get().requireContext(current, pageSize());
              if (result.nextCursor().get().afterRecordId() != last) {
                invalidResult();
                return;
              }
            } catch (IllegalArgumentException e) {
              invalidResult();
              return;
            }
          }
          page = result;
          stage = Stage.READY;
          message =
              result.rows().isEmpty()
                  ? "No rows in this bounded page of the admitted physical scope."
                  : result.rows().size()
                      + " physical rows in this page. Copies and other sections are not indexed.";
          emit();
        });
  }

  public void setInput(String input) {
    Objects.requireNonNull(input);
    if (closed || this.input.equals(input)) return;
    if (stage == Stage.PAGING || stage == Stage.LOOKING) {
      retire();
      stage = handle == null ? Stage.UNPREPARED : Stage.READY;
    }
    this.input = input;
    selected = null;
    code = null;
    message = "Draft Item record ID changed. No query was made.";
    if (page != null) mode = "Previous browse page · draft ID has not been queried";
    else if (lookup != null) mode = "Previous exact lookup · draft ID has not been queried";
    emit();
  }

  public void lookup() {
    if (!canQuery()) return;
    long id;
    try {
      if (!input.matches("[0-9]+")) throw new NumberFormatException();
      id = Long.parseLong(input);
      if (id < 1 || id > 0xffffffffL) throw new NumberFormatException();
    } catch (NumberFormatException invalid) {
      code = ItemProjectionException.Code.INVALID_REQUEST;
      message = "Enter one unsigned Item record ID from 1 to 4294967295. No query was made.";
      if (page != null) mode = "Previous browse page · invalid draft ID was not queried";
      emit();
      return;
    }
    var current = handle;
    lookup = null;
    page = null;
    selected = null;
    mode = "Exact Item record ID " + id;
    run(
        Stage.LOOKING,
        "Looking up this exact ID only in indexed physical rows.",
        () -> service.lookup(current, id),
        result -> {
          if (result == null
              || result.requestedId() != id
              || !result.coverage().equals(current.coverage())
              || result.outcome() == ItemLookupResult.Outcome.COPY_UNINDEXED
                  && current.coverage().unindexedCopyDeclarations() == 0) {
            invalidResult();
            return;
          }
          lookup = result;
          selected = result.row().orElse(null);
          stage = Stage.READY;
          message =
              switch (result.outcome()) {
                case PHYSICAL_MATCH -> "Physical row in the indexed section.";
                case COPY_UNINDEXED ->
                    "Copy declaration is not indexed in this projection. No physical candidate"
                        + " values are inferred.";
                case NO_PHYSICAL_MATCH ->
                    "No match in indexed physical rows. Copies and other sections are not covered.";
              };
          emit();
        });
  }

  public void selectRow(ItemPhysicalRow row) {
    if (closed
        || row != null
            && !(page != null && page.rows().contains(row)
                || lookup != null && lookup.row().filter(row::equals).isPresent())) return;
    selected = row;
    emit();
  }

  public void cancel() {
    if (closed || !state().busy()) return;
    boolean preparing = stage == Stage.PREPARING;
    retire();
    if (preparing) clearReady();
    else {
      page = null;
      lookup = null;
      selected = null;
    }
    stage = Stage.CANCELLED;
    code = null;
    message =
        "This request did not establish a new ready result. Shared work may continue; no automatic"
            + " repair or acquisition follows.";
    emit();
  }

  private boolean canQuery() {
    return !closed && !state().busy() && handle != null;
  }

  private void invalidResult() {
    fail(
        new ItemProjectionException(
            ItemProjectionException.Code.CORRUPT, "Returned scope mismatch"));
  }

  private <T> void run(
      Stage next, String message, Supplier<CompletionStage<T>> action, Consumer<T> success) {
    retire();
    long request = generation;
    stage = next;
    this.message = message;
    code = null;
    emit();
    if (closed || request != generation) return;
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
    } catch (RuntimeException e) {
      if (request == generation) fail(e);
    }
  }

  private void fail(Throwable e) {
    while ((e instanceof CompletionException || e instanceof ExecutionException)
        && e.getCause() != null) e = e.getCause();
    if (e instanceof CancellationException) {
      cancel();
      return;
    }
    code =
        e instanceof ItemProjectionException p
            ? p.code()
            : ItemProjectionException.Code.STORAGE_UNAVAILABLE;
    page = null;
    lookup = null;
    selected = null;
    if (Set.of(
            ItemProjectionException.Code.MISSING_LOCAL,
            ItemProjectionException.Code.CORRUPT,
            ItemProjectionException.Code.INCOMPATIBLE,
            ItemProjectionException.Code.UNSUPPORTED,
            ItemProjectionException.Code.NOT_READY)
        .contains(code)) handle = null;
    stage = Stage.FAILED;
    message =
        switch (code) {
          case INVALID_REQUEST -> "The Item query is invalid for this exact prepared scope.";
          case NOT_READY ->
              "The physical-row projection is not ready. Prepare supported rows explicitly.";
          case STALE_CURSOR ->
              "This page belongs to an older projection. Return to the first page.";
          case MISSING_LOCAL ->
              "Required retained evidence is missing. Current rows and readiness are cleared; no"
                  + " repair is started.";
          case CORRUPT ->
              "Evidence failed verification. Current rows and readiness are cleared; no repair is"
                  + " started.";
          case INCOMPATIBLE ->
              "Source layout or candidate definition is incompatible. No named fields are guessed.";
          case UNSUPPORTED ->
              "Projection is unavailable for this admitted scope or required storage mode.";
          case LIMIT_EXCEEDED ->
              "The local operation exceeded its stated bounds. No broader scope is prepared"
                  + " automatically.";
          case BUSY ->
              "The app is busy. Retry the explicit local action after another operation finishes.";
          case STORAGE_UNAVAILABLE ->
              "App-owned projection storage could not be accessed. No network repair is started.";
          case CLOSED -> "This projection service is no longer available.";
        };
    emit();
  }

  private void clearReady() {
    handle = null;
    page = null;
    lookup = null;
    selected = null;
  }

  private void retire() {
    generation++;
    if (pending != null) {
      var old = pending;
      pending = null;
      old.cancel(true);
    }
  }

  private void emit() {
    observer.accept(state());
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
  }
}
