package io.vibeminer.application.discoveryhistory;

import io.vibeminer.application.CancellationToken;

/**
 * Explicit bounded official retrieval; returns its exact batch, never a shared last observation.
 */
@FunctionalInterface
public interface DiscoveryObservationGateway {
  DiscoveryEvent observe(String productId, String region, CancellationToken cancellation);
}
