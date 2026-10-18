package io.vibeminer.application.buildlibrary;

import java.util.*;

/** Pure bounded projection only; repositories retain every observation reference. */
public final class BuildLibraryGrouping {
  private BuildLibraryGrouping() {}

  private static final Comparator<BuildLibraryReference> ORDER =
      Comparator.comparing(BuildLibraryReference::region)
          .thenComparing(
              Comparator.comparingLong(BuildLibraryReference::observationSequence).reversed())
          .thenComparingInt(BuildLibraryReference::versionsRowOrdinal)
          .thenComparing(r -> r.eventId().toString());

  public record Group(
      BuildReferenceGroupKey key,
      List<BuildLibraryReference> references,
      BuildLibraryReference preferredReference) {
    public Group {
      Objects.requireNonNull(key);
      references = List.copyOf(references);
      Objects.requireNonNull(preferredReference);
      if (references.isEmpty()
          || !references.contains(preferredReference)
          || references.stream().anyMatch(r -> !r.key().equals(key)))
        throw new IllegalArgumentException("One complete reference identity per group required");
    }
  }

  public static List<Group> group(List<BuildLibraryReference> references, int maximumReferences) {
    Objects.requireNonNull(references);
    if (maximumReferences < 1
        || maximumReferences > 100000
        || references.size() > maximumReferences)
      throw new IllegalArgumentException("Grouping reference limit exceeded");
    var grouped = new TreeMap<String, List<BuildLibraryReference>>();
    var keys = new HashMap<String, BuildReferenceGroupKey>();
    var receipts = new HashSet<String>();
    for (var reference : references) {
      Objects.requireNonNull(reference);
      var receipt = reference.eventId() + ":" + reference.versionsRowOrdinal();
      if (!receipts.add(receipt))
        throw new IllegalArgumentException("Duplicate event row reference");
      var identity = reference.key().identity();
      var previous = keys.putIfAbsent(identity, reference.key());
      if (previous != null && !previous.equals(reference.key()))
        throw new IllegalArgumentException("Group digest conflicts with exact key");
      grouped.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(reference);
    }
    var result = new ArrayList<Group>();
    for (var entry : grouped.entrySet()) {
      var rows = entry.getValue();
      rows.sort(ORDER);
      var preferred =
          rows.stream().filter(r -> r.region().equals("us")).findFirst().orElse(rows.getFirst());
      result.add(new Group(keys.get(entry.getKey()), rows, preferred));
    }
    return List.copyOf(result);
  }
}
