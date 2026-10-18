package io.vibeminer.application.buildlibrary;

import java.time.Instant;
import java.util.*;

/**
 * Immutable projection registration. An imported event can never be promoted into a fresh check.
 */
public record BuildLibraryRegistration(
    UUID eventId,
    Kind kind,
    Optional<BuildLibraryCheckTicket> check,
    Instant recordedAt,
    List<BuildLibraryReference> references) {
  public enum Kind {
    HISTORICAL_IMPORT,
    SUCCESSFUL_CHECK
  }

  public BuildLibraryRegistration {
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(check);
    Objects.requireNonNull(recordedAt);
    references = List.copyOf(references);
    if (references.isEmpty()
        || references.size() > 64
        || (kind == Kind.SUCCESSFUL_CHECK) != check.isPresent())
      throw new IllegalArgumentException("Registration kind and exact successful check required");
    var first = references.getFirst();
    int previous = -1;
    for (var ref : references) {
      if (!ref.eventId().equals(eventId)
          || ref.observationSequence() != first.observationSequence()
          || !ref.key().productId().equals(first.key().productId())
          || !ref.region().equals(first.region())
          || !ref.observedAt().equals(first.observedAt())
          || !ref.versions().equals(first.versions())
          || !ref.cdns().equals(first.cdns())
          || !ref.selectedCdn().equals(first.selectedCdn())
          || ref.versionsRowOrdinal() <= previous
          || check.isPresent()
              && (!check.get().scope().productId().equals(ref.key().productId())
                  || !check.get().scope().region().equals(ref.region())))
        throw new IllegalArgumentException("Registration changed exact source event/scope/order");
      previous = ref.versionsRowOrdinal();
    }
  }
}
