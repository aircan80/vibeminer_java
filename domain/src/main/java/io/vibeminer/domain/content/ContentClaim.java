package io.vibeminer.domain.content;

import io.vibeminer.domain.selectedfile.CanonicalReservation;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Capability-neutral view of the single authoritative content reservation header. */
public record ContentClaim(
    ContentSlotKey slot,
    String actualEncodingKey,
    long encodedBytes,
    UUID token,
    CanonicalReservation.Status status,
    Optional<CanonicalContentProof> proof) {
  public ContentClaim {
    Objects.requireNonNull(slot);
    Objects.requireNonNull(token);
    Objects.requireNonNull(status);
    Objects.requireNonNull(proof);
    actualEncodingKey = ContentValues.key(actualEncodingKey);
    if (encodedBytes < 1 || status == CanonicalReservation.Status.RETAINED && proof.isEmpty())
      throw new IllegalArgumentException("Invalid retained content claim");
    if (proof.isPresent()) {
      var value = proof.get();
      if (!value.actualEncodingKey().equals(actualEncodingKey)
          || !value.contentKey().equals(slot.contentKey())
          || value.encodedBytes() != encodedBytes)
        throw new IllegalArgumentException("Claim header and concrete proof disagree");
      if (value instanceof EncodedContentProof encoded && !encoded.receipt().slot().equals(slot))
        throw new IllegalArgumentException(
            "Encoded receipt belongs to a different content namespace");
    }
  }
}
