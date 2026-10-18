package io.vibeminer.application.buildlibrary;

import java.util.*;

/** Pure display facts, never a remote payload availability or metadata readiness proof. */
public final class BuildLibraryProjection {
  private BuildLibraryProjection() {}

  public enum Filter {
    ALL,
    LOCAL,
    CURRENTLY_ADVERTISED
  }

  public enum Advertisement {
    PRESENT_IN_CHECKED_SCOPE,
    ABSENT_FROM_CHECKED_SCOPE,
    UNKNOWN
  }

  public record Row(
      BuildLibraryGrouping.Group group,
      BuildLibrarySeenState seen,
      BuildLibraryLocalFact local,
      Advertisement advertisement,
      Map<String, Advertisement> regionalAdvertisements) {
    public Row(
        BuildLibraryGrouping.Group group,
        BuildLibrarySeenState seen,
        BuildLibraryLocalFact local,
        Advertisement advertisement) {
      this(group, seen, local, advertisement, Map.of());
    }

    public Row {
      regionalAdvertisements = Map.copyOf(regionalAdvertisements);
      if (regionalAdvertisements.size() > 64)
        throw new IllegalArgumentException("Bounded regional advertisement facts required");
      for (var region : regionalAdvertisements.keySet())
        new BuildLibraryScope(group.key().productId(), region);
      Objects.requireNonNull(group);
      Objects.requireNonNull(seen);
      Objects.requireNonNull(local);
      Objects.requireNonNull(advertisement);
      if (!group.key().identity().equals(seen.groupId()))
        throw new IllegalArgumentException("Row seen state belongs to another identity");
    }

    public boolean matches(Filter filter) {
      Objects.requireNonNull(filter);
      return switch (filter) {
        case ALL -> true;
        case LOCAL -> local.isLocal();
        case CURRENTLY_ADVERTISED -> advertisement == Advertisement.PRESENT_IN_CHECKED_SCOPE;
      };
    }
  }

  public static Advertisement advertisement(
      BuildReferenceGroupKey key, List<BuildLibraryRegionalState> checkedScopes) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(checkedScopes);
    if (checkedScopes.size() > 64)
      throw new IllegalArgumentException("Bounded checked scope required");
    var scopes = new HashSet<BuildLibraryScope>();
    boolean present = false, unknown = checkedScopes.isEmpty();
    for (var state : checkedScopes) {
      if (!state.scope().productId().equals(key.productId()) || !scopes.add(state.scope()))
        throw new IllegalArgumentException("Exact distinct product scopes required");
      if (state.checkState() == BuildLibraryRegionalState.CheckState.SUCCESS) {
        if (state.lastSuccessfulGroupIds().contains(key.identity())) present = true;
      } else unknown = true;
    }
    if (present) return Advertisement.PRESENT_IN_CHECKED_SCOPE;
    return unknown ? Advertisement.UNKNOWN : Advertisement.ABSENT_FROM_CHECKED_SCOPE;
  }
}
