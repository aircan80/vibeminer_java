package io.vibeminer.infrastructure.lookup;

import io.vibeminer.infrastructure.acquisition.*;

import java.util.concurrent.CompletableFuture;

/**
 * Infrastructure-only deterministic seam; production delegates to the strict official transports.
 */
interface LookupTransport {
    CompletableFuture<AcquiredBytes> configuration(CdnRequest request);

    CompletableFuture<AcquiredRange> range(CdnRangeRequest request);
}
