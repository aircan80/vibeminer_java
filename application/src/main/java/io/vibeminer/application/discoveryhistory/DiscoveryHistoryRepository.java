package io.vibeminer.application.discoveryhistory;

import io.vibeminer.application.CancellationToken;
import java.util.*;

/** One atomic metadata catalog; no acquisition, automatic pruning or arbitrary-config lookup. */
public interface DiscoveryHistoryRepository {
  /**
   * Validate/replay sameUUID before quotas. Commit may win cancellation; return actual stored
   * event.
   */
  DiscoveryEvent append(
      DiscoveryEvent event, DiscoveryHistoryLimits limits, CancellationToken cancellation);

  DiscoveryHistoryPage page(
      DiscoveryHistoryFilter filter,
      Optional<DiscoveryHistoryCursor> cursor,
      int pageSize,
      CancellationToken cancellation);

  Optional<DiscoveryEvent> read(UUID eventId, CancellationToken cancellation);
}
