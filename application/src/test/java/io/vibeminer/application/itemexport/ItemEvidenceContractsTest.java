package io.vibeminer.application.itemexport;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.item.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ItemEvidenceContractsTest {
  static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

  static ItemPhysicalRow row() {
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var field : ItemEvidenceFixture.item(NOW).definition().interpretation().schema().fields())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              field.field(),
              0,
              8,
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              0,
              field.candidateBits(),
              field.sign()));
    return new ItemPhysicalRow(25, 0, 0, values);
  }

  static ItemEvidenceSelection selection(boolean paired) {
    var item = ItemEvidenceFixture.item(NOW);
    return new ItemEvidenceSelection(
        item,
        25,
        paired
            ? Optional.of(new PreparedItemNames(item, ItemEvidenceFixture.names(NOW)))
            : Optional.empty());
  }

  static ItemEvidenceSnapshot snapshot() {
    return new ItemEvidenceSnapshot(
        selection(false), row(), Optional.empty(), NOW, NOW, "a".repeat(64));
  }

  @Test
  void selectionRequiresPositiveIdAndExactPair() {
    var s = selection(true);
    assertThrows(
        IllegalArgumentException.class, () -> new ItemEvidenceSelection(s.item(), 0, s.names()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ItemEvidenceSelection(s.item(), 0x100000000L, s.names()));
    var changed = ItemEvidenceFixture.item(NOW.plusSeconds(1));
    assertThrows(
        IllegalArgumentException.class, () -> new ItemEvidenceSelection(changed, 25, s.names()));
  }

  @Test
  void snapshotBindsPhysicalRowNamePresenceAndMeasuredInterval() {
    assertEquals(selection(false), snapshot().selection());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemEvidenceSnapshot(
                selection(false),
                row(),
                Optional.of(
                    new ItemNameMatch(
                        25,
                        ItemNameMatch.Outcome.NO_PHYSICAL_MATCH,
                        Optional.empty(),
                        OptionalLong.empty())),
                NOW,
                NOW,
                "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemEvidenceSnapshot(
                selection(true), row(), Optional.empty(), NOW, NOW, "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemEvidenceSnapshot(
                selection(false), row(), Optional.empty(), NOW, NOW.minusNanos(1), "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemEvidenceSnapshot(
                selection(false),
                new ItemPhysicalRow(26, 0, 0, row().values()),
                Optional.empty(),
                NOW,
                NOW,
                "a".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemEvidenceSnapshot(
                selection(false),
                new ItemPhysicalRow(25, 2, 0, row().values()),
                Optional.empty(),
                NOW,
                NOW,
                "a".repeat(64)));
  }

  @Test
  void pairedNameStatesRemainDistinctAndHistoricalProofUnchanged() {
    var s = selection(true);
    var outcomes =
        List.of(
            new ItemNameMatch(
                25,
                ItemNameMatch.Outcome.PHYSICAL_NAME,
                Optional.of(new DisplayCandidate("", 25, 0, 0, 5252, 5, 5)),
                OptionalLong.empty()),
            new ItemNameMatch(
                25, ItemNameMatch.Outcome.COPY_UNINDEXED, Optional.empty(), OptionalLong.of(12)),
            new ItemNameMatch(
                25,
                ItemNameMatch.Outcome.NO_PHYSICAL_MATCH,
                Optional.empty(),
                OptionalLong.empty()));
    for (var name : outcomes) {
      var snapshot =
          new ItemEvidenceSnapshot(
              s,
              row(),
              Optional.of(name),
              NOW.plusSeconds(10),
              NOW.plusSeconds(11),
              "b".repeat(64));
      assertEquals(name, snapshot.name().orElseThrow());
      assertSame(s.item().evidence(), snapshot.selection().item().evidence());
      assertEquals(NOW, snapshot.selection().item().evidence().localVerification().verifiedAt());
    }
  }

  @Test
  void receiptBindsBoundedBytesDigestAndDoesNotInferClockOrdering() {
    assertEquals(
        NOW.minusSeconds(10),
        new ItemEvidenceReceipt(2097152, "f".repeat(64), NOW.minusSeconds(10)).committedAt());
    assertThrows(
        IllegalArgumentException.class, () -> new ItemEvidenceReceipt(0, "a".repeat(64), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ItemEvidenceReceipt(2097153, "a".repeat(64), NOW));
    assertThrows(IllegalArgumentException.class, () -> new ItemEvidenceReceipt(1, "bad", NOW));
  }
}
