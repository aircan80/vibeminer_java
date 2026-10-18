package io.vibeminer.application.discoveryhistory;

import java.util.*;

public record DiscoveryHistoryPage(
    DiscoveryHistoryFilter filter,
    List<DiscoveryEventSummary> events,
    Optional<DiscoveryHistoryCursor> nextCursor) {
  public DiscoveryHistoryPage {
    Objects.requireNonNull(filter);
    events = List.copyOf(events);
    Objects.requireNonNull(nextCursor);
    if (events.size() > 100) throw new IllegalArgumentException("Bounded history page required");
    long previous = Long.MAX_VALUE;
    var ids = new HashSet<UUID>();
    for (var event : events) {
      if (event.sequence() > previous
          || (!ids.isEmpty() && event.sequence() == previous)
          || !ids.add(event.eventId())
          || !filter.productId().equals(event.productId())
          || filter.region().isPresent() && !filter.region().get().equals(event.region())
          || filter.channel().isPresent()
              && event.builds().stream().noneMatch(b -> b.channel().equals(filter.channel().get())))
        throw new IllegalArgumentException(
            "History page is not an ordered exact-filter observation list");
      previous = event.sequence();
    }
    if (nextCursor.isPresent()) {
      var cursor = nextCursor.get();
      cursor.requireContext(filter, cursor.pageSize());
      if (events.isEmpty()
          || events.size() > cursor.pageSize()
          || cursor.beforeSequence() != events.getLast().sequence()
          || events.getFirst().sequence() > cursor.upperSequence())
        throw new IllegalArgumentException("Next cursor differs from page boundary/watermark");
    }
  }
}
