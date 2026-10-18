package io.vibeminer.domain.content;

import java.util.Objects;
import java.util.UUID;

/** Actual publisher authority, distinct from the durable reservation and passive consumers. */
public record ContentWorkerPermit(UUID reservationToken, UUID workerNonce) {
  public ContentWorkerPermit {
    Objects.requireNonNull(reservationToken);
    Objects.requireNonNull(workerNonce);
  }
}
