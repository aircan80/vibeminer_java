package io.vibeminer.application.lookup;

import io.vibeminer.application.content.MetadataRepairPlan;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Metadata-only proposal. Session proof must be replayed before authorizing a root transfer. */
public record RootPlan(
    String sessionId,
    BuildSnapshot build,
    VerifiedSourceAlias configuration,
    String contentKey,
    long decodedBytes,
    List<RootCandidate> candidates,
    LookupBudgets budgets,
    Optional<MetadataRepairPlan> repair) {
  public RootPlan(
      String sessionId,
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      String contentKey,
      long decodedBytes,
      List<RootCandidate> candidates,
      LookupBudgets budgets) {
    this(
        sessionId,
        build,
        configuration,
        contentKey,
        decodedBytes,
        candidates,
        budgets,
        Optional.empty());
  }

  public RootPlan {
    Objects.requireNonNull(repair);
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
    if (repair.isPresent()) {
      var value = repair.get();
      var request = value.request();
      if (request.kind() != MetadataStructureAttestation.Kind.ROOT
          || !request.build().snapshotId().equals(build.snapshotId())
          || !request.configuration().equals(configuration)
          || !request.slot().contentKey().equals(contentKey)
          || request.declaredDecodedBytes().orElse(-1) != decodedBytes
          || candidates.stream()
              .noneMatch(
                  candidate ->
                      candidate.encodingKey().equals(request.requestedEncodingKey())
                          && candidate.encodedBytes() == request.requestedEncodedBytes())
          || request.requestedEncodedBytes() > budgets.maximumRootEncodedBytes()
          || decodedBytes > budgets.maximumRootDecodedBytes()
          || value.cost().maximumDecodedWorkBytes() > budgets.maximumDecodedWorkBytes()
          || value.cost().maximumPayloadRequests() > (long) budgets.maximumChunks() + 2)
        throw new IllegalArgumentException(
            "Repair consent differs from exact known root plan or its budgets");
    }
  }
}
