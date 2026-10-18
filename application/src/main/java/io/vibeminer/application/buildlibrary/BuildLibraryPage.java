package io.vibeminer.application.buildlibrary;

import java.util.*;

/** Bounded complete query page; filters are applied to authoritative facts BEFORE paging. */
public record BuildLibraryPage(
    BuildLibraryQuery query,
    int pageSize,
    long libraryRevision,
    List<Entry> entries,
    Optional<BuildLibraryCursor> nextCursor) {
  public static final int MAX_REGIONS_PER_GROUP = 64,
      MAX_REFERENCES_PER_GROUP = 4096,
      MAX_REFERENCES_PER_PAGE = 8192;

  public record Entry(long firstRegistrationSequence, BuildLibraryProjection.Row row) {
    public Entry {
      Objects.requireNonNull(row);
      if (firstRegistrationSequence < 1
          || row.group().references().size() > MAX_REFERENCES_PER_GROUP)
        throw new IllegalArgumentException("Bounded complete grouped row required");
      var regions = new HashMap<String, BuildLibraryReference>();
      validateEventReferences(row.group().references());
      for (var ref : row.group().references()) {
        var prior = regions.putIfAbsent(ref.region(), ref);
        if (prior != null
            && (!prior.eventId().equals(ref.eventId())
                || prior.observationSequence() != ref.observationSequence()
                || !prior.observedAt().equals(ref.observedAt())
                || !prior.versions().equals(ref.versions())
                || !prior.cdns().equals(ref.cdns())
                || !prior.selectedCdn().equals(ref.selectedCdn())))
          throw new IllegalArgumentException("Read rows use one latest event per group and region");
      }
      if (regions.size() > MAX_REGIONS_PER_GROUP)
        throw new IllegalArgumentException("Regional row limit exceeded");
    }
  }

  public BuildLibraryPage {
    Objects.requireNonNull(query);
    entries = List.copyOf(entries);
    Objects.requireNonNull(nextCursor);
    if (pageSize < 1 || pageSize > 100 || libraryRevision < 0 || entries.size() > pageSize)
      throw new IllegalArgumentException("Bounded library page required");
    long previous = Long.MAX_VALUE;
    String previousId = "";
    int refs = 0;
    var ids = new HashSet<String>();
    var allReferences = new ArrayList<BuildLibraryReference>();
    for (var entry : entries) {
      var row = entry.row();
      var id = row.group().key().identity();
      if (entry.firstRegistrationSequence() > previous
          || entry.firstRegistrationSequence() == previous
              && !previousId.isEmpty()
              && id.compareTo(previousId) <= 0
          || !ids.add(id)
          || !row.group().key().productId().equals(query.productId())
          || !query.matches(row))
        throw new IllegalArgumentException("Library page changed order, product or exact filter");
      previous = entry.firstRegistrationSequence();
      previousId = id;
      refs += row.group().references().size();
      allReferences.addAll(row.group().references());
    }
    validateEventReferences(allReferences);
    if (refs > MAX_REFERENCES_PER_PAGE)
      throw new IllegalArgumentException(
          "Page reference budget exceeded; no truncated groups allowed");
    if (nextCursor.isPresent()) {
      var cursor = nextCursor.get();
      cursor.requireContext(query, pageSize, libraryRevision);
      if (entries.isEmpty()
          || cursor.beforeFirstRegistrationSequence()
              != entries.getLast().firstRegistrationSequence()
          || !cursor.beforeGroupId().equals(entries.getLast().row().group().key().identity()))
        throw new IllegalArgumentException("Next cursor changed actual page boundary");
    }
  }

  private static void validateEventReferences(List<BuildLibraryReference> references) {
    var events = new HashMap<UUID, BuildLibraryReference>();
    var counts = new HashMap<UUID, Integer>();
    var rows = new HashSet<String>();
    for (var ref : references) {
      if (!rows.add(ref.eventId() + ":" + ref.versionsRowOrdinal())
          || counts.merge(ref.eventId(), 1, Integer::sum) > 64)
        throw new IllegalArgumentException("Bounded unique event rows required");
      var prior = events.putIfAbsent(ref.eventId(), ref);
      if (prior != null
          && (!prior.region().equals(ref.region())
              || prior.observationSequence() != ref.observationSequence()
              || !prior.observedAt().equals(ref.observedAt())
              || !prior.versions().equals(ref.versions())
              || !prior.cdns().equals(ref.cdns())
              || !prior.selectedCdn().equals(ref.selectedCdn())))
        throw new IllegalArgumentException("One event must preserve its shared regional facts");
    }
  }
}
