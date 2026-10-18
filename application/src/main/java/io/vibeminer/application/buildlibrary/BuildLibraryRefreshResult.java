package io.vibeminer.application.buildlibrary;

import java.util.*;

/** Actual committed registrations and precise failed phases; partial success is never discarded. */
public record BuildLibraryRefreshResult(
    BuildLibraryProductCheckTicket ticket,
    BuildLibraryProductObservation observation,
    List<BuildLibraryRegistration> registrations,
    List<ScopeFailure> failures,
    boolean cancellationRequested) {
  public enum Phase {
    CAPTURE_INTERPRETATION,
    HISTORY_APPEND,
    LIBRARY_REGISTRATION,
    CHECK_RECORDING
  }

  public record ScopeFailure(BuildLibraryScope scope, Phase phase, Throwable cause) {
    public ScopeFailure {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(phase);
      Objects.requireNonNull(cause);
    }
  }

  public BuildLibraryRefreshResult {
    Objects.requireNonNull(ticket);
    Objects.requireNonNull(observation);
    registrations = List.copyOf(registrations);
    failures = List.copyOf(failures);
    if (!ticket.productId().equals(observation.productId())
        || registrations.size() > 64
        || failures.size() > 128)
      throw new IllegalArgumentException("Bounded exact product result required");
    var eventRegions = new HashMap<UUID, String>();
    observation.events().forEach(event -> eventRegions.put(event.eventId(), event.region()));
    var registered = new HashSet<UUID>();
    for (var registration : registrations) {
      var check =
          registration
              .check()
              .orElseThrow(
                  () -> new IllegalArgumentException("Refresh requires a successful check"));
      if (registration.kind() != BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK
          || !registered.add(registration.eventId())
          || !check.scope().productId().equals(ticket.productId())
          || !check.scope().region().equals(eventRegions.get(registration.eventId()))
          || check.ordinal() != ticket.ordinal())
        throw new IllegalArgumentException("Registration changed product capture or admission");
    }
    for (var failure : failures)
      if (!failure.scope().productId().equals(ticket.productId()))
        throw new IllegalArgumentException("Failure changed product scope");
  }
}
