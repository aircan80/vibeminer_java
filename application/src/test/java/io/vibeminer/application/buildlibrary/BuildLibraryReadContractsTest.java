package io.vibeminer.application.buildlibrary;

import static io.vibeminer.application.buildlibrary.BuildLibraryPolicyTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class BuildLibraryReadContractsTest {
  static BuildLibraryPage.Entry entry(long sequence) {
    var reference = reference("us", 1);
    var row =
        new BuildLibraryProjection.Row(
            BuildLibraryGrouping.group(List.of(reference), 10).getFirst(),
            BuildLibrarySeenPolicy.observe(Optional.empty(), reference, false, NOW),
            BuildLibraryLocalFact.observationOnly(),
            BuildLibraryProjection.Advertisement.UNKNOWN);
    return new BuildLibraryPage.Entry(sequence, row);
  }

  @Test
  void queryCursorBindsRevisionProductFilterAndPageBoundary() {
    var query = new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.ALL);
    var row = entry(5);
    var cursor =
        new BuildLibraryCursor(query.identity(), 3, 5, row.row().group().key().identity(), 25);
    var page = new BuildLibraryPage(query, 25, 3, List.of(row), Optional.of(cursor));
    assertEquals(cursor, page.nextCursor().orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            cursor.requireContext(
                new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.LOCAL), 25, 3));
    assertThrows(IllegalArgumentException.class, () -> cursor.requireContext(query, 25, 4));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuildLibraryPage(query, 25, 4, List.of(row), Optional.of(cursor)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildLibraryPage(
                new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.LOCAL),
                25,
                3,
                List.of(row),
                Optional.empty()));
  }

  @Test
  void oneLatestEventPerRegionAndBoundedProductScopeAreExplicit() {
    var old = reference("us", 1);
    var newer = reference("us", 2);
    var row =
        new BuildLibraryProjection.Row(
            BuildLibraryGrouping.group(List.of(old, newer), 10).getFirst(),
            BuildLibrarySeenPolicy.observe(Optional.empty(), old, false, NOW),
            new BuildLibraryLocalFact(true, false),
            BuildLibraryProjection.Advertisement.UNKNOWN);
    assertThrows(IllegalArgumentException.class, () -> new BuildLibraryPage.Entry(1, row));
    var ticket = new BuildLibraryProductCheckTicket(UUID.randomUUID(), "wow", 10, NOW);
    assertEquals("wow", ticket.productId());
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuildLibraryProductCheckTicket(UUID.randomUUID(), "wow", 0, NOW));
  }

  @Test
  void publicGroupCannotBypassEventRowBoundsOrSharedFacts() {
    var ref = reference("us", 1);
    var duplicate = List.of(ref, ref);
    assertThrows(IllegalArgumentException.class, () -> checkedEntry(duplicate));
    var differentTime =
        new BuildLibraryReference(
            ref.key(),
            ref.eventId(),
            ref.observationSequence(),
            ref.snapshotId(),
            ref.versionsRowOrdinal() + 1,
            ref.region(),
            NOW.plusSeconds(1),
            ref.versions(),
            ref.cdns(),
            ref.selectedCdn());
    assertThrows(IllegalArgumentException.class, () -> checkedEntry(List.of(ref, differentTime)));
    var tooMany = new ArrayList<BuildLibraryReference>();
    for (int ordinal = 0; ordinal < 65; ordinal++) {
      tooMany.add(
          new BuildLibraryReference(
              ref.key(),
              ref.eventId(),
              ref.observationSequence(),
              ref.snapshotId(),
              ordinal,
              ref.region(),
              ref.observedAt(),
              ref.versions(),
              ref.cdns(),
              ref.selectedCdn()));
    }
    assertThrows(IllegalArgumentException.class, () -> checkedEntry(tooMany));
  }

  private static BuildLibraryPage.Entry checkedEntry(List<BuildLibraryReference> refs) {
    var ref = refs.getFirst();
    var group = new BuildLibraryGrouping.Group(ref.key(), refs, ref);
    return new BuildLibraryPage.Entry(
        1,
        new BuildLibraryProjection.Row(
            group,
            BuildLibrarySeenPolicy.observe(Optional.empty(), ref, false, NOW),
            BuildLibraryLocalFact.observationOnly(),
            BuildLibraryProjection.Advertisement.UNKNOWN));
  }
}
