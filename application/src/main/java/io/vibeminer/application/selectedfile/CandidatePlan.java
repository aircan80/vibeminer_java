package io.vibeminer.application.selectedfile;

import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;

/** Gateway-issued bounded session attesting the exact retained root/EN association. */
public record CandidatePlan(
    String sessionId,
    FileVariantAssociation variant,
    VerifiedSourceAlias configuration,
    VerifiedSourceAlias root,
    FileAssociationProof proof,
    List<EncodedCandidate> candidates,
    SelectedFileBudgets budgets) {
  public CandidatePlan {
    if (sessionId == null || sessionId.isBlank() || sessionId.length() > 256)
      throw new IllegalArgumentException("Bounded session identity required");
    Objects.requireNonNull(variant);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(root);
    Objects.requireNonNull(proof);
    Objects.requireNonNull(budgets);
    candidates = List.copyOf(candidates);
    if (candidates.isEmpty()
        || candidates.size() > budgets.lookup().maximumCandidates()
        || !variant.rootSource().equals(root.source())
        || !proof.rootSource().equals(root.source())
        || candidates.stream().map(EncodedCandidate::encodingKey).distinct().count()
            != candidates.size())
      throw new IllegalArgumentException("Inconsistent selected-file candidate plan");
  }
}
