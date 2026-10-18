package io.vibeminer.application.itemexport;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.item.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ItemEvidenceCoverageTest {
  @Test
  void copyOutcomeCannotExceedZeroDeclaredCopies() {
    var original = ItemEvidenceContractsTest.selection(true);
    var pair = original.names().orElseThrow();
    var n = pair.names();
    var c = n.coverage();
    var names =
        new PreparedSparseNames(
            n.definition(),
            n.evidence(),
            new PreparedSparseNames.Coverage(
                c.sectionOrdinal(),
                c.physicalRows(),
                0,
                c.excludedSections(),
                c.projectedUtf8Bytes()));
    var selection =
        new ItemEvidenceSelection(
            original.item(), 25, Optional.of(new PreparedItemNames(original.item(), names)));
    var copy =
        new ItemNameMatch(
            25, ItemNameMatch.Outcome.COPY_UNINDEXED, Optional.empty(), OptionalLong.of(12));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemEvidenceSnapshot(
                selection,
                ItemEvidenceContractsTest.row(),
                Optional.of(copy),
                ItemEvidenceContractsTest.NOW,
                ItemEvidenceContractsTest.NOW,
                "a".repeat(64)));
  }

  @Test
  void nameCannotExceedTrustedUtf8CoverageWhileItsRecordGeometryFits() {
    var original = ItemEvidenceContractsTest.selection(true);
    var pair = original.names().orElseThrow();
    var n = pair.names();
    var c = n.coverage();
    var names =
        new PreparedSparseNames(
            n.definition(),
            n.evidence(),
            new PreparedSparseNames.Coverage(
                c.sectionOrdinal(),
                c.physicalRows(),
                c.unindexedCopyDeclarations(),
                c.excludedSections(),
                0));
    var selection =
        new ItemEvidenceSelection(
            original.item(), 25, Optional.of(new PreparedItemNames(original.item(), names)));
    var match =
        new ItemNameMatch(
            25,
            ItemNameMatch.Outcome.PHYSICAL_NAME,
            Optional.of(
                new DisplayCandidate(
                    "x", 25, 0, 0, n.definition().interpretation().recordAreaOffset(), 6, 6)),
            OptionalLong.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemEvidenceSnapshot(
                selection,
                ItemEvidenceContractsTest.row(),
                Optional.of(match),
                ItemEvidenceContractsTest.NOW,
                ItemEvidenceContractsTest.NOW,
                "a".repeat(64)));
  }
}
