package io.vibeminer.application.buildlibrary;

import java.time.Instant;
import java.util.*;

/** Durable per-scope request admission order, never wall-clock/publisher chronology. */
public record BuildLibraryCheckTicket(
    UUID attemptId, BuildLibraryScope scope, long ordinal, Instant requestedAt) {
  public BuildLibraryCheckTicket {
    Objects.requireNonNull(attemptId);
    Objects.requireNonNull(scope);
    Objects.requireNonNull(requestedAt);
    if (ordinal < 1)
      throw new IllegalArgumentException("Positive scope admission ordinal required");
  }
}
