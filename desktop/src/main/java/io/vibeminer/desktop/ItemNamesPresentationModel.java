package io.vibeminer.desktop;

import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.SelectedFileDescriptor;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Explicit retained-only paired names workspace; no source discovery, acquisition or owner close.
 */
public final class ItemNamesPresentationModel implements AutoCloseable {
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
      PreparedItemProjection item,
      SelectedFileDescriptor sparseSource,
      Stage stage,
      PreparedItemNames pair,
      PreparedItemNames historicalPair,
      ItemNamesPage page,
      ItemNamesLookup lookup,
      String input,
      NamedItemRow selected,
      String mode,
      String message,
      ItemNamesException.Code code) {
    public boolean busy() {
      return stage == Stage.PREPARING || stage == Stage.PAGING || stage == Stage.LOOKING;
    }
  }

  private final ItemNamesService service;
  private final Executor ui;
  private final Consumer<State> observer;
  private final SparseNameLimits limits =
      new SparseNameLimits(
          16L << 20,
          131072,
          4096,
          4 << 20,
          4 << 20,
          131072,
          4 << 20,
          200000,
          1000,
          64L << 20,
          65535,
          16384,
          65535,
          32L << 20,
          128L << 20,
          100);
  private BuildSnapshot build;
  private String locale = "enUS";
  private PreparedItemProjection item;
  private SelectedFileDescriptor sparseSource;
  private Stage stage = Stage.NO_SOURCE;
  private PreparedItemNames pair, historicalPair;
  private ItemNamesPage page;
  private ItemNamesLookup lookup;
  private String input = "",
      mode = "No query",
      message = "Choose a prepared Item source and a verified retained Sparse source.";
  private NamedItemRow selected;
  private ItemNamesException.Code code;
  private CompletableFuture<?> pending;
  private long generation;
  private boolean closed;

  public ItemNamesPresentationModel(
      ItemNamesService service, Executor ui, Consumer<State> observer) {
    this.service = Objects.requireNonNull(service);
    this.ui = Objects.requireNonNull(ui);
    this.observer = Objects.requireNonNull(observer);
  }

  public State state() {
    return new State(
        build,
        locale,
        item,
        sparseSource,
        stage,
        pair,
        historicalPair,
        page,
        lookup,
        input,
        selected,
        mode,
        message,
        code);
  }

  public SparseNameLimits limits() {
    return limits;
  }

  public int pageSize() {
    return 50;
  }

  public void selectContext(
      BuildSnapshot b, String locale, PreparedItemProjection item, SelectedFileDescriptor sparse) {
    Objects.requireNonNull(locale);
    if (closed
        || Objects.equals(build, b)
            && this.locale.equals(locale)
            && Objects.equals(this.item, item)
            && Objects.equals(sparseSource, sparse)) return;
    retire();
    build = b;
    this.locale = locale;
    this.item = item;
    this.sparseSource = sparse;
    clearReady();
    historicalPair = null;
    input = "";
    mode = "No query";
    code = null;
    stage =
        build == null
            ? Stage.NO_SOURCE
            : !supportedContext()
                ? Stage.UNSUPPORTED
                : item == null || sparse == null
                    ? Stage.NO_SOURCE
                    : eligible() ? Stage.UNPREPARED : Stage.UNSUPPORTED;
    message =
        stage == Stage.NO_SOURCE
            ? "A prepared Item source and separately verified retained Sparse source are required."
                + " No work has started."
            : stage == Stage.UNSUPPORTED
                ? "This exact source pair, build or locale is outside the admitted names cohort."
                : "Source pair selected. Names are not prepared; no local work has started.";
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
    if (!supportedContext() || item == null || sparseSource == null) return false;
    var a = item.evidence().association().descriptor().association();
    var s = sparseSource.association();
    return item.snapshotId().equals(build.snapshotId())
        && item.locale().equals(locale)
        && s.build().snapshotId().equals(build.snapshotId())
        && a.fileDataId() == 841626
        && a.contentKey().equals("1df6b6dbae36878cea020a62b15b9bd6")
        && s.fileDataId() == 1572924
        && s.contentKey().equals("bb0ecbdc2fd4988ddd3c1c5e95c34165")
        && (s.localeFlags() & 2) != 0;
  }

  public void prepare() {
    if (closed || state().busy() || !eligible()) return;
    clearReady();
    var request = new ItemNamesRequest(item, sparseSource, locale, limits);
    run(
        Stage.PREPARING,
        "Preparing name candidates locally for the explicit source pair. No acquisition starts.",
        () -> service.prepare(request),
        result -> {
          if (!validPrepared(result, request)) {
            invalid();
            return;
          }
          pair = result;
          historicalPair = result;
          stage = Stage.READY;
          mode = "No query";
          message = "Paired physical scope ready. Choose Browse or an exact Item record ID.";
          emit();
        });
  }

  private boolean validPrepared(PreparedItemNames p, ItemNamesRequest r) {
    if (p == null
        || !p.snapshotId().equals(build.snapshotId())
        || !p.locale().equals(locale)
        || !p.names().evidence().association().descriptor().equals(r.sparseSource())
        || !sameItemProof(r.item(), p.item())) return false;
    var n = p.names();
    if (n.evidence().chunks().size() > limits.maxChunks()) return false;
    long decodedWork = 0;
    for (var chunk : n.evidence().chunks()) {
      if (chunk.encodedBytes() > limits.maxChunkEncodedBytes()
          || chunk.decodedBytes() > limits.maxChunkDecodedBytes()
          || chunk.decodedBytes() > limits.maxDecodedWorkBytes() - decodedWork) return false;
      decodedWork += chunk.decodedBytes();
    }
    var i = n.definition().interpretation();
    var c = n.coverage();
    return n.evidence().localVerification().object().length() <= limits.maxEncodedBytes()
        && i.metadataPrefixBytes() <= limits.maxMetadataBytes()
        && i.suffixBytes() <= limits.maxSuffixBytes()
        && i.recordAreaBytes() <= limits.maxRecordAreaBytes()
        && c.physicalRows() <= limits.maxPhysicalRows()
        && c.unindexedCopyDeclarations() <= limits.maxCopyDeclarations()
        && c.projectedUtf8Bytes() <= limits.maxProjectedUtf8Bytes();
  }

  private static boolean sameItemProof(PreparedItemProjection a, PreparedItemProjection b) {
    var x = a.evidence();
    var y = b.evidence();
    return a.definition().equals(b.definition())
        && a.coverage().equals(b.coverage())
        && x.association().equals(y.association())
        && x.chunks().equals(y.chunks())
        && x.windows().equals(y.windows())
        && x.fullDecoded().equals(y.fullDecoded())
        && x.localVerification().object().equals(y.localVerification().object())
        && x.localVerification()
            .verifierRevision()
            .equals(y.localVerification().verifierRevision());
  }

  public void firstPage() {
    page(Optional.empty());
  }

  public void nextPage() {
    if (page != null && page.nextCursor().isPresent()) page(page.nextCursor());
  }

  private void page(Optional<ItemNamesCursor> cursor) {
    if (!canQuery()) return;
    var current = pair;
    try {
      cursor.ifPresent(c -> c.requireContext(current, pageSize()));
    } catch (IllegalArgumentException bad) {
      fail(new ItemNamesException(ItemNamesException.Code.STALE_CURSOR, "Cursor"));
      return;
    }
    clearResults();
    mode = "Browse physical Item rows · numeric Item ID order";
    run(
        Stage.PAGING,
        "Reading a bounded joined page offline.",
        () -> service.page(current, cursor, pageSize()),
        result -> {
          if (!validPage(result, current, cursor)) {
            invalid();
            return;
          }
          page = result;
          stage = Stage.READY;
          message =
              result.rows().size()
                  + " physical Item rows in this page. Names are candidates in the paired admitted"
                  + " scope; copies and other sections remain unindexed.";
          emit();
        });
  }

  private boolean validPage(
      ItemNamesPage p, PreparedItemNames current, Optional<ItemNamesCursor> cursor) {
    if (p == null
        || !p.pair().equals(current)
        || p.rows().size() > pageSize()
        || p.rows().size() > current.item().coverage().physicalRows()
        || p.rows().isEmpty()
            && (p.nextCursor().isPresent()
                || cursor.isEmpty() && current.item().coverage().physicalRows() > 0)) return false;
    long previous = cursor.map(ItemNamesCursor::afterRecordId).orElse(0L);
    var items = new HashSet<Integer>();
    var names = new HashSet<Integer>();
    try {
      for (var row : p.rows()) {
        current.requireRow(row);
        if (row.item().recordId() <= previous
            || !items.add(row.item().physicalOrdinal())
            || !validName(row.name())) return false;
        if (row.name().candidate().isPresent()
            && !names.add(row.name().candidate().get().physicalOrdinal())) return false;
        previous = row.item().recordId();
      }
      if (p.nextCursor().isPresent()) {
        var next = p.nextCursor().get();
        next.requireContext(current, pageSize());
        if (p.rows().size() != pageSize() || next.afterRecordId() != previous) return false;
      }
      return true;
    } catch (IllegalArgumentException bad) {
      return false;
    }
  }

  private boolean validName(ItemNameMatch match) {
    return match
        .candidate()
        .map(
            c ->
                c.prefixBytes() <= limits.maxPrefixBytes()
                    && c.recordBytes() <= limits.maxRecordBytes()
                    && c.utf8Bytes() <= limits.maxStringBytes())
        .orElse(true);
  }

  public void setInput(String value) {
    Objects.requireNonNull(value);
    if (closed || input.equals(value)) return;
    if (stage == Stage.PAGING || stage == Stage.LOOKING) {
      retire();
      stage = pair == null ? Stage.UNPREPARED : Stage.READY;
    }
    input = value;
    selected = null;
    if (pair == null && stage == Stage.FAILED) {
      emit();
      return;
    }
    code = null;
    message = "Draft Item record ID changed. No query was made.";
    if (page != null) mode = "Previous browse page · draft ID not queried";
    else if (lookup != null) mode = "Previous exact lookup · draft ID not queried";
    emit();
  }

  public void lookup() {
    if (!canQuery()) return;
    long id;
    try {
      if (!input.matches("[0-9]+")) throw new NumberFormatException();
      id = Long.parseLong(input);
      if (id < 1 || id > 0xffffffffL) throw new NumberFormatException();
    } catch (NumberFormatException bad) {
      code = ItemNamesException.Code.INVALID_REQUEST;
      message = "Enter an Item record ID from 1 to 4294967295. No query was made.";
      if (page != null || lookup != null) mode = "Previous result · invalid draft not queried";
      emit();
      return;
    }
    var current = pair;
    clearResults();
    mode = "Exact Item record ID " + id;
    run(
        Stage.LOOKING,
        "Looking up one Item ID in the paired admitted physical scope.",
        () -> service.lookup(current, id),
        result -> {
          if (!validLookup(result, current, id)) {
            invalid();
            return;
          }
          lookup = result;
          stage = Stage.READY;
          var joined = result.result();
          if (joined.item().outcome() == ItemLookupResult.Outcome.PHYSICAL_MATCH) {
            selected =
                new NamedItemRow(joined.item().row().orElseThrow(), joined.name().orElseThrow());
            message = nameStatus(selected.name());
          } else
            message =
                joined.item().outcome() == ItemLookupResult.Outcome.COPY_UNINDEXED
                    ? "Item copy declaration is unindexed. No physical Item anchor or name lookup"
                        + " is implied."
                    : "No physical Item match in the admitted Item scope. This does not establish"
                        + " absence from the whole table or build.";
          emit();
        });
  }

  private boolean validLookup(ItemNamesLookup result, PreparedItemNames current, long id) {
    if (result == null
        || !result.pair().equals(current)
        || result.result().item().requestedId() != id) return false;
    try {
      current.requireLookup(result.result());
      return result.result().name().map(this::validName).orElse(true);
    } catch (IllegalArgumentException bad) {
      return false;
    }
  }

  public static String nameStatus(ItemNameMatch match) {
    return switch (match.outcome()) {
      case PHYSICAL_NAME ->
          match.candidate().orElseThrow().value().isEmpty()
              ? "Empty name candidate"
              : "Physical Display candidate";
      case COPY_UNINDEXED -> "Name copy declaration unindexed";
      case NO_PHYSICAL_MATCH -> "No physical name match in the paired admitted scope";
    };
  }

  public void selectRow(NamedItemRow row) {
    if (closed || pair == null || page == null || !page.rows().contains(row)) return;
    selected = row;
    emit();
  }

  public void invalidateDependency(String snapshotId) {
    if (closed || build == null || !build.snapshotId().equals(snapshotId)) return;
    retire();
    clearReady();
    stage = Stage.FAILED;
    code = ItemNamesException.Code.NOT_READY;
    message =
        "Required source-pair evidence needs re-verification. Joined readiness is cleared; no"
            + " repair or acquisition starts.";
    emit();
  }

  public void cancel() {
    if (closed || !state().busy()) return;
    retire();
    clearResults();
    stage = Stage.CANCELLED;
    code = null;
    message =
        "Local request cancelled. Independent Item data is unchanged; no new paired result is"
            + " claimed.";
    emit();
  }

  private boolean canQuery() {
    return !closed && !state().busy() && pair != null;
  }

  private <T> void run(
      Stage next, String text, Supplier<CompletionStage<T>> action, Consumer<T> success) {
    retire();
    long request = generation;
    stage = next;
    message = text;
    code = null;
    emit();
    if (closed || generation != request) return;
    try {
      var f = Objects.requireNonNull(action.get()).toCompletableFuture();
      pending = f;
      f.whenComplete(
          (value, error) ->
              ui.execute(
                  () -> {
                    if (closed || generation != request) return;
                    pending = null;
                    if (error != null) fail(error);
                    else success.accept(value);
                  }));
    } catch (RuntimeException error) {
      if (!closed && generation == request) fail(error);
    }
  }

  private void invalid() {
    fail(
        new ItemNamesException(
            ItemNamesException.Code.CORRUPT, "Returned source pair or scope changed"));
  }

  private void fail(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    clearResults();
    if (error instanceof CancellationException) {
      stage = Stage.CANCELLED;
      code = null;
      message = "Local request cancelled. No new paired result is claimed.";
      emit();
      return;
    }
    code =
        error instanceof ItemNamesException typed
            ? typed.code()
            : ItemNamesException.Code.STORAGE_UNAVAILABLE;
    if (code == ItemNamesException.Code.MISSING_LOCAL
        || code == ItemNamesException.Code.CORRUPT
        || code == ItemNamesException.Code.NOT_READY
        || code == ItemNamesException.Code.INCOMPATIBLE
        || code == ItemNamesException.Code.UNSUPPORTED
        || code == ItemNamesException.Code.STALE_CURSOR) pair = null;
    stage = Stage.FAILED;
    message =
        switch (code) {
          case MISSING_LOCAL ->
              "Source-pair evidence is missing locally. Joined readiness is cleared; inspect the"
                  + " retained sources explicitly. No acquisition starts.";
          case CORRUPT ->
              "Source-pair evidence failed verification. Joined readiness is cleared; the failing"
                  + " side is not identified. No repair starts.";
          case NOT_READY ->
              "This source pair is no longer prepared. Verify both sources and prepare names"
                  + " explicitly.";
          case STALE_CURSOR ->
              "The page belongs to another source pair or revision. Prepare the current pair and"
                  + " browse from the first page.";
          case UNSUPPORTED, INCOMPATIBLE ->
              "This exact pair or interpretation is unsupported. Existing Item data is unchanged.";
          case LIMIT_EXCEEDED ->
              "Local preparation exceeded the disclosed work limits. No new paired result is"
                  + " ready.";
          case BUSY ->
              "The application is busy. Retry the explicit local action when capacity is"
                  + " available.";
          case INVALID_REQUEST ->
              "The source pair or request is invalid. Check the exact context before retrying.";
          case CLOSED ->
              "The local names service is closed. Reopen the application before retrying.";
          case STORAGE_UNAVAILABLE ->
              "Local names storage is unavailable. No new paired result is claimed.";
        };
    emit();
  }

  private void clearResults() {
    page = null;
    lookup = null;
    selected = null;
  }

  private void clearReady() {
    pair = null;
    clearResults();
  }

  private void retire() {
    generation++;
    var p = pending;
    pending = null;
    if (p != null) p.cancel(true);
  }

  private void emit() {
    observer.accept(state());
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
    clearReady();
  }
}
