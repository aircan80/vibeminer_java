package io.vibeminer.application.discoveryhistory;

/** Stable local recording order; neither source wall time nor game chronology. */
public record DiscoveryHistoryCursor(
    String filterIdentity, long upperSequence, long beforeSequence, int pageSize) {
  public DiscoveryHistoryCursor {
    filterIdentity = HistoryValues.digest(filterIdentity);
    HistoryValues.pageSize(pageSize);
    if (upperSequence < 1 || beforeSequence < 1 || beforeSequence > upperSequence)
      throw new IllegalArgumentException("Valid bounded history watermark required");
  }

  public void requireContext(DiscoveryHistoryFilter filter, int requestedPageSize) {
    if (filter == null
        || !filterIdentity.equals(filter.identity())
        || pageSize != requestedPageSize)
      throw new IllegalArgumentException("History cursor belongs to another query");
  }
}
