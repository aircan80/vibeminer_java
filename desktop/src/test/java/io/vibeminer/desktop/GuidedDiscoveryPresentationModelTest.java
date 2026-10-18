package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.discoveryhistory.DiscoveryEvent;
import io.vibeminer.application.discoveryhistory.DiscoveryEventSummary.DocumentReceipt;
import io.vibeminer.domain.SourceDocument;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class GuidedDiscoveryPresentationModelTest {
  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  private static final List<GuidedDiscoveryPane.ProductOption> PRODUCTS =
      List.of(
          new GuidedDiscoveryPane.ProductOption("wow", "World of Warcraft · Retail"),
          new GuidedDiscoveryPane.ProductOption("wowt", "World of Warcraft · PTR"));

  record PageRequest(
      BuildLibraryQuery query,
      Optional<BuildLibraryCursor> cursor,
      CompletableFuture<BuildLibraryPage> future) {}

  static final class Refresh implements BuildLibraryService.RefreshOperation {
    final CompletableFuture<BuildLibraryRefreshResult> future = new CompletableFuture<>();
    int canceled;

    public CompletionStage<BuildLibraryRefreshResult> completion() {
      return future;
    }

    public void requestCancellation() {
      canceled++;
    }
  }

  static final class Service implements BuildLibraryService {
    final Queue<PageRequest> pages = new ArrayDeque<>();
    final Queue<Refresh> refreshes = new ArrayDeque<>();
    final Queue<CompletableFuture<BuildLibraryPage.Entry>> selections = new ArrayDeque<>();
    final List<String> refreshProducts = new ArrayList<>();
    final List<String> selectedIds = new ArrayList<>();
    int closes;

    public RefreshOperation refreshProduct(String product) {
      refreshProducts.add(product);
      var r = new Refresh();
      refreshes.add(r);
      return r;
    }

    public CompletionStage<BuildLibraryPage> page(
        BuildLibraryQuery query, Optional<BuildLibraryCursor> cursor, int size) {
      assertEquals(25, size);
      var future = new CompletableFuture<BuildLibraryPage>();
      pages.add(new PageRequest(query, cursor, future));
      return future;
    }

    public CompletionStage<BuildLibraryPage.Entry> select(String id) {
      selectedIds.add(id);
      var f = new CompletableFuture<BuildLibraryPage.Entry>();
      selections.add(f);
      return f;
    }

    public void close() {
      closes++;
    }
  }

  static final class Harness {
    final Service service = new Service();
    final List<BuildLibraryPage.Entry> opens = new ArrayList<>(), sources = new ArrayList<>();
    final GuidedDiscoveryPresentationModel model;

    Harness() {
      this(Runnable::run);
    }

    Harness(java.util.concurrent.Executor executor) {
      model =
          new GuidedDiscoveryPresentationModel(
              service,
              PRODUCTS,
              executor,
              s -> {},
              e ->
                  new GuidedDiscoveryPresentationModel.OpenAdmission(
                      true, "Prepare exact metadata"),
              opens::add,
              sources::add);
    }

    void restore(BuildLibraryPage.Entry... entries) {
      model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
      complete(service.pages.remove(), entries);
    }
  }

  private static BuildLibraryPage.Entry entry(
      String product, String config, long sequence, boolean local, boolean fresh) {
    var key =
        new BuildReferenceGroupKey(
            product,
            "Published",
            "12.1.5.70448",
            config.repeat(32),
            "b".repeat(32),
            "70448",
            "",
            Map.of());
    String base = "https://us.version.battle.net/v2/products/" + product;
    var reference =
        new BuildLibraryReference(
            key,
            UUID.randomUUID(),
            sequence,
            key.snapshotIdFor("us"),
            0,
            "us",
            NOW,
            new DocumentReceipt(base + "/versions", NOW, "c".repeat(64), 32),
            new DocumentReceipt(base + "/cdns", NOW, "d".repeat(64), 32),
            new DiscoveryEvent.CdnRow(
                0, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "cdn.blizzard.com")));
    var group = new BuildLibraryGrouping.Group(key, List.of(reference), reference);
    return new BuildLibraryPage.Entry(
        sequence,
        new BuildLibraryProjection.Row(
            group,
            new BuildLibrarySeenState(
                key.identity(), reference.eventId(), sequence, NOW, fresh, Optional.empty()),
            new BuildLibraryLocalFact(local, false),
            BuildLibraryProjection.Advertisement.PRESENT_IN_CHECKED_SCOPE));
  }

  private static void complete(PageRequest request, BuildLibraryPage.Entry... entries) {
    request
        .future()
        .complete(new BuildLibraryPage(request.query(), 25, 1, List.of(entries), Optional.empty()));
  }

  private static BuildLibraryRefreshResult refreshed(String product, boolean partial) {
    String base = "https://us.version.battle.net/v2/products/" + product;
    var versions =
        new DiscoveryEvent.ParsedSource(
            new SourceDocument(
                base + "/versions", NOW, "Region\n".getBytes(StandardCharsets.UTF_8)),
            List.of("Region"),
            Optional.empty());
    var cdns =
        new DiscoveryEvent.ParsedSource(
            new SourceDocument(base + "/cdns", NOW, "Name\n".getBytes(StandardCharsets.UTF_8)),
            List.of("Name"),
            Optional.empty());
    var observation =
        new BuildLibraryProductObservation(
            product, NOW, "test-v1", versions, cdns, List.of(), List.of(), Optional.empty());
    var failures =
        partial
            ? List.of(
                new BuildLibraryRefreshResult.ScopeFailure(
                    new BuildLibraryScope(product, "tw"),
                    BuildLibraryRefreshResult.Phase.CHECK_RECORDING,
                    new IllegalStateException("private")))
            : List.<BuildLibraryRefreshResult.ScopeFailure>of();
    return new BuildLibraryRefreshResult(
        new BuildLibraryProductCheckTicket(UUID.randomUUID(), product, 1, NOW),
        observation,
        List.of(),
        failures,
        false);
  }

  @Test
  void restoringLocalPageDoesNotRefreshOrOpenAndUsesExactApplicationFacts() {
    var h = new Harness();
    var row = entry("wow", "a", 1, false, true);
    h.restore(row);
    assertTrue(h.service.refreshProducts.isEmpty());
    assertTrue(h.opens.isEmpty());
    assertFalse(h.model.viewData().rows().getFirst().local());
    assertTrue(h.model.viewData().rows().getFirst().newToVibeMiner());
    assertEquals(
        row.row().group().key().identity(), h.model.viewData().rows().getFirst().groupId());
  }

  @Test
  void deliberateProductChangeRefreshesOnceThenReadsCommittedLocalPage() {
    var h = new Harness();
    h.model.selectProduct("wow");
    assertEquals(List.of("wow"), h.service.refreshProducts);
    assertTrue(h.service.pages.isEmpty());
    h.service.refreshes.remove().future.complete(refreshed("wow", false));
    assertEquals(1, h.service.pages.size());
    complete(h.service.pages.remove(), entry("wow", "a", 1, true, false));
    h.model.selectProduct("wow");
    assertEquals(1, h.service.refreshProducts.size());
    assertFalse(h.model.state().busy());
  }

  @Test
  void filtersReadLocalDataWithoutProductRefreshAndOldPageCannotPaintNewFilter() {
    var h = new Harness();
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    var old = h.service.pages.remove();
    h.model.filter(GuidedDiscoveryPane.Filter.LOCAL);
    var latest = h.service.pages.remove();
    complete(latest, entry("wow", "b", 2, true, false));
    complete(old, entry("wow", "a", 1, false, false));
    assertEquals(GuidedDiscoveryPane.Filter.LOCAL, h.model.state().filter());
    assertEquals(
        "b".repeat(32),
        h.model.state().page().entries().getFirst().row().group().key().buildConfig());
    assertTrue(h.service.refreshProducts.isEmpty());
  }

  @Test
  void productChangeCancelsOldObservationAndLateCompletionCannotIssuePage() {
    var h = new Harness();
    h.model.selectProduct("wow");
    var old = h.service.refreshes.remove();
    h.model.selectProduct("wowt");
    var latest = h.service.refreshes.remove();
    assertEquals(1, old.canceled);
    old.future.complete(refreshed("wow", false));
    assertTrue(h.service.pages.isEmpty());
    latest.future.complete(refreshed("wowt", false));
    assertEquals("wowt", h.service.pages.remove().query().productId());
  }

  @Test
  void partialRefreshStillReadsAuthoritativePageAndReportsIncompleteCheck() {
    var h = new Harness();
    h.model.selectProduct("wow");
    h.service.refreshes.remove().future.complete(refreshed("wow", true));
    complete(h.service.pages.remove(), entry("wow", "a", 1, true, false));
    assertFalse(h.model.state().error().isBlank());
    assertEquals(1, h.model.state().page().entries().size());
    assertFalse(h.model.state().error().contains("private"));
  }

  @Test
  void refreshAndPageFailurePreservePriorRowsWithoutLeakingDiagnostics() {
    var h = new Harness();
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    h.model.refresh();
    h.service
        .refreshes
        .remove()
        .future
        .completeExceptionally(new IllegalStateException("secret local path"));
    h.service
        .pages
        .remove()
        .future()
        .completeExceptionally(new IllegalStateException("secret local path"));
    assertEquals(List.of(row), h.model.state().page().entries());
    assertFalse(h.model.state().busy());
    assertFalse(h.model.state().error().contains("secret"));
  }

  @Test
  void selectionRereadsExactEntryButDoesNotOpenUntilExplicitAction() {
    var h = new Harness();
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    h.model.select(row.row().group().key().identity());
    h.service.selections.remove().complete(row);
    assertTrue(h.opens.isEmpty());
    h.model.openSelected();
    assertEquals(List.of(row), h.opens);
    h.model.sourceDetails();
    assertEquals(List.of(row), h.sources);
  }

  @Test
  void staleSelectionCannotOpenNewProductAndUnknownGroupCannotRequestLookup() {
    var h = new Harness();
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    h.model.select("missing");
    assertTrue(h.service.selectedIds.isEmpty());
    h.model.select(row.row().group().key().identity());
    var old = h.service.selections.remove();
    h.model.selectProduct("wowt");
    old.complete(row);
    h.model.openSelected();
    assertNull(h.model.state().selected());
    assertTrue(h.opens.isEmpty());
  }

  @Test
  void alteredSelectionIdentityIsRejectedRatherThanSubstituted() {
    var h = new Harness();
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    h.model.select(row.row().group().key().identity());
    h.service.selections.remove().complete(entry("wow", "b", 2, true, false));
    h.model.openSelected();
    assertTrue(h.opens.isEmpty());
    assertNull(h.model.state().selected());
    assertFalse(h.model.state().error().isBlank());
  }

  @Test
  void asynchronousCompletionsUsePresentationExecutorAndCloseDoesNotCloseBorrowedService() {
    Queue<Runnable> ui = new ArrayDeque<>();
    var h = new Harness(ui::add);
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    complete(h.service.pages.remove());
    assertTrue(h.model.state().busy());
    ui.remove().run();
    assertFalse(h.model.state().busy());
    h.model.selectProduct("wow");
    h.model.refresh();
    var pending = h.service.refreshes.remove();
    h.model.close();
    pending.future.complete(refreshed("wow", false));
    while (!ui.isEmpty()) ui.remove().run();
    assertEquals(0, h.service.closes);
    assertTrue(h.service.pages.isEmpty());
  }

  @Test
  void wrongQueryResponseAndInvalidProductCannotBorrowPriorContext() {
    var h = new Harness();
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    var pending = h.service.pages.remove();
    pending
        .future()
        .complete(
            new BuildLibraryPage(
                new BuildLibraryQuery("wowt", BuildLibraryProjection.Filter.ALL),
                25,
                1,
                List.of(),
                Optional.empty()));
    assertNull(h.model.state().page());
    assertFalse(h.model.state().error().isBlank());
    h.model.selectProduct("not_reviewed");
    assertTrue(h.service.refreshProducts.isEmpty());
  }

  @Test
  void suppliedAliasUpdatesHaveNoIoAndRemovedProductRetiresPendingWork() {
    var h = new Harness();
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    h.model.setProducts(
        List.of(new GuidedDiscoveryPane.ProductOption("wow", "Updated presentation alias")));
    assertEquals(row, h.model.state().page().entries().getFirst());
    assertTrue(h.service.pages.isEmpty());
    assertTrue(h.service.refreshProducts.isEmpty());
    h.model.refresh();
    var pending = h.service.refreshes.remove();
    h.model.setProducts(List.of());
    assertEquals(1, pending.canceled);
    pending.future.complete(refreshed("wow", false));
    assertTrue(h.service.pages.isEmpty());
    assertNull(h.model.state().productId());
  }

  @Test
  void nextPageUsesExactCursorWithoutRefreshAndRejectsChangedRevision() {
    var h = new Harness();
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    var request = h.service.pages.remove();
    var row = entry("wow", "a", 2, true, false);
    var cursor =
        new BuildLibraryCursor(
            request.query().identity(), 7, 2, row.row().group().key().identity(), 25);
    request
        .future()
        .complete(new BuildLibraryPage(request.query(), 25, 7, List.of(row), Optional.of(cursor)));
    h.model.more();
    var next = h.service.pages.remove();
    assertEquals(Optional.of(cursor), next.cursor());
    assertTrue(h.service.refreshProducts.isEmpty());
    next.future()
        .complete(
            new BuildLibraryPage(
                next.query(), 25, 8, List.of(entry("wow", "b", 1, true, false)), Optional.empty()));
    assertEquals(List.of(row), h.model.state().page().entries());
    assertTrue(h.model.state().error().contains("local list changed"));
  }

  @Test
  void inspectorUsesRereadEntryFactsRatherThanOldPageFacts() {
    var h = new Harness();
    var old = entry("wow", "a", 1, false, true);
    h.restore(old);
    h.model.select(old.row().group().key().identity());
    var reread = entry("wow", "a", 1, true, false);
    h.service.selections.remove().complete(reread);
    assertTrue(h.model.viewData().rows().getFirst().local());
    assertFalse(h.model.viewData().rows().getFirst().newToVibeMiner());
    h.model.openSelected();
    assertSame(reread, h.opens.getFirst());
  }

  @Test
  void closingPendingRefreshCancelsOnlyObservationAndFencesQueuedTerminal() {
    var h = new Harness();
    h.model.selectProduct("wow");
    var pending = h.service.refreshes.remove();
    h.model.close();
    assertEquals(1, pending.canceled);
    pending.future.complete(refreshed("wow", false));
    h.model.reload();
    h.model.openSelected();
    assertEquals(0, h.service.closes);
    assertTrue(h.service.pages.isEmpty());
    assertTrue(h.opens.isEmpty());
  }
}
