package io.vibeminer.domain.item;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Exact result only within indexed physical rows; no whole-table absence or resolved copy claim.
 */
public record ItemLookupResult(
    long requestedId,
    Outcome outcome,
    Optional<ItemPhysicalRow> row,
    OptionalLong copySourceId,
    PreparedItemProjection.Coverage coverage) {
  public enum Outcome {
    PHYSICAL_MATCH,
    COPY_UNINDEXED,
    NO_PHYSICAL_MATCH
  }

  public ItemLookupResult {
    ItemValues.id(requestedId);
    Objects.requireNonNull(outcome);
    Objects.requireNonNull(row);
    Objects.requireNonNull(copySourceId);
    Objects.requireNonNull(coverage);
    if (row.isPresent()
        && (row.get().recordId() != requestedId
            || row.get().sectionOrdinal() != coverage.sectionOrdinal()
            || row.get().physicalOrdinal() >= coverage.physicalRows()))
      throw new IllegalArgumentException("Physical row differs from requested admitted scope");
    if (copySourceId.isPresent()) ItemValues.id(copySourceId.getAsLong());
    if (outcome == Outcome.PHYSICAL_MATCH && (row.isEmpty() || copySourceId.isPresent())
        || outcome == Outcome.COPY_UNINDEXED && (row.isPresent() || copySourceId.isEmpty())
        || outcome == Outcome.NO_PHYSICAL_MATCH && (row.isPresent() || copySourceId.isPresent()))
      throw new IllegalArgumentException(
          "Lookup outcome cannot manufacture a row/copy declaration");
  }
}
