package io.vibeminer.desktop;

import io.vibeminer.application.buildlibrary.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Presentation-thread-confined binding for the authoritative grouped library, not a discovery
 * backend.
 */
public final class GuidedDiscoveryPresentationModel implements AutoCloseable {
  public record OpenAdmission(boolean enabled, String reason) {
    public OpenAdmission {
      Objects.requireNonNull(reason);
    }
  }

  public record State(
      List<GuidedDiscoveryPane.ProductOption> products,
      String productId,
      GuidedDiscoveryPane.Filter filter,
      BuildLibraryPage page,
      BuildLibraryPage.Entry selected,
      boolean busy,
      boolean selecting,
      String message,
      String error) {
    public State {
      products = List.copyOf(products);
      Objects.requireNonNull(filter);
      Objects.requireNonNull(message);
      Objects.requireNonNull(error);
    }
  }

  private final BuildLibraryService service;
  private final Executor presentation;
  private final Consumer<State> observer;
  private final Function<BuildLibraryPage.Entry, OpenAdmission> admission;
  private final Consumer<BuildLibraryPage.Entry> open;
  private final Consumer<BuildLibraryPage.Entry> source;
  private State state;
  private long generation, selectionGeneration;
  private boolean closed;
  private BuildLibraryService.RefreshOperation refreshing;
  private BuildLibraryService.LocalPreparationOperation preparing;
  private final Map<String, LegacyBuildObservationImporter.Cursor> localCursors =
      new LinkedHashMap<>();
  private String migrationIssue = "";
  private Throwable migrationFailure;

  /** The typed entry migration issue is retained separately from remote-check diagnostics. */
  public Optional<Throwable> localPreparationFailure() {
    return Optional.ofNullable(migrationFailure);
  }

  public GuidedDiscoveryPresentationModel(
      BuildLibraryService service,
      List<GuidedDiscoveryPane.ProductOption> products,
      Executor presentation,
      Consumer<State> observer,
      Function<BuildLibraryPage.Entry, OpenAdmission> admission,
      Consumer<BuildLibraryPage.Entry> open,
      Consumer<BuildLibraryPage.Entry> source) {
    this.service = Objects.requireNonNull(service);
    this.presentation = Objects.requireNonNull(presentation);
    this.observer = Objects.requireNonNull(observer);
    this.admission = Objects.requireNonNull(admission);
    this.open = Objects.requireNonNull(open);
    this.source = Objects.requireNonNull(source);
    products = checkedProducts(products);
    state =
        new State(
            products,
            null,
            GuidedDiscoveryPane.Filter.ALL,
            null,
            null,
            false,
            false,
            "Choose a Warcraft product to begin.",
            "");
  }

  private static List<GuidedDiscoveryPane.ProductOption> checkedProducts(
      List<GuidedDiscoveryPane.ProductOption> products) {
    products = List.copyOf(products);
    var ids = new HashSet<String>();
    for (var product : products)
      if (!ids.add(product.productId()))
        throw new IllegalArgumentException("Duplicate reviewed product");
    return products;
  }

  /** Replaces supplied presentation aliases without requesting any network or local read. */
  public void setProducts(List<GuidedDiscoveryPane.ProductOption> products) {
    if (closed) return;
    products = checkedProducts(products);
    var suppliedIds = new HashSet<String>();
    for (var product : products) suppliedIds.add(product.productId());
    localCursors.keySet().retainAll(suppliedIds);
    if (state.productId() != null
        && products.stream().noneMatch(p -> p.productId().equals(state.productId()))) {
      retire();
      publish(
          new State(
              products,
              null,
              GuidedDiscoveryPane.Filter.ALL,
              null,
              null,
              false,
              false,
              "Choose a Warcraft product to begin.",
              ""));
    } else
      publish(
          new State(
              products,
              state.productId(),
              state.filter(),
              state.page(),
              state.selected(),
              state.busy(),
              state.selecting(),
              state.message(),
              state.error()));
  }

  public State state() {
    return state;
  }

  /** Restores local state only. It does not refresh current publication pointers. */
  public void restore(String product, GuidedDiscoveryPane.Filter filter) {
    if (closed) return;
    Objects.requireNonNull(filter);
    if (!knownProduct(product)) {
      invalidProduct();
      return;
    }
    beginEntry(product, filter, false);
  }

  private void beginEntry(
      String product, GuidedDiscoveryPane.Filter filter, boolean remoteAfterPage) {
    beginEntry(product, filter, remoteAfterPage, false);
  }

  private void beginEntry(
      String product,
      GuidedDiscoveryPane.Filter filter,
      boolean remoteAfterPage,
      boolean explicitRemoteRetry) {
    long request = retire();
    migrationIssue = "";
    migrationFailure = null;
    var prior = product.equals(state.productId()) ? state.page() : null;
    publish(
        new State(
            state.products(),
            product,
            filter,
            prior,
            null,
            true,
            false,
            "Preparing saved local build observations…",
            ""));
    if (!current(request)) return;
    try {
      var operation =
          Objects.requireNonNull(
              service.prepareLocal(product, Optional.ofNullable(localCursors.get(product))));
      if (!current(request)) {
        operation.requestCancellation();
        return;
      }
      preparing = operation;
      Objects.requireNonNull(operation.completion())
          .whenComplete(
              (result, failure) ->
                  presentation.execute(
                      () -> {
                        if (!current(request)) return;
                        preparing = null;
                        Throwable cause = unwrap(failure);
                        if (cause instanceof UnsupportedOperationException) {
                          migrationFailure = cause;
                          migrationIssue =
                              "Older local builds have not been imported by this library service.";
                          // Compatibility harnesses cannot migrate; keep their existing explicit
                          // refresh usable.
                          if (remoteAfterPage) beginRefresh(product, true);
                          else
                            readPage(
                                request,
                                Optional.empty(),
                                "Showing saved build observations. Publication has not been"
                                    + " refreshed.",
                                "");
                          return;
                        }
                        boolean cancelled = cause instanceof CancellationException;
                        if (cause == null
                            && (result == null || !result.progress().productId().equals(product)))
                          cause =
                              new BuildLibraryException(
                                  BuildLibraryException.Code.CORRUPT_LIBRARY,
                                  "Local preparation returned another product");
                        if (cause == null) {
                          rememberProgress(product, result.progress());
                          cancelled = result.cancellationRequested();
                          if (!result.progress().complete())
                            migrationIssue =
                                "Older local build import is incomplete. Retry the local"
                                    + " preparation to continue.";
                          if (cancelled)
                            migrationIssue =
                                "Local preparation stopped. Saved progress remains available.";
                        } else {
                          migrationFailure = cause;
                          if (cause instanceof LegacyBuildImportException typed)
                            rememberProgress(product, typed.partialProgress());
                          if (cause instanceof LegacyBuildImportCancelled typed)
                            rememberProgress(product, typed.partialProgress());
                          migrationIssue =
                              cancelled
                                  ? "Local preparation stopped. Saved progress remains available."
                                  : "Some older local builds could not be imported. Saved"
                                      + " observations remain available.";
                        }
                        boolean refreshAfter =
                            remoteAfterPage && !cancelled && (cause == null || explicitRemoteRetry);
                        readPage(
                            request,
                            Optional.empty(),
                            "Showing saved local build observations.",
                            "",
                            refreshAfter ? () -> beginRefresh(product, true) : () -> {});
                      }));
    } catch (RuntimeException failure) {
      if (!current(request)) return;
      preparing = null;
      Throwable cause = unwrap(failure);
      migrationFailure = cause;
      if (cause instanceof LegacyBuildImportException typed)
        rememberProgress(product, typed.partialProgress());
      if (cause instanceof LegacyBuildImportCancelled typed)
        rememberProgress(product, typed.partialProgress());
      boolean cancelled = cause instanceof CancellationException;
      migrationIssue =
          cancelled
              ? "Local preparation stopped. Saved progress remains available."
              : "Older local builds could not be imported. Saved observations remain available.";
      readPage(
          request,
          Optional.empty(),
          "Showing saved local build observations.",
          "",
          remoteAfterPage && explicitRemoteRetry && !cancelled
              ? () -> beginRefresh(product, true)
              : () -> {});
    }
  }

  private void rememberProgress(String product, LegacyBuildObservationImporter.Batch progress) {
    if (!product.equals(progress.productId())) return;
    if (progress.complete()) localCursors.remove(product);
    else
      progress
          .nextCursor()
          .ifPresent(
              cursor -> {
                if (!localCursors.containsKey(product) && localCursors.size() == 64)
                  localCursors.remove(localCursors.keySet().iterator().next());
                localCursors.put(product, cursor);
              });
  }

  private static Throwable unwrap(Throwable failure) {
    while ((failure instanceof CompletionException || failure instanceof ExecutionException)
        && failure.getCause() != null) failure = failure.getCause();
    return failure;
  }

  /** Only a deliberate changed product selection initiates automatic refresh. */
  public void selectProduct(String product) {
    if (closed || Objects.equals(product, state.productId())) return;
    if (!knownProduct(product)) {
      invalidProduct();
      return;
    }
    beginEntry(product, GuidedDiscoveryPane.Filter.ALL, true);
  }

  public void refresh() {
    if (!closed && state.productId() != null && !state.busy()) {
      if (localCursors.containsKey(state.productId())
          || (migrationFailure != null
              && !(migrationFailure instanceof UnsupportedOperationException)))
        beginEntry(state.productId(), state.filter(), true, true);
      else beginRefresh(state.productId(), true);
    }
  }

  public void reload() {
    if (closed || state.productId() == null) return;
    long request = retire();
    publish(
        new State(
            state.products(),
            state.productId(),
            state.filter(),
            state.page(),
            null,
            true,
            false,
            "Reading saved build observations…",
            ""));
    if (current(request))
      readPage(request, Optional.empty(), "Saved build observations loaded.", "");
  }

  public void filter(GuidedDiscoveryPane.Filter filter) {
    Objects.requireNonNull(filter);
    if (closed || state.productId() == null || filter == state.filter()) return;
    long request = retire();
    publish(
        new State(
            state.products(),
            state.productId(),
            filter,
            null,
            null,
            true,
            false,
            "Reading saved build observations…",
            ""));
    if (current(request))
      readPage(request, Optional.empty(), "Saved build observations loaded.", "");
  }

  /**
   * Bounded next-page navigation; it never refreshes publication or accumulates an unbounded list.
   */
  public void more() {
    if (closed || state.busy() || state.page() == null || state.page().nextCursor().isEmpty())
      return;
    var cursor = state.page().nextCursor();
    long request = retire();
    publish(
        new State(
            state.products(),
            state.productId(),
            state.filter(),
            state.page(),
            null,
            true,
            false,
            "Reading the next saved page…",
            ""));
    if (current(request)) readPage(request, cursor, "Next saved page loaded.", "");
  }

  private void beginRefresh(String product, boolean preservePage) {
    long request = retire();
    var prior = preservePage ? state.page() : null;
    publish(
        new State(
            state.products(),
            product,
            preservePage ? state.filter() : GuidedDiscoveryPane.Filter.ALL,
            prior,
            null,
            true,
            false,
            "Checking official product metadata. No game content is being downloaded.",
            ""));
    if (!current(request)) return;
    try {
      var operation = Objects.requireNonNull(service.refreshProduct(product));
      if (!current(request)) {
        cancel(operation);
        return;
      }
      refreshing = operation;
      Objects.requireNonNull(operation.completion())
          .whenComplete(
              (result, failure) ->
                  presentation.execute(
                      () -> {
                        if (!current(request)) return;
                        refreshing = null;
                        if (failure != null
                            || result == null
                            || !product.equals(result.ticket().productId())) {
                          readPage(
                              request,
                              Optional.empty(),
                              "Showing saved build observations.",
                              "Official metadata could not be refreshed. Prior observations remain"
                                  + " available.");
                          return;
                        }
                        boolean incomplete =
                            !result.failures().isEmpty()
                                || !result.observation().failures().isEmpty()
                                || result.observation().diagnostic().isPresent();
                        String message =
                            result.cancellationRequested()
                                ? "Refresh stopped. Committed observations remain available."
                                : "Saved observations loaded after the official metadata check.";
                        String error =
                            incomplete
                                ? "Some regional checks did not complete. Prior regional"
                                    + " observations are preserved."
                                : "";
                        readPage(request, Optional.empty(), message, error);
                      }));
    } catch (RuntimeException failure) {
      if (current(request)) {
        refreshing = null;
        readPage(
            request,
            Optional.empty(),
            "Showing saved build observations.",
            "Official metadata could not be refreshed. Prior observations remain available.");
      }
    }
  }

  private void readPage(
      long request, Optional<BuildLibraryCursor> cursor, String message, String error) {
    readPage(request, cursor, message, error, () -> {});
  }

  private void readPage(
      long request,
      Optional<BuildLibraryCursor> cursor,
      String message,
      String error,
      Runnable afterPage) {
    String combinedError =
        migrationIssue.isBlank()
            ? error
            : error.isBlank() ? migrationIssue : migrationIssue + " " + error;
    if (!current(request)) return;
    var query =
        new BuildLibraryQuery(
            state.productId(), BuildLibraryProjection.Filter.valueOf(state.filter().name()));
    try {
      service
          .page(query, cursor, 25)
          .whenComplete(
              (page, failure) ->
                  presentation.execute(
                      () -> {
                        if (!current(request)) return;
                        if (failure != null
                            || page == null
                            || !query.equals(page.query())
                            || page.pageSize() != 25) {
                          pageFailure(failure, combinedError);
                          return;
                        }
                        if (cursor.isPresent()
                            && cursor.orElseThrow().libraryRevision() != page.libraryRevision()) {
                          pageFailure(
                              new BuildLibraryException(
                                  BuildLibraryException.Code.STALE_CURSOR,
                                  "Page changed the admitted library revision"),
                              combinedError);
                          return;
                        }
                        selectionGeneration++;
                        publish(
                            new State(
                                state.products(),
                                state.productId(),
                                state.filter(),
                                page,
                                null,
                                false,
                                false,
                                message,
                                combinedError));
                        if (current(request)) afterPage.run();
                      }));
    } catch (RuntimeException failure) {
      if (current(request)) pageFailure(failure, combinedError);
    }
  }

  private void pageFailure(Throwable failure, String priorError) {
    String explanation =
        code(failure) == BuildLibraryException.Code.STALE_CURSOR
            ? "The local list changed. Reload the saved list before continuing."
            : "Saved build observations could not be read. Retry the local list.";
    publish(
        new State(
            state.products(),
            state.productId(),
            state.filter(),
            state.page(),
            null,
            false,
            false,
            "Prior loaded rows are unchanged.",
            priorError.isBlank() ? explanation : priorError + " " + explanation));
  }

  public void select(String groupId) {
    if (closed || groupId == null || state.page() == null) return;
    var candidate =
        state.page().entries().stream()
            .filter(e -> e.row().group().key().identity().equals(groupId))
            .findFirst()
            .orElse(null);
    if (candidate == null) return;
    long request = generation, selection = ++selectionGeneration;
    publish(
        new State(
            state.products(),
            state.productId(),
            state.filter(),
            state.page(),
            null,
            state.busy(),
            true,
            "Reading exact build details…",
            state.error()));
    if (!selectionCurrent(request, selection)) return;
    try {
      service
          .select(groupId)
          .whenComplete(
              (entry, failure) ->
                  presentation.execute(
                      () -> {
                        if (!selectionCurrent(request, selection)) return;
                        if (failure != null
                            || entry == null
                            || !candidate.row().group().key().equals(entry.row().group().key())) {
                          publish(
                              new State(
                                  state.products(),
                                  state.productId(),
                                  state.filter(),
                                  state.page(),
                                  null,
                                  state.busy(),
                                  false,
                                  "Select a build to inspect it.",
                                  "This exact build could not be read. Reload the local list; no"
                                      + " replacement was selected."));
                          return;
                        }
                        publish(
                            new State(
                                state.products(),
                                state.productId(),
                                state.filter(),
                                state.page(),
                                entry,
                                state.busy(),
                                false,
                                "Exact build selected. Opening is a separate action.",
                                state.error()));
                      }));
    } catch (RuntimeException failure) {
      if (selectionCurrent(request, selection))
        publish(
            new State(
                state.products(),
                state.productId(),
                state.filter(),
                state.page(),
                null,
                state.busy(),
                false,
                "Select a build to inspect it.",
                "This exact build could not be read. Reload the local list."));
    }
  }

  public void openSelected() {
    if (closed || state.busy() || state.selecting() || state.selected() == null) return;
    var selected = state.selected();
    if (admission.apply(selected).enabled()) open.accept(selected);
  }

  public void sourceDetails() {
    if (!closed && !state.selecting() && state.selected() != null) source.accept(state.selected());
  }

  public GuidedDiscoveryPane.ViewData viewData() {
    var rows = state.page() == null ? List.<BuildLibraryPage.Entry>of() : state.page().entries();
    return new GuidedDiscoveryPane.ViewData(
        state.products(),
        state.productId(),
        rows.stream()
            .map(
                entry ->
                    display(
                        state.selected() != null
                                && state
                                    .selected()
                                    .row()
                                    .group()
                                    .key()
                                    .equals(entry.row().group().key())
                            ? state.selected()
                            : entry))
            .toList(),
        state.selected() == null ? null : state.selected().row().group().key().identity(),
        state.filter(),
        state.busy() || state.selecting(),
        state.productId() != null,
        state.page() != null && state.page().nextCursor().isPresent(),
        state.message(),
        state.error());
  }

  private GuidedDiscoveryPane.BuildRow display(BuildLibraryPage.Entry entry) {
    var row = entry.row();
    var group = row.group();
    var key = group.key();
    var preferred = group.preferredReference();
    var allowed = admission.apply(entry);
    var details =
        List.of(
            new GuidedDiscoveryPane.Detail(
                "PRODUCT / SOURCE CHANNEL", key.productId() + " / " + key.channel()),
            new GuidedDiscoveryPane.Detail("BUILD CONFIGURATION", key.buildConfig()),
            new GuidedDiscoveryPane.Detail("CDN CONFIGURATION", key.cdnConfig()),
            new GuidedDiscoveryPane.Detail("UPSTREAM BUILD ID", key.upstreamBuildId()),
            new GuidedDiscoveryPane.Detail(
                "PRODUCT CONFIGURATION",
                key.productConfig().isEmpty() ? "Not declared" : key.productConfig()),
            new GuidedDiscoveryPane.Detail(
                "PREFERRED METADATA REFERENCE",
                preferred.region() + " · " + preferred.snapshotId()),
            new GuidedDiscoveryPane.Detail(
                "FIRST SEEN BY VIBEMINER", row.seen().firstSeenAt().toString()),
            new GuidedDiscoveryPane.Detail(
                "PUBLICATION CHECK",
                switch (row.advertisement()) {
                  case PRESENT_IN_CHECKED_SCOPE -> "Present in checked regional metadata";
                  case ABSENT_FROM_CHECKED_SCOPE -> "Absent from the successfully checked scope";
                  case UNKNOWN -> "Unknown or incomplete regional check";
                }));
    return new GuidedDiscoveryPane.BuildRow(
        key.identity(),
        key.versionLabel(),
        group.references().stream().map(BuildLibraryReference::region).distinct().sorted().toList(),
        "Readiness not assessed",
        row.local().isLocal(),
        row.advertisement() == BuildLibraryProjection.Advertisement.PRESENT_IN_CHECKED_SCOPE,
        row.seen().isNew(),
        details,
        allowed.enabled(),
        allowed.reason());
  }

  private boolean knownProduct(String product) {
    return product != null
        && state.products().stream().anyMatch(p -> p.productId().equals(product));
  }

  private void invalidProduct() {
    retire();
    publish(
        new State(
            state.products(),
            null,
            GuidedDiscoveryPane.Filter.ALL,
            null,
            null,
            false,
            false,
            "Choose a Warcraft product to begin.",
            "Choose a supplied Warcraft product identifier."));
  }

  private long retire() {
    generation++;
    selectionGeneration++;
    var local = preparing;
    preparing = null;
    if (local != null)
      try {
        local.requestCancellation();
      } catch (RuntimeException ignored) {
      }
    var prior = refreshing;
    refreshing = null;
    if (prior != null) cancel(prior);
    return generation;
  }

  private static void cancel(BuildLibraryService.RefreshOperation operation) {
    try {
      operation.requestCancellation();
    } catch (RuntimeException ignored) {
      /* Observation is already retired. */
    }
  }

  private boolean current(long request) {
    return !closed && request == generation;
  }

  private boolean selectionCurrent(long request, long selection) {
    return current(request) && selection == selectionGeneration;
  }

  private static BuildLibraryException.Code code(Throwable failure) {
    while (failure instanceof CompletionException || failure instanceof ExecutionException)
      failure = failure.getCause();
    return failure instanceof BuildLibraryException typed ? typed.code() : null;
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
