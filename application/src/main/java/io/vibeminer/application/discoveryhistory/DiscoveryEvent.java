package io.vibeminer.application.discoveryhistory;

import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;

/** Immutable client retrieval batch, not an atomic upstream snapshot or availability proof. */
public record DiscoveryEvent(
    UUID eventId,
    String productId,
    String region,
    Instant completedAt,
    String parserRevision,
    ParsedSource versions,
    ParsedSource cdns,
    CdnRow selectedCdn,
    List<ObservedBuild> builds) {
  public record ParsedSource(
      SourceDocument source, List<String> columns, Optional<String> sequence) {
    public ParsedSource {
      Objects.requireNonNull(source);
      if (source.bytes().length == 0)
        throw new IllegalArgumentException("Nonempty captured metadata required");
      columns = List.copyOf(columns);
      Objects.requireNonNull(sequence);
      if (columns.isEmpty()
          || columns.size() > 64
          || new HashSet<>(columns).size() != columns.size())
        throw new IllegalArgumentException("Bounded unique source columns required");
      columns.forEach(HistoryValues::column);
      if (sequence.isPresent()
          && (sequence.get().length() > 16384 || !sequence.get().matches("[0-9]+")))
        throw new IllegalArgumentException("Raw decimal sequence required");
    }

    void requireRow(Map<String, String> values) {
      if (!values.keySet().equals(new HashSet<>(columns)))
        throw new IllegalArgumentException("Selected row differs from source columns");
    }
  }

  public record CdnRow(int rowOrdinal, Map<String, String> values) {
    public CdnRow {
      HistoryValues.ordinal(rowOrdinal);
      values = HistoryValues.row(values);
    }
  }

  public record ObservedBuild(
      BuildSnapshot build, int versionsRowOrdinal, Map<String, String> versionsValues) {
    public ObservedBuild {
      Objects.requireNonNull(build);
      HistoryValues.text(build.editionLabel(), 4096);
      HistoryValues.text(build.channel(), 4096);
      HistoryValues.text(build.versionLabel(), 4096);
      HistoryValues.ordinal(versionsRowOrdinal);
      versionsValues = HistoryValues.row(versionsValues);
    }
  }

  public DiscoveryEvent {
    Objects.requireNonNull(eventId);
    productId = HistoryValues.product(productId);
    region = HistoryValues.region(region);
    Objects.requireNonNull(completedAt);
    parserRevision = HistoryValues.text(parserRevision, 128);
    Objects.requireNonNull(versions);
    Objects.requireNonNull(cdns);
    Objects.requireNonNull(selectedCdn);
    builds = List.copyOf(builds);
    if (builds.isEmpty() || builds.size() > 64)
      throw new IllegalArgumentException("One to64 observed builds required");
    var base = "https://us.version.battle.net/v2/products/" + productId;
    if (!versions.source().sourceUrl().equals(base + "/versions")
        || !cdns.source().sourceUrl().equals(base + "/cdns"))
      throw new IllegalArgumentException("Exact official metadata roles required");
    cdns.requireRow(selectedCdn.values());
    if (!region.equals(selectedCdn.values().get("Name"))
        || blank(selectedCdn.values().get("Path"))
        || blank(selectedCdn.values().get("Hosts")))
      throw new IllegalArgumentException("Selected CDN region and usable raw route required");
    int previous = -1;
    var shared = new ArrayList<ObservedBuild>(builds.size());
    for (var row : builds) {
      versions.requireRow(row.versionsValues());
      var b = row.build();
      var v = row.versionsValues();
      if (row.versionsRowOrdinal() <= previous
          || b.fixture()
          || !productId.equals(b.productId())
          || !region.equals(b.region())
          || !region.equals(v.get("Region"))
          || !b.versionLabel().equals(v.get("VersionsName"))
          || !b.buildConfig().equalsIgnoreCase(v.get("BuildConfig"))
          || !b.cdnConfig().equalsIgnoreCase(v.get("CDNConfig"))
          || !b.evidence().upstreamBuildId().equals(v.getOrDefault("BuildId", ""))
          || !b.evidence().productConfig().equalsIgnoreCase(v.getOrDefault("ProductConfig", ""))
          || !b.observedAt().equals(completedAt)
          || !b.evidence().documents().equals(List.of(versions.source(), cdns.source())))
        throw new IllegalArgumentException(
            "Observed build differs from exact event interpretation");
      previous = row.versionsRowOrdinal();
      // Equal caller documents are normalized to the same TWO event instances, never copied per
      // build.
      var evidence =
          new BuildEvidence(
              b.evidence().upstreamBuildId(),
              b.evidence().productConfig(),
              List.of(versions.source(), cdns.source()));
      var observed =
          new BuildSnapshot(
              b.productId(),
              b.editionLabel(),
              b.channel(),
              b.region(),
              b.versionLabel(),
              b.buildConfig(),
              b.cdnConfig(),
              b.observedAt(),
              false,
              evidence);
      shared.add(new ObservedBuild(observed, row.versionsRowOrdinal(), v));
    }
    builds = List.copyOf(shared);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
