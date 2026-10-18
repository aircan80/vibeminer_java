package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildoverview.BuildOverview;
import io.vibeminer.application.buildreadiness.*;
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
    int closes, activePages, maximumActivePages;
    boolean throwNextPage, completeNextPage;

    public RefreshOperation refreshProduct(String product) {
      refreshProducts.add(product);
      var r = new Refresh();
      refreshes.add(r);
      return r;
    }

    public CompletionStage<BuildLibraryPage> page(
        BuildLibraryQuery query, Optional<BuildLibraryCursor> cursor, int size) {
      assertEquals(25, size);
      if (throwNextPage) {
        throwNextPage = false;
        throw new BuildLibraryException(
            BuildLibraryException.Code.STORAGE_UNAVAILABLE, "Authored failure");
      }
      if (completeNextPage) {
        completeNextPage = false;
        return CompletableFuture.completedStage(
            new BuildLibraryPage(query, size, 1, List.of(), Optional.empty()));
      }
      if (activePages == 4)
        return CompletableFuture.failedFuture(
            new BuildLibraryException(BuildLibraryException.Code.BUSY, "Four actual reads held"));
      activePages++;
      maximumActivePages = Math.max(maximumActivePages, activePages);
      var future = new CompletableFuture<BuildLibraryPage>();
      pages.add(new PageRequest(query, cursor, future));
      return future.whenComplete((page, failure) -> activePages--);
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
      this(executor, state -> {});
    }

    Harness(
        java.util.concurrent.Executor executor,
        java.util.function.Consumer<GuidedDiscoveryPresentationModel.State> observer) {
      model =
          new GuidedDiscoveryPresentationModel(
              service,
              PRODUCTS,
              executor,
              observer,
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

  static final class ReadinessOperation implements BuildReadinessService.Operation {
    final List<BuildReadiness.Context> contexts;
    final CompletableFuture<List<BuildReadiness.Status>> done = new CompletableFuture<>();
    int canceled;
    Runnable onCancel = () -> {};

    ReadinessOperation(List<BuildReadiness.Context> contexts) {
      this.contexts = List.copyOf(contexts);
    }

    public CompletionStage<List<BuildReadiness.Status>> completion() {
      return done;
    }

    public void requestCancellation() {
      canceled++;
      onCancel.run();
    }
  }

  static final class ReadinessService implements BuildReadinessService {
    final List<ReadinessOperation> operations = new ArrayList<>();
    int closes, activePages, maximumActivePages;
    boolean throwNextPage, completeNextPage;

    public Operation read(List<BuildReadiness.Context> contexts) {
      assertTrue(contexts.size() <= BuildReadiness.MAXIMUM_CONTEXTS);
      var operation = new ReadinessOperation(contexts);
      operations.add(operation);
      return operation;
    }

    public void close() {
      closes++;
    }
  }

  private static ReadinessService readiness(Harness h) {
    var service = new ReadinessService();
    h.model.configureReadiness(service, "enUS", "metadata-v1", "inventory-v1");
    return service;
  }

  private static BuildReadiness.Status status(
      BuildReadiness.Context context,
      BuildReadiness.AdmissionPresence admission,
      BuildOverview.Readiness checked) {
    var ref = context.reference();
    var observation =
        checked == null
            ? Optional.<BuildReadiness.Observation>empty()
            : Optional.of(
                new BuildReadiness.Observation(
                    checked,
                    NOW,
                    "f".repeat(64),
                    new BuildReadiness.Origin(
                        ref.eventId(),
                        ref.observationSequence(),
                        ref.versionsRowOrdinal(),
                        ref.key().identity(),
                        ref.snapshotId(),
                        ref.region()),
                    checked == BuildOverview.Readiness.FULL
                        ? Optional.of(
                            new io.vibeminer.application.buildretention.BuildRetention.Target(
                                ref.key().identity(),
                                ref.snapshotId(),
                                "a".repeat(64),
                                "b".repeat(64),
                                1,
                                context.inventoryScopeRevision(),
                                "c".repeat(64)))
                        : Optional.empty()));
    return new BuildReadiness.Status(context, admission, observation);
  }

  @Test
  void rapidSearchesCoalesceBehindActualPageRetirementWithoutBusy() {
    var h = new Harness();
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    var held = h.service.pages.remove();
    for (int n = 0; n < 8; n++) h.model.search(Integer.toString(n));
    h.model.region("eu");
    assertTrue(h.service.pages.isEmpty(), "Only one actual read; latest desired query is pending");
    assertEquals(1, h.service.maximumActivePages);
    complete(held);
    var latest = h.service.pages.remove();
    assertEquals(Optional.of("7"), latest.query().search());
    assertEquals(Optional.of("eu"), latest.query().region());
    complete(latest);
    assertEquals(latest.query(), h.model.state().page().query());
    assertFalse(h.model.state().busy());
    assertFalse(h.model.state().error().contains("could not be read"));
    assertEquals(1, h.service.maximumActivePages);
    assertEquals(0, h.service.activePages);
    assertTrue(h.service.refreshProducts.isEmpty());
  }

  @Test
  void duplicateOldCompletionCannotReleaseSuccessorAndCloseSuppressesPendingRead() {
    var ui = new ArrayDeque<Runnable>();
    var h = new Harness(ui::add);
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    while (!ui.isEmpty()) ui.remove().run();
    var held = h.service.pages.remove();
    h.model.search("70448");
    complete(held);
    var callback = ui.remove();
    callback.run();
    var successor = h.service.pages.remove();
    callback.run();
    h.model.region("eu");
    assertTrue(h.service.pages.isEmpty());
    h.model.close();
    complete(successor);
    while (!ui.isEmpty()) ui.remove().run();
    assertTrue(h.service.pages.isEmpty());
    assertEquals(0, h.service.activePages);
    assertEquals(0, h.service.closes);
  }

  @Test
  void synchronousCompletionThrowAndRejectedPresentationDoNotStrandPageOwnership() {
    var reject = new java.util.concurrent.atomic.AtomicBoolean();
    var h =
        new Harness(
            action -> {
              if (reject.get()) throw new RejectedExecutionException("Authored UI rejection");
              action.run();
            });
    h.service.completeNextPage = true;
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    assertNotNull(h.model.state().page());
    h.service.throwNextPage = true;
    h.model.search("one");
    assertFalse(h.model.state().busy());
    h.model.search("two");
    var held = h.service.pages.remove();
    reject.set(true);
    complete(held);
    assertEquals(0, h.service.activePages);
    reject.set(false);
    h.model.search("three");
    var latest = h.service.pages.remove();
    complete(latest);
    assertEquals(Optional.of("three"), h.model.state().page().query().search());
    assertFalse(h.model.state().busy());
    assertTrue(h.service.pages.isEmpty());
  }

  @Test
  void pageObserverReentryCannotLetOldFinallyClearTheNewActualFlight() {
    var model = new java.util.concurrent.atomic.AtomicReference<GuidedDiscoveryPresentationModel>();
    var h =
        new Harness(
            Runnable::run,
            state -> {
              if (!state.busy()
                  && state.page() != null
                  && state.page().query().search().equals(Optional.of("one")))
                model.get().search("two");
            });
    model.set(h.model);
    h.restore();
    h.model.search("one");
    complete(h.service.pages.remove());
    var two = h.service.pages.remove();
    h.model.search("three");
    assertTrue(h.service.pages.isEmpty());
    complete(two);
    var three = h.service.pages.remove();
    complete(three);
    assertEquals(Optional.of("three"), h.model.state().page().query().search());
    assertEquals(1, h.service.maximumActivePages);
    assertEquals(0, h.service.activePages);
  }

  @Test
  void researchLocaleIsFactualWithoutReadinessAndUpdatesWithoutChangingOpenReference() {
    var h = new Harness();
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    h.model.select(row.row().group().key().identity());
    h.service.selections.remove().complete(row);
    assertTrue(
        h.model
            .viewData()
            .rows()
            .getFirst()
            .details()
            .contains(new GuidedDiscoveryPane.Detail("RESEARCH LOCALE", "enUS")));
    h.model.setReadinessLocale("deDE");
    assertTrue(
        h.model
            .viewData()
            .rows()
            .getFirst()
            .details()
            .contains(new GuidedDiscoveryPane.Detail("RESEARCH LOCALE", "deDE")));
    assertEquals(row, h.model.state().selected());
    h.model.openSelected();
    assertEquals(row, h.opens.getFirst());
    assertTrue(h.service.pages.isEmpty());
    assertTrue(h.service.refreshProducts.isEmpty());
  }

  @Test
  void searchAndRegionQueryWholeCatalogAndRejectStaleCompletionsWithoutRefresh() {
    var h = new Harness();
    h.restore(entry("wow", "a", 1, true, false));
    h.model.search("70448");
    var older = h.service.pages.remove();
    assertEquals(Optional.of("70448"), older.query().search());
    assertTrue(older.cursor().isEmpty());
    h.model.region("eu");
    assertTrue(h.service.pages.isEmpty());
    complete(older, entry("wow", "b", 2, true, false));
    var current = h.service.pages.remove();
    assertEquals(Optional.of("eu"), current.query().region());
    assertEquals(Optional.of("70448"), current.query().search());
    assertTrue(current.cursor().isEmpty());
    current
        .future()
        .complete(new BuildLibraryPage(current.query(), 25, 1, List.of(), Optional.empty()));
    assertTrue(h.model.state().page().entries().isEmpty());
    assertNull(h.model.state().selected());
    assertEquals("70448", h.model.viewData().query());
    assertEquals("eu", h.model.viewData().region());
    assertTrue(
        h.model.viewData().availableRegions().containsAll(List.of("us", "eu", "kr", "tw", "cn")));
    assertTrue(h.service.refreshProducts.isEmpty());
  }

  @Test
  void selectedRegionalReferenceDrivesDetailsAndOpenWithoutDiscardingProvenance() {
    var h = new Harness();
    var us = entry("wow", "a", 1, true, false);
    var group = us.row().group();
    var original = group.preferredReference();
    var eu =
        new BuildLibraryReference(
            group.key(),
            UUID.randomUUID(),
            2,
            group.key().snapshotIdFor("eu"),
            0,
            "eu",
            NOW,
            original.versions(),
            original.cdns(),
            new DiscoveryEvent.CdnRow(
                0, Map.of("Name", "eu", "Path", "tpr/wow", "Hosts", "cdn.blizzard.com")));
    var merged =
        new BuildLibraryPage.Entry(
            1,
            new BuildLibraryProjection.Row(
                new BuildLibraryGrouping.Group(group.key(), List.of(original, eu), original),
                us.row().seen(),
                us.row().local(),
                us.row().advertisement()));
    h.restore(merged);
    h.model.region("eu");
    var request = h.service.pages.remove();
    complete(request, merged);
    h.model.select(group.key().identity());
    h.service.selections.remove().complete(merged);
    assertEquals(eu, h.model.state().selected().row().group().preferredReference());
    assertEquals(List.of(original, eu), h.model.state().selected().row().group().references());
    h.model.openSelected();
    assertEquals(eu, h.opens.getFirst().row().group().preferredReference());
    assertTrue(
        h.model
            .viewData()
            .rows()
            .getFirst()
            .details()
            .contains(new GuidedDiscoveryPane.Detail("REGION", "eu")));
    assertTrue(
        h.model.viewData().rows().getFirst().details().stream()
            .anyMatch(
                d ->
                    d.label().equals("PREFERRED METADATA REFERENCE")
                        && d.value().startsWith("eu")));
    h.model.search("nonmatching");
    assertNull(h.model.state().selected());
    assertTrue(h.service.refreshProducts.isEmpty());
  }

  @Test
  void cancellationReentryKeepsNewerProductInsteadOfOuterRestore() {
    var h = new Harness();
    var service = readiness(h);
    h.restore(entry("wow", "a", 1, true, false));
    var old = service.operations.getFirst();
    old.onCancel = () -> h.model.restore("wowt", GuidedDiscoveryPane.Filter.ALL);
    h.model.restore("wow", GuidedDiscoveryPane.Filter.ALL);
    assertEquals("wowt", h.model.state().productId());
    assertEquals(1, h.service.pages.size());
    assertEquals("wowt", h.service.pages.peek().query().productId());
    assertEquals(1, service.operations.size());
    old.done.complete(List.of());
    assertEquals("wowt", h.model.state().productId());
  }

  @Test
  void selectionCancellationReentryCannotInspectNewerProductWithOldGroup() {
    var h = new Harness();
    var service = readiness(h);
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    service.operations.getFirst().onCancel =
        () -> h.model.restore("wowt", GuidedDiscoveryPane.Filter.ALL);
    h.model.select(row.row().group().key().identity());
    assertEquals("wowt", h.model.state().productId());
    assertTrue(h.service.selections.isEmpty());
    assertNull(h.model.state().selected());
  }

  @Test
  void localeCancellationReentryKeepsNewerLocaleAndOnePendingFlight() {
    var h = new Harness();
    var service = readiness(h);
    h.restore(entry("wow", "a", 1, true, false));
    var old = service.operations.getFirst();
    old.onCancel = () -> h.model.setReadinessLocale("frFR");
    h.model.setReadinessLocale("deDE");
    assertEquals(1, service.operations.size());
    old.done.complete(List.of());
    assertEquals(2, service.operations.size());
    assertEquals("frFR", service.operations.getLast().contexts.getFirst().locale());
  }

  @Test
  void readinessConfigurationAndAliasesDoNotReadUntilAnActualVisiblePageArrives() {
    var h = new Harness();
    var service = readiness(h);
    h.model.setProducts(PRODUCTS);
    assertTrue(service.operations.isEmpty());
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    assertEquals(1, service.operations.size());
    var contexts = service.operations.getFirst().contexts;
    assertEquals(1, contexts.size());
    assertEquals(row.row().group().preferredReference(), contexts.getFirst().reference());
    assertEquals("enUS", contexts.getFirst().locale());
    assertEquals("metadata-v1", contexts.getFirst().metadataScopeRevision());
    assertEquals("inventory-v1", contexts.getFirst().inventoryScopeRevision());
    assertFalse(h.model.viewData().rows().getFirst().status().contains("Fully downloaded"));
    assertTrue(h.opens.isEmpty());
  }

  @Test
  void returningFromMiningReloadsOnlySavedObservationsAndPreservesSelection() {
    var h = new Harness();
    var service = readiness(h);
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    var first = service.operations.getFirst();
    first.done.complete(
        List.of(status(first.contexts.getFirst(), BuildReadiness.AdmissionPresence.PRESENT, null)));
    h.model.select(row.row().group().key().identity());
    h.service.selections.remove().complete(row);
    var selectedRead = service.operations.getLast();
    selectedRead.done.complete(
        List.of(
            status(
                selectedRead.contexts.getFirst(), BuildReadiness.AdmissionPresence.PRESENT, null)));
    int pages = h.service.pages.size();
    int refreshes = h.service.refreshes.size();
    h.model.refreshReadiness();
    var returned = service.operations.getLast();
    returned.done.complete(
        List.of(
            status(
                returned.contexts.getFirst(),
                BuildReadiness.AdmissionPresence.PRESENT,
                BuildOverview.Readiness.FULL)));
    assertSame(row, h.model.state().selected());
    assertEquals(pages, h.service.pages.size());
    assertEquals(refreshes, h.service.refreshes.size());
    assertTrue(h.opens.isEmpty());
    assertTrue(
        h.model.viewData().rows().getFirst().status().startsWith("Last checked: Fully downloaded"));
  }

  @Test
  void readinessAdmissionPresenceIsNotAClaimOfCurrentCoverage() {
    var h = new Harness();
    var service = readiness(h);
    h.restore(entry("wow", "a", 1, true, false));
    var operation = service.operations.getFirst();
    operation.done.complete(
        List.of(
            status(operation.contexts.getFirst(), BuildReadiness.AdmissionPresence.PRESENT, null)));
    assertEquals("Local readiness not checked", h.model.viewData().rows().getFirst().status());
    h.model.setReadinessLocale("deDE");
    operation = service.operations.getLast();
    operation.done.complete(
        List.of(
            status(operation.contexts.getFirst(), BuildReadiness.AdmissionPresence.ABSENT, null)));
    assertEquals("Not ready", h.model.viewData().rows().getFirst().status());
  }

  @Test
  void datedHistoricalFullHasHumanTimeLocaleAndExplicitNonCurrentInspectorNote() {
    var h = new Harness();
    var service = readiness(h);
    h.restore(entry("wow", "a", 1, true, false));
    var operation = service.operations.getFirst();
    operation.done.complete(
        List.of(
            status(
                operation.contexts.getFirst(),
                BuildReadiness.AdmissionPresence.PRESENT,
                BuildOverview.Readiness.FULL)));
    var row = h.model.viewData().rows().getFirst();
    assertEquals("Last checked: Fully downloaded · 18 Oct 2026, 12:00 UTC · enUS", row.status());
    assertTrue(row.details().stream().anyMatch(d -> d.value().contains("Files may have changed")));
    assertTrue(h.opens.isEmpty());
  }

  @Test
  void missingAdmissionOverridesHistoricalFullButPreservesDatedEvidenceInInspector() {
    var h = new Harness();
    var service = readiness(h);
    h.restore(entry("wow", "a", 1, true, false));
    var operation = service.operations.getFirst();
    operation.done.complete(
        List.of(
            status(
                operation.contexts.getFirst(),
                BuildReadiness.AdmissionPresence.ABSENT,
                BuildOverview.Readiness.FULL)));
    var row = h.model.viewData().rows().getFirst();
    assertEquals("Not ready", row.status());
    assertTrue(
        row.details().stream().anyMatch(d -> d.value().contains("Last checked: Fully downloaded")));
  }

  @Test
  void changingPageCoalescesUntilCanceledSqlObservationActuallyTerminates() {
    var h = new Harness();
    var service = readiness(h);
    h.restore(entry("wow", "a", 1, true, false));
    var old = service.operations.getFirst();
    h.model.filter(GuidedDiscoveryPane.Filter.LOCAL);
    complete(h.service.pages.remove(), entry("wow", "b", 2, true, false));
    assertEquals(
        1,
        service.operations.size(),
        "Do not release the old actual SQL operation on cancellation");
    assertTrue(old.canceled > 0);
    old.done.complete(
        List.of(
            status(
                old.contexts.getFirst(),
                BuildReadiness.AdmissionPresence.PRESENT,
                BuildOverview.Readiness.FULL)));
    assertEquals(2, service.operations.size());
    assertEquals(
        "b".repeat(32),
        service.operations.getLast().contexts.getFirst().reference().key().buildConfig());
    assertFalse(h.model.viewData().rows().getFirst().status().contains("Fully downloaded"));
    assertTrue(h.service.refreshProducts.isEmpty());
  }

  @Test
  void localeAndExactSelectionRetireOldReadinessWithoutPreparingOrOpening() {
    var h = new Harness();
    var service = readiness(h);
    var oldRow = entry("wow", "a", 1, true, false);
    h.restore(oldRow);
    var old = service.operations.getFirst();
    h.model.setReadinessLocale("deDE");
    old.done.complete(
        List.of(
            status(
                old.contexts.getFirst(),
                BuildReadiness.AdmissionPresence.PRESENT,
                BuildOverview.Readiness.FULL)));
    var locale = service.operations.getLast();
    assertEquals("deDE", locale.contexts.getFirst().locale());
    h.model.select(oldRow.row().group().key().identity());
    var fresh = entry("wow", "a", 2, true, false);
    h.service.selections.remove().complete(fresh);
    locale.done.complete(
        List.of(
            status(
                locale.contexts.getFirst(),
                BuildReadiness.AdmissionPresence.PRESENT,
                BuildOverview.Readiness.FULL)));
    assertEquals(
        fresh.row().group().preferredReference(),
        service.operations.getLast().contexts.getFirst().reference());
    assertFalse(h.model.viewData().rows().getFirst().status().contains("Fully downloaded"));
    assertTrue(h.opens.isEmpty());
  }

  @Test
  void wrongOrIncompleteReadinessResponseNeverPaintsFullAndKeepsLibraryRows() {
    var h = new Harness();
    var service = readiness(h);
    var row = entry("wow", "a", 1, true, false);
    h.restore(row);
    service.operations.getFirst().done.complete(List.of());
    assertEquals(List.of(row), h.model.state().page().entries());
    assertEquals("Local readiness not checked", h.model.viewData().rows().getFirst().status());
    assertTrue(h.model.viewData().error().contains("Local readiness"));
  }

  @Test
  void readinessFailureAndCloseKeepUnknownAndDoNotCloseBorrowedService() {
    var h = new Harness();
    var service = readiness(h);
    h.restore(entry("wow", "a", 1, true, false));
    service
        .operations
        .getFirst()
        .done
        .completeExceptionally(new IllegalStateException("private path"));
    assertFalse(h.model.viewData().error().contains("private"));
    assertFalse(h.model.viewData().rows().getFirst().status().contains("Fully downloaded"));
    h.model.setReadinessLocale("deDE");
    var pending = service.operations.getLast();
    h.model.close();
    assertTrue(pending.canceled > 0);
    pending.done.complete(
        List.of(
            status(
                pending.contexts.getFirst(),
                BuildReadiness.AdmissionPresence.PRESENT,
                BuildOverview.Readiness.FULL)));
    assertEquals(0, service.closes);
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
    assertTrue(h.service.pages.isEmpty());
    complete(old, entry("wow", "a", 1, false, false));
    assertNull(h.model.state().page());
    var latest = h.service.pages.remove();
    complete(latest, entry("wow", "b", 2, true, false));
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
    assertTrue(h.service.pages.isEmpty());
    ui.remove()
        .run(); // Explicit local-preparation completion enters the presentation thread first.
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
