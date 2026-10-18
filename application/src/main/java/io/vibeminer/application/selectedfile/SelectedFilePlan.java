package io.vibeminer.application.selectedfile;

import io.vibeminer.domain.selectedfile.ContentRepresentation;
import java.util.*;

/** Explicit payload consent: one canonical new locator or one actual retained representation. */
public record SelectedFilePlan(
    CandidateChoice choice,
    Optional<LocatedFilePlan> acquisition,
    Optional<ContentRepresentation> reuse,
    AcquisitionCost cost) {
  public SelectedFilePlan {
    Objects.requireNonNull(choice);
    Objects.requireNonNull(acquisition);
    Objects.requireNonNull(reuse);
    Objects.requireNonNull(cost);
    if (acquisition.isPresent() == reuse.isPresent())
      throw new IllegalArgumentException(
          "Exactly one acquisition or retained representation required");
  }
}
