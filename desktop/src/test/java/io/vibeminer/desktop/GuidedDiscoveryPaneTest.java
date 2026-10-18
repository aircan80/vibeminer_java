package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GuidedDiscoveryPaneTest {
  private static GuidedDiscoveryPane.BuildRow row(String id, boolean canOpen) {
    return new GuidedDiscoveryPane.BuildRow(
        id,
        "12.1.5.70448",
        List.of("us", "eu"),
        "Not ready",
        true,
        true,
        false,
        List.of(),
        canOpen,
        canOpen ? "" : "Unsupported metadata");
  }

  private static GuidedDiscoveryPane.ViewData data(String product, String selected, boolean busy) {
    return new GuidedDiscoveryPane.ViewData(
        List.of(
            new GuidedDiscoveryPane.ProductOption("wow", "World of Warcraft · Retail"),
            new GuidedDiscoveryPane.ProductOption("wowt", "World of Warcraft · PTR")),
        product,
        List.of(row("a", true), row("b", false)),
        selected,
        GuidedDiscoveryPane.Filter.ALL,
        busy,
        true,
        false,
        "",
        "");
  }

  private static final class Harness {
    final AtomicReference<GuidedDiscoveryPane.ViewData> state =
        new AtomicReference<>(data("wow", "a", false));
    final List<String> calls = new ArrayList<>();
    final GuidedDiscoveryPane.Interactions events =
        new GuidedDiscoveryPane.Interactions(
            state::get,
            new GuidedDiscoveryPane.Callbacks(
                p -> calls.add("product:" + p),
                f -> calls.add("filter:" + f),
                b -> calls.add("select:" + b),
                b -> calls.add("open:" + b),
                () -> calls.add("refresh"),
                () -> calls.add("more"),
                b -> calls.add("source:" + b)));
  }

  @Test
  void programmaticRestoreCannotRefreshSelectOrOpenEvenWhenNested() {
    var h = new Harness();
    h.events.restore(
        () -> {
          h.events.productChanged("wowt");
          h.events.restore(() -> h.events.buildSelected("b"));
          h.events.openSelected();
        });
    assertTrue(h.calls.isEmpty());
    h.events.productChanged("wowt");
    assertEquals(List.of("product:wowt"), h.calls);
  }

  @Test
  void failedRestoreReleasesGuard() {
    var h = new Harness();
    assertThrows(
        IllegalStateException.class,
        () ->
            h.events.restore(
                () -> {
                  throw new IllegalStateException();
                }));
    h.events.productChanged("wowt");
    assertEquals(List.of("product:wowt"), h.calls);
  }

  @Test
  void rowSelectionNeverOpensBuildAndOnlyCurrentMembersAreAdmitted() {
    var h = new Harness();
    h.events.buildSelected("missing");
    h.events.buildSelected("b");
    assertEquals(List.of("select:b"), h.calls);
    assertEquals(
        "a", h.state.get().selectedBuildId(), "Callbacks do not mutate application projection");
  }

  @Test
  void deliberateProductSelectionRefreshesOnceWhileSameOrUnknownValuesDoNot() {
    var h = new Harness();
    h.events.productChanged(null);
    h.events.productChanged("other");
    h.events.productChanged("wow");
    h.events.productChanged("wowt");
    assertEquals(List.of("product:wowt"), h.calls);
  }

  @Test
  void soleOpenActionUsesCurrentExactSelectionAndHonorsAdmission() {
    var h = new Harness();
    h.events.openSelected();
    h.state.set(data("wow", "b", false));
    h.events.openSelected();
    h.state.set(data("wow", "a", true));
    h.events.openSelected();
    h.state.set(data("wow", null, false));
    h.events.openSelected();
    assertEquals(List.of("open:a"), h.calls);
  }

  @Test
  void explicitRefreshAndPagingHonorCurrentCapabilities() {
    var h = new Harness();
    h.events.refresh();
    h.events.more();
    h.state.set(data("wow", "a", true));
    h.events.refresh();
    assertEquals(List.of("refresh"), h.calls);
  }

  @Test
  void sourceDetailsDoNotOpenOrChangeSelection() {
    var h = new Harness();
    h.events.sourceDetails();
    assertEquals(List.of("source:a"), h.calls);
  }

  @Test
  void localFiltersArePresentationCallbacksWithoutImpliedNetwork() {
    var h = new Harness();
    h.events.filterChanged(GuidedDiscoveryPane.Filter.ALL);
    h.events.filterChanged(GuidedDiscoveryPane.Filter.LOCAL);
    assertEquals(List.of("filter:LOCAL"), h.calls);
  }

  @Test
  void queryAndRegionControlsUseSuppliedStateAndRestorationNeverQueries() {
    var current = new AtomicReference<>(data("wow", "a", false));
    var calls = new ArrayList<String>();
    var events =
        new GuidedDiscoveryPane.Interactions(
            current::get,
            new GuidedDiscoveryPane.Callbacks(
                p -> {},
                f -> {},
                b -> {},
                b -> {},
                () -> {},
                () -> {},
                b -> {},
                q -> calls.add("query:" + q),
                r -> calls.add("region:" + r)));
    events.restore(
        () -> {
          events.queryChanged("70448");
          events.regionChanged("eu");
        });
    assertTrue(calls.isEmpty());
    events.queryChanged("");
    events.regionChanged("");
    assertTrue(calls.isEmpty());
    events.queryChanged("70448");
    events.regionChanged("eu");
    assertEquals(List.of("query:70448", "region:eu"), calls);
  }

  @Test
  void searchAcceptsBoundedUnicodeButRejectsInvalidOrControlInput() {
    assertTrue(GuidedDiscoveryPane.validSearch("12.1.5.70448"));
    assertTrue(GuidedDiscoveryPane.validSearch("版本 😀"));
    assertTrue(GuidedDiscoveryPane.validSearch("x".repeat(128)));
    assertFalse(GuidedDiscoveryPane.validSearch("x".repeat(129)));
    assertFalse(GuidedDiscoveryPane.validSearch("build\nversion"));
    assertFalse(GuidedDiscoveryPane.validSearch("\uD800"));
  }

  @Test
  void conciseReadinessKeepsHistoricalQualifierAndDoesNotHidePendingOrErrorNotes() {
    assertEquals(
        "Saved assessment; open to verify current files.",
        GuidedDiscoveryPane.readinessSummary("Last checked: Fully downloaded", "full warning"));
    assertEquals(
        "Open to download and verify build metadata.",
        GuidedDiscoveryPane.readinessSummary("Not ready", "admission absent"));
    assertEquals(
        "Local readiness could not be read.",
        GuidedDiscoveryPane.readinessSummary(
            "Local readiness not checked", "Local readiness could not be read."));
  }

  @Test
  void inspectorSubtitleUsesSuppliedDescriptionSourceAndRegionsWithoutGuessingLive() {
    var row =
        new GuidedDiscoveryPane.BuildRow(
            "a",
            "70448",
            List.of("us", "eu"),
            "Not ready",
            false,
            true,
            false,
            List.of(new GuidedDiscoveryPane.Detail("PRODUCT / SOURCE CHANNEL", "wow / Published")),
            false,
            "");
    assertEquals(
        "World of Warcraft · Retail · Published · us · eu",
        GuidedDiscoveryPane.inspectorSubtitle(data("wow", "a", false), row));
  }

  @Test
  void observationTimeIsHumanUtcWithoutChangingUnknownOriginalValues() {
    assertEquals(
        "18 Oct 2026, 20:56 UTC",
        GuidedDiscoveryPane.observedTime("2026-10-18T20:56:31.483553462Z"));
    assertEquals("Unknown", GuidedDiscoveryPane.observedTime("Unknown"));
  }

  @Test
  void projectionsDefensivelyCopyListsAndRejectAmbiguousRowIdentity() {
    var rows = new ArrayList<>(List.of(row("a", true)));
    var value =
        new GuidedDiscoveryPane.ViewData(
            List.of(),
            null,
            rows,
            "a",
            GuidedDiscoveryPane.Filter.ALL,
            false,
            false,
            false,
            "",
            "");
    rows.clear();
    assertEquals(1, value.rows().size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GuidedDiscoveryPane.ViewData(
                List.of(),
                null,
                List.of(row("a", true), row("a", false)),
                "a",
                GuidedDiscoveryPane.Filter.ALL,
                false,
                false,
                false,
                "",
                ""));
  }

  @Test
  void mouseSelectionSurvivesSynchronousModelRenderWithoutInvalidatingJavaFxChange()
      throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        "1".equals(System.getenv("VIBEMINER_FX_SELECTION_TEST"))
            && System.getenv("DISPLAY") != null,
        "Run the Robot regression explicitly on an isolated display");
    var started = new java.util.concurrent.CountDownLatch(1);
    try {
      javafx.application.Platform.startup(started::countDown);
    } catch (IllegalStateException alreadyStarted) {
      started.countDown();
    }
    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
    var failure = new AtomicReference<Throwable>();
    var previousHandler = new AtomicReference<Thread.UncaughtExceptionHandler>();
    var previousImplicitExit = new java.util.concurrent.atomic.AtomicBoolean();
    var listChanges = new java.util.concurrent.atomic.AtomicInteger();
    var selected = new ArrayList<String>();
    var selectionObserved = new java.util.concurrent.CountDownLatch(1);
    var setup =
        new java.util.concurrent.FutureTask<javafx.stage.Stage>(
            () -> {
              previousImplicitExit.set(javafx.application.Platform.isImplicitExit());
              javafx.application.Platform.setImplicitExit(false);
              previousHandler.set(Thread.currentThread().getUncaughtExceptionHandler());
              Thread.currentThread()
                  .setUncaughtExceptionHandler(
                      (thread, error) -> failure.compareAndSet(null, error));
              var holder = new GuidedDiscoveryPane[1];
              holder[0] =
                  new GuidedDiscoveryPane(
                      data("wow", "a", false),
                      new GuidedDiscoveryPane.Callbacks(
                          p -> {},
                          f -> {},
                          id -> {
                            selected.add(id);
                            selectionObserved.countDown();
                            // The real model publishes an intermediate selection-loading state
                            // synchronously.
                            holder[0].update(data("wow", null, false));
                          },
                          id -> {},
                          () -> {},
                          () -> {},
                          id -> {}));
              var stage = new javafx.stage.Stage();
              stage.setScene(new javafx.scene.Scene(holder[0], 1280, 900));
              stage.show();
              stage.getScene().getRoot().applyCss();
              stage.getScene().getRoot().layout();
              var list = (javafx.scene.control.ListView<?>) holder[0].lookup("#guided-builds");
              list.getItems()
                  .addListener(
                      (javafx.collections.ListChangeListener<Object>)
                          change -> listChanges.incrementAndGet());
              return stage;
            });
    javafx.application.Platform.runLater(setup);
    var stage = setup.get(5, java.util.concurrent.TimeUnit.SECONDS);
    try {
      var click =
          new java.util.concurrent.FutureTask<Void>(
              () -> {
                var list =
                    (javafx.scene.control.ListView<?>) stage.getScene().lookup("#guided-builds");
                var cell =
                    list.lookupAll(".list-cell").stream()
                        .filter(
                            n ->
                                n instanceof javafx.scene.control.ListCell<?> c
                                    && c.getIndex() == 1)
                        .findFirst()
                        .orElseThrow();
                var bounds = cell.localToScreen(cell.getBoundsInLocal());
                var robot = new javafx.scene.robot.Robot();
                robot.mouseMove(bounds.getMinX() + 20, bounds.getMinY() + 20);
                robot.mouseClick(javafx.scene.input.MouseButton.PRIMARY);
                return null;
              });
      javafx.application.Platform.runLater(click);
      click.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(
          selectionObserved.await(5, java.util.concurrent.TimeUnit.SECONDS),
          "Actual mouse selected the second row");
      var drained = new java.util.concurrent.FutureTask<Void>(() -> null);
      javafx.application.Platform.runLater(drained);
      drained.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(List.of("b"), selected);
      assertEquals(0, listChanges.get(), "Selection-only loading renders preserve the item list");
      var restore =
          new java.util.concurrent.FutureTask<Void>(
              () -> {
                ((GuidedDiscoveryPane) stage.getScene().getRoot()).update(data("wow", "b", false));
                return null;
              });
      javafx.application.Platform.runLater(restore);
      restore.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var restored = new java.util.concurrent.FutureTask<Void>(() -> null);
      javafx.application.Platform.runLater(restored);
      restored.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          List.of("b"),
          selected,
          "Programmatic restored selection cannot emit another user request");
      var refreshedBeforeDispatch =
          new java.util.concurrent.FutureTask<Void>(
              () -> {
                var pane = (GuidedDiscoveryPane) stage.getScene().getRoot();
                var list = (javafx.scene.control.ListView<?>) pane.lookup("#guided-builds");
                list.getSelectionModel().clearAndSelect(0);
                var current = data("wow", null, false);
                var original = current.rows().getFirst();
                var updated =
                    new GuidedDiscoveryPane.BuildRow(
                        original.groupId(),
                        original.version(),
                        original.regions(),
                        "Partial",
                        original.local(),
                        original.currentlyAdvertised(),
                        original.newToVibeMiner(),
                        original.details(),
                        original.openEnabled(),
                        original.openReason());
                pane.update(
                    new GuidedDiscoveryPane.ViewData(
                        current.products(),
                        "wow",
                        List.of(updated, current.rows().get(1)),
                        null,
                        current.filter(),
                        false,
                        true,
                        false,
                        "",
                        ""));
                return null;
              });
      javafx.application.Platform.runLater(refreshedBeforeDispatch);
      refreshedBeforeDispatch.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var refreshed = new java.util.concurrent.FutureTask<Void>(() -> null);
      javafx.application.Platform.runLater(refreshed);
      refreshed.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          List.of("b", "a"),
          selected,
          "Same-context restored-null/readiness refresh must preserve the pending user selection");
      var supersede =
          new java.util.concurrent.FutureTask<Void>(
              () -> {
                var list =
                    (javafx.scene.control.ListView<?>) stage.getScene().lookup("#guided-builds");
                list.getSelectionModel().clearAndSelect(0);
                // A different product render wins before that deferred selection can be dispatched.
                ((GuidedDiscoveryPane) stage.getScene().getRoot()).update(data("wowt", "b", false));
                return null;
              });
      javafx.application.Platform.runLater(supersede);
      supersede.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var superseded = new java.util.concurrent.FutureTask<Void>(() -> null);
      javafx.application.Platform.runLater(superseded);
      superseded.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          List.of("b", "a"),
          selected,
          "Superseded deferred selection cannot select another product");
      var base = data("wow", null, false);
      var changedContexts =
          List.of(
              data("wowt", null, false),
              new GuidedDiscoveryPane.ViewData(
                  base.products(),
                  "wow",
                  base.rows(),
                  null,
                  GuidedDiscoveryPane.Filter.LOCAL,
                  false,
                  true,
                  false,
                  "",
                  ""),
              new GuidedDiscoveryPane.ViewData(
                  base.products(),
                  "wow",
                  base.rows(),
                  null,
                  base.filter(),
                  false,
                  true,
                  false,
                  "",
                  "",
                  "changed",
                  "",
                  List.of()),
              new GuidedDiscoveryPane.ViewData(
                  base.products(),
                  "wow",
                  base.rows(),
                  null,
                  base.filter(),
                  false,
                  true,
                  false,
                  "",
                  "",
                  "",
                  "eu",
                  List.of("eu")),
              new GuidedDiscoveryPane.ViewData(
                  base.products(),
                  "wow",
                  List.of(base.rows().get(1)),
                  null,
                  base.filter(),
                  false,
                  true,
                  false,
                  "",
                  ""));
      for (var changedContext : changedContexts) {
        var invalidate =
            new java.util.concurrent.FutureTask<Void>(
                () -> {
                  var pane = (GuidedDiscoveryPane) stage.getScene().getRoot();
                  pane.update(base);
                  var list = (javafx.scene.control.ListView<?>) pane.lookup("#guided-builds");
                  list.getSelectionModel().clearAndSelect(0);
                  pane.update(changedContext);
                  pane.update(
                      base); // Even a return before dispatch must not revive an invalidated click.
                  return null;
                });
        javafx.application.Platform.runLater(invalidate);
        invalidate.get(5, java.util.concurrent.TimeUnit.SECONDS);
        var invalidated = new java.util.concurrent.FutureTask<Void>(() -> null);
        javafx.application.Platform.runLater(invalidated);
        invalidated.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(
            List.of("b", "a"),
            selected,
            "Context change or removal must invalidate even when the old context returns");
      }
      var clearUserIntent =
          new java.util.concurrent.FutureTask<Void>(
              () -> {
                var list =
                    (javafx.scene.control.ListView<?>) stage.getScene().lookup("#guided-builds");
                list.getSelectionModel().clearAndSelect(0);
                list.getSelectionModel().clearSelection();
                return null;
              });
      javafx.application.Platform.runLater(clearUserIntent);
      clearUserIntent.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var cleared = new java.util.concurrent.FutureTask<Void>(() -> null);
      javafx.application.Platform.runLater(cleared);
      cleared.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(List.of("b", "a"), selected, "Explicit user clear cancels queued intent");
      var newerUserIntent =
          new java.util.concurrent.FutureTask<Void>(
              () -> {
                var list =
                    (javafx.scene.control.ListView<?>) stage.getScene().lookup("#guided-builds");
                list.getSelectionModel().clearAndSelect(0);
                list.getSelectionModel().clearAndSelect(1);
                return null;
              });
      javafx.application.Platform.runLater(newerUserIntent);
      newerUserIntent.get(5, java.util.concurrent.TimeUnit.SECONDS);
      var newer = new java.util.concurrent.FutureTask<Void>(() -> null);
      javafx.application.Platform.runLater(newer);
      newer.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          List.of("b", "a", "b"), selected, "Only the latest queued user selection is dispatched");
      assertNull(failure.get(), () -> "JavaFX mouse selection failed: " + failure.get());
    } finally {
      var close =
          new java.util.concurrent.FutureTask<Void>(
              () -> {
                Thread.currentThread().setUncaughtExceptionHandler(previousHandler.get());
                stage.close();
                javafx.application.Platform.setImplicitExit(previousImplicitExit.get());
                return null;
              });
      javafx.application.Platform.runLater(close);
      close.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }
}
