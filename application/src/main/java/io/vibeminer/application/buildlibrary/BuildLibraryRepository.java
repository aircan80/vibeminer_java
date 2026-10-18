package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.CancellationToken;
import java.time.Instant;
import java.util.*;

/**
 * Narrow durable observation projection, not acquisition or user-managed workspace storage.
 * Implementations validate the committed history event using the SAME catalog transaction.
 */
public interface BuildLibraryRepository {
  enum Failure {
    FAILED,
    CANCELLED
  }

  BuildLibraryCheckTicket beginCheck(
      BuildLibraryScope scope, Instant requestedAt, CancellationToken token);

  /**
   * Same event/kind/ticket replays original result/time. Different kind/ticket conflicts; imports
   * cannot seed baselines/advertisements. The baseline is read INSIDE the atomic successful
   * registration transaction, before processing any row, not when beginCheck admits the ticket.
   * That single pre-registration fact decides New for every newly seen group, then the first
   * baseline is established. If B commits the first baseline before stale A registers a novel
   * group, A's group is New, but A still cannot overwrite B's newer advertisement outcome. Stale
   * ticket success can register evidence/firstSeen but cannot replace a newer scope check. Each
   * ticket finishes once; facade cancellation must not finish an actual running check.
   */
  BuildLibraryRegistration recordObservation(
      UUID eventId,
      BuildLibraryRegistration.Kind kind,
      Optional<BuildLibraryCheckTicket> check,
      Instant recordedAt,
      CancellationToken token);

  /**
   * Preserve last success, never establish a baseline; stale outcomes cannot replace newer checks.
   */
  BuildLibraryRegionalState recordFailedScope(
      BuildLibraryCheckTicket check, Failure failure, Instant checkedAt, CancellationToken token);

  Optional<BuildLibrarySeenState> findSeen(String groupId, CancellationToken token);

  /** Validate the registered exact reference; only successful Open admission invokes this. */
  BuildLibrarySeenState acknowledgeOpen(
      BuildLibraryReference exactOpenedReference, Instant openedAt, CancellationToken token);

  Optional<BuildLibraryRegionalBaseline> baseline(BuildLibraryScope scope, CancellationToken token);

  BuildLibraryRegionalState regionalState(BuildLibraryScope scope, CancellationToken token);
}
