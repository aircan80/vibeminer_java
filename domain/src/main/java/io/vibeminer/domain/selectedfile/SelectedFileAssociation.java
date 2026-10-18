package io.vibeminer.domain.selectedfile;

import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.Objects;

/** Immutable per-build association, committed only for its surviving consumer after publication. */
public record SelectedFileAssociation(
    SelectedFileDescriptor descriptor,
    VerifiedSourceAlias configuration,
    VerifiedSourceAlias root,
    FileAssociationProof proof,
    ContentRepresentation representation) {
  public SelectedFileAssociation {
    Objects.requireNonNull(descriptor);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(root);
    Objects.requireNonNull(proof);
    Objects.requireNonNull(representation);
    if (!descriptor.association().rootSource().equals(root.source())
        || !proof.rootSource().equals(root.source())
        || !representation.slot().equals(descriptor.association().slot())
        || !representation
            .encoded()
            .source()
            .immutableSourceKey()
            .equals(descriptor.actualEncodingKey()))
      throw new IllegalArgumentException("Selected association source/context mismatch");
  }

  public String snapshotId() {
    return descriptor.association().build().snapshotId();
  }

  public String role() {
    return descriptor.role();
  }
}
