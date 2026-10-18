package io.vibeminer.application.selectedfile;

import java.util.Objects;

/** Canonical gateway session issued only from an exact loose HTTP 404 observation. */
public record Loose404Permit(String sessionId, CandidateChoice choice) {
  public Loose404Permit {
    if (sessionId == null || sessionId.isBlank() || sessionId.length() > 256)
      throw new IllegalArgumentException("Bounded missing-loose session required");
    Objects.requireNonNull(choice);
  }
}
