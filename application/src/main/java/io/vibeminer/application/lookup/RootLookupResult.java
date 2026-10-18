package io.vibeminer.application.lookup;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Returned only after complete supported structural EOF AND complete protocol verification. Absence
 * is scoped to these selected IDs in this exact root, never a filename/item claim.
 */
public record RootLookupResult(
    BuildSnapshot build,
    VerifiedSourceAlias root,
    Set<Long> requestedIds,
    List<RootVariant> variants,
    long scannedRecords) {
  public RootLookupResult {
    Objects.requireNonNull(build);
    Objects.requireNonNull(root);
    requestedIds = Set.copyOf(requestedIds);
    variants = List.copyOf(variants);
    if (scannedRecords < 0 || requestedIds.stream().anyMatch(id -> id < 0 || id > 0xffffffffL))
      throw new IllegalArgumentException("Invalid lookup coverage");
    for (var variant : variants)
      if (!requestedIds.contains(variant.fileDataId()))
        throw new IllegalArgumentException("Variant outside selected coverage");
  }

  public Set<Long> absentIds() {
    var found = variants.stream().map(RootVariant::fileDataId).collect(Collectors.toSet());
    return requestedIds.stream()
        .filter(id -> !found.contains(id))
        .collect(Collectors.toUnmodifiableSet());
  }
}
