package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.discoveryhistory.DiscoveryHistoryFilter;
import java.time.Instant;
import java.util.*;

/** Global durable admission order reserved BEFORE paired metadata capture discovers regions. */
public record BuildLibraryProductCheckTicket(
    UUID attemptId, String productId, long ordinal, Instant requestedAt) {
  public BuildLibraryProductCheckTicket {
    Objects.requireNonNull(attemptId);
    Objects.requireNonNull(requestedAt);
    new DiscoveryHistoryFilter(productId, Optional.empty(), Optional.empty());
    if (ordinal < 1) throw new IllegalArgumentException("Positive admission ordinal required");
  }
}
