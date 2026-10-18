package io.vibeminer.infrastructure.discovery;

import io.vibeminer.domain.BuildSnapshot;
import java.time.Instant;
import java.util.List;

/** Complete source evidence retained until the persistence use case consumes it. */
public record DiscoveryObservation(String productId, String region, Instant observedAt,
        CapturedMetadata versions, CapturedMetadata cdns, MetadataDocument versionsDocument,
        MetadataDocument cdnsDocument, List<BuildSnapshot> builds) {
    public DiscoveryObservation { builds = List.copyOf(builds); }
}
