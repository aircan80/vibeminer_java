package io.vibeminer.application.buildlibrary;

import java.time.Instant;
import java.util.*;

/**
 * Local observation and explicit Open acknowledgement, never publisher age or download readiness.
 */
public record BuildLibrarySeenState(
    String groupId,
    UUID firstEventId,
    long firstObservationSequence,
    Instant firstSeenAt,
    boolean eligibleAsNew,
    Optional<Instant> acknowledgedAt) {
  public BuildLibrarySeenState {
    groupId = BuildLibraryValues.digest(groupId);
    Objects.requireNonNull(firstEventId);
    Objects.requireNonNull(firstSeenAt);
    Objects.requireNonNull(acknowledgedAt);
    if (firstObservationSequence < 1)
      throw new IllegalArgumentException("Positive observed sequence required");
  }

  public boolean isNew() {
    return eligibleAsNew && acknowledgedAt.isEmpty();
  }
}
