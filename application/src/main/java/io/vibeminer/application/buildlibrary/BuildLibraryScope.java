package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.discoveryhistory.DiscoveryHistoryFilter;
import java.util.Optional;

/** One actual checked publication region, independent of research locale. */
public record BuildLibraryScope(String productId, String region) {
  public BuildLibraryScope {
    new DiscoveryHistoryFilter(productId, Optional.of(region), Optional.empty());
  }
}
