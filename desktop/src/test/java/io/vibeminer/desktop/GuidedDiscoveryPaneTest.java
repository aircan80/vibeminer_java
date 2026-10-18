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
}
