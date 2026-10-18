package io.vibeminer.domain.item;

import java.util.*;

/** Physical empty names, unindexed copies and scoped nonmatches remain distinct. */
public record ItemNameMatch(
    long requestedId,
    Outcome outcome,
    Optional<DisplayCandidate> candidate,
    OptionalLong copySourceId) {
  public enum Outcome {
    PHYSICAL_NAME,
    COPY_UNINDEXED,
    NO_PHYSICAL_MATCH
  }

  public ItemNameMatch {
    ItemValues.id(requestedId);
    if (requestedId == 0) throw new IllegalArgumentException("Positive Item ID required");
    Objects.requireNonNull(outcome);
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(copySourceId);
    if (copySourceId.isPresent()) {
      ItemValues.id(copySourceId.getAsLong());
      if (copySourceId.getAsLong() == 0)
        throw new IllegalArgumentException("Positive copy source required");
    }
    if (candidate.isPresent() && candidate.get().physicalRecordId() != requestedId
        || outcome == Outcome.PHYSICAL_NAME && (candidate.isEmpty() || copySourceId.isPresent())
        || outcome == Outcome.COPY_UNINDEXED && (candidate.isPresent() || copySourceId.isEmpty())
        || outcome == Outcome.NO_PHYSICAL_MATCH
            && (candidate.isPresent() || copySourceId.isPresent()))
      throw new IllegalArgumentException("Name outcome cannot manufacture a physical/copy value");
  }
}
