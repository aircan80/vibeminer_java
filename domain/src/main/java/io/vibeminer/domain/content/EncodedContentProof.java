package io.vibeminer.domain.content;

import io.vibeminer.domain.StoredObject;
import io.vibeminer.domain.selectedfile.ContentRepresentation;
import java.util.Objects;

/** Existing encoded-only receipt retained without manufacturing metadata capabilities. */
public record EncodedContentProof(ContentRepresentation receipt) implements CanonicalContentProof {
  public EncodedContentProof {
    Objects.requireNonNull(receipt);
  }

  @Override
  public StoredObject object() {
    return receipt.encoded().object();
  }

  @Override
  public String actualEncodingKey() {
    return receipt.encoded().source().immutableSourceKey();
  }

  @Override
  public String contentKey() {
    return receipt.slot().contentKey();
  }
}
