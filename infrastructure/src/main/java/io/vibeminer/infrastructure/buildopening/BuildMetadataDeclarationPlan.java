package io.vibeminer.infrastructure.buildopening;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import java.util.*;

/**
 * Authenticated configuration classification only; no retained metadata or admission capability.
 */
public final class BuildMetadataDeclarationPlan {
  public enum Classification {
    REQUIRED_METADATA,
    ALTERNATIVE_RESOLVER,
    DELTA_UPDATE,
    INFORMATIONAL
  }

  public record Source(
      Kind kind,
      String contentKey,
      Optional<String> encodingKey,
      OptionalLong decodedBytes,
      OptionalLong encodedBytes) {
    public Source {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(encodingKey);
      Objects.requireNonNull(decodedBytes);
      Objects.requireNonNull(encodedBytes);
      if (contentKey == null
          || !contentKey.matches("[0-9a-f]{32}")
          || contentKey.equals("0".repeat(32))
          || encodingKey.isPresent()
              && (!encodingKey.get().matches("[0-9a-f]{32}")
                  || encodingKey.get().equals("0".repeat(32)))
          || decodedBytes.isPresent() != encodedBytes.isPresent()
          || decodedBytes.isPresent()
              && (decodedBytes.getAsLong() < 1 || encodedBytes.getAsLong() < 1))
        throw new IllegalArgumentException(
            "Exact nonzero keys and positive optional size pair required");
    }
  }

  private final BuildSnapshot build;
  private final VerifiedSourceAlias configuration;
  private final Map<Kind, Source> sources;
  private final Map<String, List<String>> values;
  private final Map<String, Classification> classifications;

  private BuildMetadataDeclarationPlan(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      Map<Kind, Source> sources,
      Map<String, List<String>> values,
      Map<String, Classification> classifications) {
    this.build = build;
    this.configuration = configuration;
    this.sources = Map.copyOf(sources);
    var copy = new LinkedHashMap<String, List<String>>();
    values.forEach((key, value) -> copy.put(key, List.copyOf(value)));
    this.values = Collections.unmodifiableMap(copy);
    this.classifications = Map.copyOf(classifications);
  }

  static BuildMetadataDeclarationPlan issued(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      Map<Kind, Source> sources,
      Map<String, List<String>> values,
      Map<String, Classification> classifications) {
    return new BuildMetadataDeclarationPlan(build, configuration, sources, values, classifications);
  }

  public BuildSnapshot build() {
    return build;
  }

  public VerifiedSourceAlias configuration() {
    return configuration;
  }

  public String rawSha256() {
    return configuration.object().digest().value();
  }

  public String classifierRevision() {
    return BuildMetadataDeclarationClassifier.REVISION;
  }

  public List<Source> requiredSources() {
    return Arrays.stream(Kind.values()).filter(sources::containsKey).map(sources::get).toList();
  }

  public Source source(Kind kind) {
    var source = sources.get(kind);
    if (source == null)
      throw new IllegalArgumentException("Metadata role is not published in this plan");
    return source;
  }

  public boolean downloadPublished() {
    return sources.containsKey(Kind.DOWNLOAD);
  }

  public boolean sizePublished() {
    return sources.containsKey(Kind.DOWNLOAD_SIZE);
  }

  public Map<String, List<String>> values() {
    return values;
  }

  public Map<String, Classification> classifications() {
    return classifications;
  }
}
