package io.vibeminer.application.selectedfile;

import java.util.*;

/** A bounded search without a result is conclusive only when complete is true. */
public record ArchiveSearchResult(
    Optional<LocatedFilePlan> located,
    int checkedArchives,
    int advertisedArchives,
    boolean complete) {
  public ArchiveSearchResult {
    Objects.requireNonNull(located);
    if (checkedArchives < 0
        || advertisedArchives < checkedArchives
        || complete && located.isEmpty() && checkedArchives != advertisedArchives)
      throw new IllegalArgumentException("Invalid checked archive scope");
  }
}
