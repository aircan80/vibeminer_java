package io.vibeminer.domain.selectedfile;

import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Persisted root/EN association attestation, never a constructor-issued decoded-content proof. */
public record FileAssociationProof(
    ResolvedSourceIdentity rootSource,
    ResolvedSourceIdentity encodingBootstrapSource,
    String verifierRevision,
    String proofDigest,
    OptionalLong declaredDecodedBytes,
    List<EncodedCandidate> authenticatedCandidates) {
  /** Legacy metadata has no reusable current candidate facts; production must revalidate it. */
  public FileAssociationProof(
      ResolvedSourceIdentity rootSource,
      ResolvedSourceIdentity encodingBootstrapSource,
      String verifierRevision,
      String proofDigest) {
    this(
        rootSource,
        encodingBootstrapSource,
        verifierRevision,
        proofDigest,
        OptionalLong.empty(),
        List.of());
  }

  public FileAssociationProof {
    Objects.requireNonNull(rootSource);
    Objects.requireNonNull(encodingBootstrapSource);
    verifierRevision = SelectedFileValues.revision(verifierRevision);
    proofDigest = SelectedFileValues.digest(proofDigest);
    Objects.requireNonNull(declaredDecodedBytes);
    authenticatedCandidates = List.copyOf(authenticatedCandidates);
    if (declaredDecodedBytes.isPresent() && declaredDecodedBytes.getAsLong() < 0
        || authenticatedCandidates.size() > 255)
      throw new IllegalArgumentException("Invalid bounded EN declaration facts");
    var keys = new HashSet<String>();
    for (var candidate : authenticatedCandidates)
      if (!keys.add(candidate.encodingKey()))
        throw new IllegalArgumentException("Duplicate authenticated encoding candidate");
  }
}
