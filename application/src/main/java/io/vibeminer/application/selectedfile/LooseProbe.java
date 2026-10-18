package io.vibeminer.application.selectedfile;

import java.util.*;

/** Exactly one canonical loose locator or explicitly typed HTTP-404 archive permission. */
public record LooseProbe(Optional<LocatedFilePlan> located, Optional<Loose404Permit> missing) {
  public LooseProbe {
    Objects.requireNonNull(located);
    Objects.requireNonNull(missing);
    if (located.isPresent() == missing.isPresent())
      throw new IllegalArgumentException("Exactly one loose outcome required");
  }
}
