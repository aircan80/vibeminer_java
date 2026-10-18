package io.vibeminer.domain.content;

import io.vibeminer.domain.StoredObject;

/**
 * Exactly two concrete ledger attestations; neither construction nor persistence verifies bytes.
 */
public sealed interface CanonicalContentProof permits EncodedContentProof, MetadataContentProof {
  StoredObject object();

  String actualEncodingKey();

  String contentKey();

  default long encodedBytes() {
    return object().length();
  }
}
