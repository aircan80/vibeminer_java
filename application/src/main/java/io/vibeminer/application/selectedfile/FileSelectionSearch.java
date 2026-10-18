package io.vibeminer.application.selectedfile;

import java.util.*;

/** Explicit bounded archive result; incomplete search cannot prove absence. */
public record FileSelectionSearch(
    Optional<SelectedFilePlan> plan,
    int checkedArchives,
    int advertisedArchives,
    boolean complete) {
  public FileSelectionSearch {
    Objects.requireNonNull(plan);
    if (checkedArchives < 0
        || advertisedArchives < checkedArchives
        || complete && plan.isEmpty() && checkedArchives != advertisedArchives)
      throw new IllegalArgumentException("Invalid archive search scope");
  }
}
