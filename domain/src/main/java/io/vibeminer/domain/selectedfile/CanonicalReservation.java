package io.vibeminer.domain.selectedfile;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable canonical slot. RECOVERY may hold prospective bytes that are not yet present in CAS. */
public record CanonicalReservation(
    ContentSlotKey slot,
    String actualEncodingKey,
    long encodedBytes,
    UUID token,
    Status status,
    Optional<ContentRepresentation> representation) {
  public enum Status {
    PENDING,
    PUBLISHING,
    RECOVERY,
    RETAINED
  }

  public CanonicalReservation {
    Objects.requireNonNull(slot);
    Objects.requireNonNull(token);
    Objects.requireNonNull(status);
    Objects.requireNonNull(representation);
    actualEncodingKey = SelectedFileValues.key(actualEncodingKey);
    if (encodedBytes < 1 || status == Status.RETAINED && representation.isEmpty())
      throw new IllegalArgumentException("Invalid canonical reservation");
    if (representation.isPresent()) {
      var value = representation.get();
      if (!value.slot().equals(slot)
          || !value.encoded().source().immutableSourceKey().equals(actualEncodingKey)
          || value.encoded().object().length() != encodedBytes)
        throw new IllegalArgumentException(
            "Canonical receipt disagrees with reserved representation");
    }
  }
}
