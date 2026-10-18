package io.vibeminer.application.content;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One immutable audit observation; terminal failures carry no invented successful transfer. */
public record MetadataRepairAuditEvent(
    int sequence, Kind kind, Instant recordedAt, Optional<MetadataRepairObservation> observation) {
  public enum Kind {
    PROSPECTIVE,
    STRUCTURE,
    COMPLETED,
    CONCLUSIVELY_UNPUBLISHED,
    UNCERTAIN
  }

  public MetadataRepairAuditEvent {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(recordedAt);
    Objects.requireNonNull(observation);
    boolean successful =
        kind == Kind.PROSPECTIVE || kind == Kind.STRUCTURE || kind == Kind.COMPLETED;
    if (sequence < 1 || sequence > 4 || successful != observation.isPresent())
      throw new IllegalArgumentException("Invalid bounded repair audit event");
  }
}
