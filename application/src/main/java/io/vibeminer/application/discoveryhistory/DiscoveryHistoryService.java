package io.vibeminer.application.discoveryhistory;

import java.util.*;
import java.util.concurrent.CompletionStage;

/** Explicit refresh/persistence and bounded offline browsing. Resources remain caller-owned. */
public interface DiscoveryHistoryService extends AutoCloseable {
  RefreshOperation refreshObservation(String productId, String region);

  CompletionStage<DiscoveryHistoryPage> history(
      DiscoveryHistoryFilter filter, Optional<DiscoveryHistoryCursor> cursor, int pageSize);

  CompletionStage<DiscoveryHistorySelection> select(DiscoveryObservationSelection selection);

  interface RefreshOperation {
    CompletionStage<DiscoveryEvent> completion();

    void requestCancellation();
  }

  @Override
  void close();
}
