package io.vibeminer.application.lookup;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.List;
import java.util.Objects;

/** Metadata-only proposal. Session proof must be replayed before authorizing a root transfer. */
public record RootPlan(
    String sessionId,
    BuildSnapshot build,
    VerifiedSourceAlias configuration,
    String contentKey,
    long decodedBytes,
    List<RootCandidate> candidates,
    LookupBudgets budgets) {
  public RootPlan {
    Objects.requireNonNull(build);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(budgets);
    candidates = List.copyOf(candidates);
    if (sessionId == null
        || sessionId.isBlank()
        || sessionId.length() > 256
        || contentKey == null
        || !contentKey.matches("[0-9a-f]{32}")
        || decodedBytes < 1
        || candidates.size() > budgets.maximumCandidates())
      throw new IllegalArgumentException("Invalid root plan");
  }
}
