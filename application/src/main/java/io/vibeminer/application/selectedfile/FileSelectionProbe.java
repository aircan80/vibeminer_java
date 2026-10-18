package io.vibeminer.application.selectedfile;

import java.util.*;

/** User-facing loose lookup/reuse result; a missing permit authorizes a separate archive action. */
public record FileSelectionProbe(
    Optional<SelectedFilePlan> plan, Optional<Loose404Permit> missing) {
  public FileSelectionProbe {
    Objects.requireNonNull(plan);
    Objects.requireNonNull(missing);
    if (plan.isPresent() == missing.isPresent())
      throw new IllegalArgumentException("Exactly one probe outcome required");
  }
}
