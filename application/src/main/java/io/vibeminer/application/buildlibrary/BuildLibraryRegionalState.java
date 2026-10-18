package io.vibeminer.application.buildlibrary;

import java.time.Instant;
import java.util.*;

/**
 * Last checked scope facts. Failed/canceled checks preserve prior evidence without claiming
 * presence now.
 */
public record BuildLibraryRegionalState(
    BuildLibraryScope scope,
    CheckState checkState,
    Optional<UUID> latestSuccessfulEventId,
    Set<String> lastSuccessfulGroupIds,
    Optional<Instant> lastCheckAt) {
  public enum CheckState {
    NOT_CHECKED,
    SUCCESS,
    FAILED,
    CANCELLED
  }

  public BuildLibraryRegionalState {
    Objects.requireNonNull(scope);
    Objects.requireNonNull(checkState);
    Objects.requireNonNull(latestSuccessfulEventId);
    lastSuccessfulGroupIds = Set.copyOf(lastSuccessfulGroupIds);
    Objects.requireNonNull(lastCheckAt);
    lastSuccessfulGroupIds.forEach(BuildLibraryValues::digest);
    if (lastSuccessfulGroupIds.size() > 64
        || latestSuccessfulEventId.isPresent() != !lastSuccessfulGroupIds.isEmpty()
        || checkState == CheckState.SUCCESS && latestSuccessfulEventId.isEmpty()
        || checkState != CheckState.NOT_CHECKED && lastCheckAt.isEmpty())
      throw new IllegalArgumentException(
          "Checked scope must preserve exact successful evidence and explicit outcome");
  }
}
