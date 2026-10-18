package io.vibeminer.domain.selectedfile;

import java.util.Objects;
import java.util.OptionalLong;

/** All EN alternatives remain visible; unknown size cannot authorize a transfer. */
public record EncodedCandidate(
    String encodingKey, OptionalLong encodedBytes, OptionalLong specificationIndex) {
  public EncodedCandidate {
    encodingKey = SelectedFileValues.key(encodingKey);
    Objects.requireNonNull(encodedBytes);
    Objects.requireNonNull(specificationIndex);
    if (encodedBytes.isPresent() && encodedBytes.getAsLong() < 1
        || specificationIndex.isPresent() && specificationIndex.getAsLong() < 0)
      throw new IllegalArgumentException("Invalid encoding candidate facts");
  }
}
