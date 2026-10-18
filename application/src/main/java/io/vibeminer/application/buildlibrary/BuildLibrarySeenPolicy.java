package io.vibeminer.application.buildlibrary;

import java.time.Instant;
import java.util.*;

/**
 * Pure per-group transition. Owner supplies the baseline read before rows INSIDE successful
 * registration, not ticket admission.
 */
public final class BuildLibrarySeenPolicy {
  private BuildLibrarySeenPolicy() {}

  public static BuildLibrarySeenState observe(
      Optional<BuildLibrarySeenState> existing,
      BuildLibraryReference reference,
      boolean scopeHadBaselineBeforeCheck,
      Instant recordedAt) {
    Objects.requireNonNull(existing);
    Objects.requireNonNull(reference);
    Objects.requireNonNull(recordedAt);
    if (existing.isPresent()) {
      var state = existing.get();
      requireGroup(state, reference);
      return state;
    }
    return new BuildLibrarySeenState(
        reference.key().identity(),
        reference.eventId(),
        reference.observationSequence(),
        recordedAt,
        scopeHadBaselineBeforeCheck,
        Optional.empty());
  }

  public static BuildLibrarySeenState acknowledgeOpen(
      BuildLibrarySeenState state, BuildLibraryReference openedReference, Instant openedAt) {
    Objects.requireNonNull(state);
    Objects.requireNonNull(openedReference);
    Objects.requireNonNull(openedAt);
    requireGroup(state, openedReference);
    if (state.acknowledgedAt().isPresent()) return state;
    return new BuildLibrarySeenState(
        state.groupId(),
        state.firstEventId(),
        state.firstObservationSequence(),
        state.firstSeenAt(),
        state.eligibleAsNew(),
        Optional.of(openedAt));
  }

  public static BuildLibraryRegionalBaseline establishFirstSuccessfulBaseline(
      Optional<BuildLibraryRegionalBaseline> existing,
      BuildLibraryReference reference,
      Instant recordedAt) {
    Objects.requireNonNull(existing);
    Objects.requireNonNull(reference);
    Objects.requireNonNull(recordedAt);
    var scope = new BuildLibraryScope(reference.key().productId(), reference.region());
    if (existing.isPresent()) {
      if (!existing.get().scope().equals(scope))
        throw new IllegalArgumentException("Baseline belongs to another checked scope");
      return existing.get();
    }
    return new BuildLibraryRegionalBaseline(
        scope, reference.eventId(), reference.observationSequence(), recordedAt);
  }

  private static void requireGroup(BuildLibrarySeenState state, BuildLibraryReference reference) {
    if (!state.groupId().equals(reference.key().identity()))
      throw new IllegalArgumentException("Seen state belongs to another complete identity");
  }
}
