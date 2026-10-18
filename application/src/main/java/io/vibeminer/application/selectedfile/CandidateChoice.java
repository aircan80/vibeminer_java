package io.vibeminer.application.selectedfile;

import io.vibeminer.domain.selectedfile.EncodedCandidate;
import java.util.Objects;

/** Explicit user selection; membership still requires canonical gateway replay. */
public record CandidateChoice(CandidatePlan plan, EncodedCandidate candidate) {
  public CandidateChoice {
    Objects.requireNonNull(plan);
    Objects.requireNonNull(candidate);
  }
}
