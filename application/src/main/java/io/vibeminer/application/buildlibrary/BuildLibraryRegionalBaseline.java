package io.vibeminer.application.buildlibrary;

import java.time.Instant;
import java.util.*;

/** First explicitly successful scope check; imported history does not establish it. */
public record BuildLibraryRegionalBaseline(
    BuildLibraryScope scope, UUID eventId, long observationSequence, Instant recordedAt) {
  public BuildLibraryRegionalBaseline {
    Objects.requireNonNull(scope);
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(recordedAt);
    if (observationSequence < 1)
      throw new IllegalArgumentException("Positive source observation sequence required");
  }
}
