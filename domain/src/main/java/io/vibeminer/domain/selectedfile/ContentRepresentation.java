package io.vibeminer.domain.selectedfile;

import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.Objects;

/**
 * Canonical encoded representation and its original acquisition receipt; decoded proof is absent.
 */
public record ContentRepresentation(
    ContentSlotKey slot,
    VerifiedSourceAlias encoded,
    EncodedScope scope,
    EncodedSourceReceipt sourceReceipt) {
  public static final String SOURCE_NAMESPACE = "wow.blte.encoded";
  public static final String VERIFIER_SEMANTICS = "blte-complete-encoded-v1";

  public static ResolvedSourceIdentity sourceIdentity(String encodingKey) {
    return new ResolvedSourceIdentity(
        SOURCE_NAMESPACE, SelectedFileValues.key(encodingKey), VERIFIER_SEMANTICS);
  }

  public static ResolvedSourceIdentity encodedIdentity(String encodingKey) {
    return sourceIdentity(encodingKey);
  }

  public ContentRepresentation {
    Objects.requireNonNull(slot);
    Objects.requireNonNull(encoded);
    Objects.requireNonNull(scope);
    Objects.requireNonNull(sourceReceipt);
    if (!encoded.source().equals(sourceIdentity(encoded.source().immutableSourceKey()))
        || encoded.object().length() != sourceReceipt.childEncodedBytes()
        || !encoded.evidence().sourceUrl().equals(sourceReceipt.actualResourceUrl()))
      throw new IllegalArgumentException("Encoded receipt/source/object mismatch");
    if (sourceReceipt.kind() == EncodedSourceReceipt.Kind.LOOSE
        && !sourceReceipt.resourceKey().equals(encoded.source().immutableSourceKey()))
      throw new IllegalArgumentException("Loose receipt identifies a different EKey");
  }
}
