package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.discoveryhistory.DiscoveryEvent;
import io.vibeminer.application.discoveryhistory.DiscoveryHistoryException;
import java.time.Instant;
import java.util.*;

/** One paired retrieval, with independently interpretable regional outcomes; never availability. */
public record BuildLibraryProductObservation(
    String productId,
    Instant completedAt,
    String parserRevision,
    DiscoveryEvent.ParsedSource versions,
    DiscoveryEvent.ParsedSource cdns,
    List<DiscoveryEvent> events,
    List<RegionalFailure> failures,
    Optional<String> diagnostic) {
  public record RegionalFailure(
      BuildLibraryScope scope, DiscoveryHistoryException.Code code, String message) {
    public RegionalFailure {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(code);
      message = BuildLibraryValues.text(message, 4096);
    }
  }

  public BuildLibraryProductObservation {
    new BuildLibraryScope(productId, "us");
    Objects.requireNonNull(completedAt);
    parserRevision = BuildLibraryValues.text(parserRevision, 128);
    Objects.requireNonNull(versions);
    Objects.requireNonNull(cdns);
    events = List.copyOf(events);
    failures = List.copyOf(failures);
    Objects.requireNonNull(diagnostic);
    diagnostic.ifPresent(value -> BuildLibraryValues.text(value, 4096));
    String base = "https://us.version.battle.net/v2/products/" + productId;
    if (!versions.source().sourceUrl().equals(base + "/versions")
        || !cdns.source().sourceUrl().equals(base + "/cdns")
        || events.size() + failures.size() > 64) {
      throw new IllegalArgumentException("Bounded exact product capture required");
    }
    var regions = new HashSet<String>();
    var eventIds = new HashSet<UUID>();
    var shared = new ArrayList<DiscoveryEvent>(events.size());
    for (var event : events) {
      if (!event.productId().equals(productId)
          || !event.completedAt().equals(completedAt)
          || !event.parserRevision().equals(parserRevision)
          || !event.versions().equals(versions)
          || !event.cdns().equals(cdns)
          || !regions.add(event.region())
          || !eventIds.add(event.eventId())) {
        throw new IllegalArgumentException("Regional event differs from paired capture");
      }
      shared.add(
          new DiscoveryEvent(
              event.eventId(),
              productId,
              event.region(),
              completedAt,
              parserRevision,
              versions,
              cdns,
              event.selectedCdn(),
              event.builds()));
    }
    for (var failure : failures) {
      if (!failure.scope().productId().equals(productId)
          || !regions.add(failure.scope().region())) {
        throw new IllegalArgumentException("Conflicting regional capture outcome");
      }
    }
    events = List.copyOf(shared);
    // No regional events is intentionally legal: an empty capture cannot prove publication absence.
  }
}
