package io.vibeminer.infrastructure.discovery;

import static io.vibeminer.application.discoveryhistory.DiscoveryHistoryException.Code.*;

import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Pinned, transport-free interpretation shared by official capture and durable event replay. */
public final class DiscoveryObservationMapper {
  public static final String PARSER_REVISION = "blizzard-publication-metadata-v1";
  private static final Pattern PRODUCT = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
  private static final Pattern REGION = Pattern.compile("[a-z]{2,8}");

  public DiscoveryEvent map(
      UUID eventId,
      String productId,
      String region,
      Instant completedAt,
      SourceDocument versions,
      SourceDocument cdns) {
    if (eventId == null
        || productId == null
        || !PRODUCT.matcher(productId).matches()
        || region == null
        || !REGION.matcher(region).matches()
        || completedAt == null
        || versions == null
        || cdns == null)
      throw new DiscoveryHistoryException(
          INVALID_REQUEST, "Exact bounded discovery inputs required");
    String base = "https://us.version.battle.net/v2/products/" + productId;
    if (!versions.sourceUrl().equals(base + "/versions")
        || !cdns.sourceUrl().equals(base + "/cdns"))
      throw new DiscoveryHistoryException(
          CORRUPT_OBSERVATION, "Captured metadata roles differ from the official request");
    try {
      var parser = new BlizzardMetadataParser();
      var published = parser.parse(versions.bytes());
      var advertised = parser.parse(cdns.bytes());
      published.requireColumns("Region", "BuildConfig", "CDNConfig", "VersionsName");
      advertised.requireColumns("Name", "Path", "Hosts");
      DiscoveryEvent.CdnRow selected = null;
      for (int ordinal = 0; ordinal < advertised.rows().size(); ordinal++) {
        var row = advertised.rows().get(ordinal);
        if (region.equals(row.get("Name"))) {
          if (selected != null || row.get("Path").isBlank() || row.get("Hosts").isBlank())
            throw new MetadataFormatException("Requested CDN region is ambiguous or unusable");
          selected = new DiscoveryEvent.CdnRow(ordinal, row);
        }
      }
      if (selected == null)
        throw new MetadataFormatException("No published CDN metadata for requested region");
      var builds = new ArrayList<DiscoveryEvent.ObservedBuild>();
      for (int ordinal = 0; ordinal < published.rows().size(); ordinal++) {
        var row = published.rows().get(ordinal);
        if (!region.equals(row.get("Region"))) continue;
        if (builds.size() == 64)
          throw new DiscoveryHistoryException(
              LIMIT_EXCEEDED, "Discovery event exceeds64 matched builds");
        var evidence =
            new BuildEvidence(
                row.getOrDefault("BuildId", ""),
                row.getOrDefault("ProductConfig", ""),
                List.of(versions, cdns));
        var build =
            new BuildSnapshot(
                productId,
                productId + " (edition unverified)",
                "Published",
                region,
                row.get("VersionsName"),
                row.get("BuildConfig"),
                row.get("CDNConfig"),
                completedAt,
                false,
                evidence);
        builds.add(new DiscoveryEvent.ObservedBuild(build, ordinal, row));
      }
      if (builds.isEmpty())
        throw new MetadataFormatException("No published build for requested region");
      return new DiscoveryEvent(
          eventId,
          productId,
          region,
          completedAt,
          PARSER_REVISION,
          new DiscoveryEvent.ParsedSource(versions, published.columns(), published.sequence()),
          new DiscoveryEvent.ParsedSource(cdns, advertised.columns(), advertised.sequence()),
          selected,
          builds);
    } catch (IllegalArgumentException malformed) {
      throw new DiscoveryHistoryException(
          CORRUPT_OBSERVATION,
          "Captured publication cannot be interpreted consistently",
          malformed);
    }
  }

  /**
   * Reparse original bytes under their supported revision, never reinterpret recorded row facts.
   */
  public DiscoveryEvent replay(DiscoveryEvent recorded) {
    Objects.requireNonNull(recorded);
    if (!PARSER_REVISION.equals(recorded.parserRevision()))
      throw new DiscoveryHistoryException(
          UNSUPPORTED_INTERPRETATION, "Recorded discovery parser revision is unsupported");
    var observed =
        map(
            recorded.eventId(),
            recorded.productId(),
            recorded.region(),
            recorded.completedAt(),
            recorded.versions().source(),
            recorded.cdns().source());
    if (!observed.equals(recorded))
      throw new DiscoveryHistoryException(
          CORRUPT_OBSERVATION,
          "Recorded row/column/sequence interpretation differs from the captured documents");
    return observed;
  }
}
