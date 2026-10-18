package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.discoveryhistory.*;
import java.time.Instant;
import java.util.*;

/** Body-free immutable regional receipt; canonical first workspace provenance is not rewritten. */
public record BuildLibraryReference(
    BuildReferenceGroupKey key,
    UUID eventId,
    long observationSequence,
    String snapshotId,
    int versionsRowOrdinal,
    String region,
    Instant observedAt,
    DiscoveryEventSummary.DocumentReceipt versions,
    DiscoveryEventSummary.DocumentReceipt cdns,
    DiscoveryEvent.CdnRow selectedCdn) {
  public BuildLibraryReference {
    Objects.requireNonNull(key);
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(versions);
    Objects.requireNonNull(cdns);
    Objects.requireNonNull(selectedCdn);
    snapshotId = BuildLibraryValues.digest(snapshotId);
    new DiscoveryHistoryFilter(key.productId(), Optional.of(region), Optional.empty());
    var base = "https://us.version.battle.net/v2/products/" + key.productId();
    if (observationSequence < 1
        || versionsRowOrdinal < 0
        || versionsRowOrdinal >= 10000
        || !snapshotId.equals(key.snapshotIdFor(region))
        || !region.equals(selectedCdn.values().get("Name"))
        || selectedCdn.values().getOrDefault("Path", "").isBlank()
        || selectedCdn.values().getOrDefault("Hosts", "").isBlank()
        || !versions.sourceUrl().equals(base + "/versions")
        || !cdns.sourceUrl().equals(base + "/cdns"))
      throw new IllegalArgumentException(
          "Exact event, regional identity and document roles required");
  }

  public static List<BuildLibraryReference> from(DiscoveryEvent event, long sequence) {
    Objects.requireNonNull(event);
    var versions = DiscoveryEventSummary.DocumentReceipt.from(event.versions().source());
    var cdns = DiscoveryEventSummary.DocumentReceipt.from(event.cdns().source());
    return event.builds().stream()
        .map(
            row ->
                new BuildLibraryReference(
                    BuildReferenceGroupKey.from(row),
                    event.eventId(),
                    sequence,
                    row.build().snapshotId(),
                    row.versionsRowOrdinal(),
                    event.region(),
                    event.completedAt(),
                    versions,
                    cdns,
                    event.selectedCdn()))
        .toList();
  }
}
