package io.vibeminer.application.discoveryhistory;

import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;

/** Body-free checked recording summary. Selection must re-read and replay the full event. */
public record DiscoveryEventSummary(
    UUID eventId,
    long sequence,
    String productId,
    String region,
    Instant completedAt,
    String parserRevision,
    DocumentReceipt versions,
    DocumentReceipt cdns,
    List<BuildReference> builds) {
  public record DocumentReceipt(String sourceUrl, Instant capturedAt, String sha256, long length) {
    public DocumentReceipt {
      sourceUrl = HistoryValues.text(sourceUrl, 2048);
      Objects.requireNonNull(capturedAt);
      sha256 = HistoryValues.digest(sha256);
      if (length < 1 || length > 1048576)
        throw new IllegalArgumentException("Bounded nonempty metadata receipt required");
    }

    public static DocumentReceipt from(SourceDocument source) {
      return new DocumentReceipt(
          source.sourceUrl(), source.capturedAt(), source.sha256(), source.bytes().length);
    }
  }

  public record BuildReference(
      String snapshotId,
      int versionsRowOrdinal,
      String channel,
      String versionLabel,
      String buildConfig,
      String cdnConfig,
      String upstreamBuildId,
      String productConfig) {
    public BuildReference {
      snapshotId = HistoryValues.digest(snapshotId);
      HistoryValues.ordinal(versionsRowOrdinal);
      channel = HistoryValues.text(channel, 4096);
      versionLabel = HistoryValues.text(versionLabel, 4096);
      if (buildConfig == null
          || !buildConfig.matches("[0-9a-f]{32}")
          || cdnConfig == null
          || !cdnConfig.matches("[0-9a-f]{32}"))
        throw new IllegalArgumentException("Normalized exact configuration keys required");
      var evidence = new BuildEvidence(upstreamBuildId, productConfig, List.of());
      if (!evidence.productConfig().equals(productConfig))
        throw new IllegalArgumentException("Normalized product config required");
    }

    public static BuildReference from(DiscoveryEvent.ObservedBuild row) {
      var b = row.build();
      return new BuildReference(
          b.snapshotId(),
          row.versionsRowOrdinal(),
          b.channel(),
          b.versionLabel(),
          b.buildConfig(),
          b.cdnConfig(),
          b.evidence().upstreamBuildId(),
          b.evidence().productConfig());
    }
  }

  public DiscoveryEventSummary {
    Objects.requireNonNull(eventId);
    if (sequence < 1) throw new IllegalArgumentException("Positive local sequence required");
    productId = HistoryValues.product(productId);
    region = HistoryValues.region(region);
    Objects.requireNonNull(completedAt);
    parserRevision = HistoryValues.text(parserRevision, 128);
    Objects.requireNonNull(versions);
    Objects.requireNonNull(cdns);
    builds = List.copyOf(builds);
    if (builds.isEmpty() || builds.size() > 64)
      throw new IllegalArgumentException("Bounded event references required");
    var base = "https://us.version.battle.net/v2/products/" + productId;
    if (!versions.sourceUrl().equals(base + "/versions")
        || !cdns.sourceUrl().equals(base + "/cdns"))
      throw new IllegalArgumentException("Exact metadata receipt roles required");
    int previous = -1;
    for (var ref : builds) {
      var identity =
          new BuildSnapshot(
              productId,
              productId,
              ref.channel(),
              region,
              ref.versionLabel(),
              ref.buildConfig(),
              ref.cdnConfig(),
              completedAt,
              false,
              new BuildEvidence(ref.upstreamBuildId(), ref.productConfig(), List.of()));
      if (ref.versionsRowOrdinal() <= previous || !identity.snapshotId().equals(ref.snapshotId()))
        throw new IllegalArgumentException("Snapshot reference identity/order mismatch");
      previous = ref.versionsRowOrdinal();
    }
  }

  public static DiscoveryEventSummary from(DiscoveryEvent event, long sequence) {
    return new DiscoveryEventSummary(
        event.eventId(),
        sequence,
        event.productId(),
        event.region(),
        event.completedAt(),
        event.parserRevision(),
        DocumentReceipt.from(event.versions().source()),
        DocumentReceipt.from(event.cdns().source()),
        event.builds().stream().map(BuildReference::from).toList());
  }
}
