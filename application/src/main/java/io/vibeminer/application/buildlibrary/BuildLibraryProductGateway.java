package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.CancellationToken;

/**
 * Fetches versions/CDNs once, then interprets actual regional rows without additional retrievals.
 */
public interface BuildLibraryProductGateway {
  BuildLibraryProductObservation observeProduct(String productId, CancellationToken cancellation);
}
