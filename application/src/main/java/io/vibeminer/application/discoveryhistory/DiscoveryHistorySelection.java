package io.vibeminer.application.discoveryhistory;

import java.util.Objects;

/**
 * Offline selected observation. Does not grant acquisition authority or claim current existence.
 */
public record DiscoveryHistorySelection(
    DiscoveryObservationSelection selection,
    DiscoveryEvent event,
    DiscoveryEvent.ObservedBuild build) {
  public enum RemoteContentAvailability {
    NOT_CHECKED
  }

  public DiscoveryHistorySelection {
    Objects.requireNonNull(selection);
    Objects.requireNonNull(event);
    Objects.requireNonNull(build);
    if (!selection.eventId().equals(event.eventId())
        || !selection.snapshotId().equals(build.build().snapshotId())
        || selection.versionsRowOrdinal() != build.versionsRowOrdinal()
        || !event.builds().contains(build))
      throw new IllegalArgumentException("Exact event, snapshot and selected row required");
  }

  public RemoteContentAvailability remoteContentAvailability() {
    return RemoteContentAvailability.NOT_CHECKED;
  }
}
