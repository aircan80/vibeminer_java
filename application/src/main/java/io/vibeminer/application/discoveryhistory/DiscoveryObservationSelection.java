package io.vibeminer.application.discoveryhistory;

import java.util.*;

public record DiscoveryObservationSelection(
    UUID eventId, String snapshotId, int versionsRowOrdinal) {
  public DiscoveryObservationSelection {
    Objects.requireNonNull(eventId);
    snapshotId = HistoryValues.digest(snapshotId);
    HistoryValues.ordinal(versionsRowOrdinal);
  }
}
