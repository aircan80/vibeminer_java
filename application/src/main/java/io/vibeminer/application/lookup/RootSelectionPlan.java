package io.vibeminer.application.lookup;

import java.util.Objects;

/** Explicit authorization of one known-size candidate, subject to canonical proof validation. */
public record RootSelectionPlan(RootPlan plan, RootCandidate candidate) {
  public RootSelectionPlan {
    Objects.requireNonNull(plan);
    Objects.requireNonNull(candidate);
    if (!plan.candidates().contains(candidate) || candidate.encodedBytes() < 1)
      throw new IllegalArgumentException("Select a known-size candidate from this plan");
  }
}
