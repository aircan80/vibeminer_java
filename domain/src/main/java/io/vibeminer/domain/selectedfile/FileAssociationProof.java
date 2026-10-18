package io.vibeminer.domain.selectedfile;

import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.Objects;

/** Persisted verifier attestation of the root/EN association; not a constructor-issued proof. */
public record FileAssociationProof(
    ResolvedSourceIdentity rootSource,
    ResolvedSourceIdentity encodingBootstrapSource,
    String verifierRevision,
    String proofDigest) {
  public FileAssociationProof {
    Objects.requireNonNull(rootSource);
    Objects.requireNonNull(encodingBootstrapSource);
    verifierRevision = SelectedFileValues.revision(verifierRevision);
    proofDigest = SelectedFileValues.digest(proofDigest);
  }
}
