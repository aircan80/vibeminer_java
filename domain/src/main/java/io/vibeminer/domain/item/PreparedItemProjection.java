package io.vibeminer.domain.item;

import java.util.Objects;

/** Consumer context references one shared artifact; construction does not publish an index. */
public record PreparedItemProjection(
    ItemArtifactDefinition definition, ItemProjectionEvidence evidence, Coverage coverage) {
  public record Coverage(
      int sectionOrdinal, long physicalRows, long unindexedCopyDeclarations, int excludedSections) {
    public Coverage {
      if (sectionOrdinal < 0
          || physicalRows < 0
          || unindexedCopyDeclarations < 0
          || excludedSections < 0)
        throw new IllegalArgumentException("Invalid admitted physical coverage");
    }
  }

  public PreparedItemProjection {
    Objects.requireNonNull(definition);
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(coverage);
    var i = definition.interpretation();
    if (coverage.sectionOrdinal() != i.sectionOrdinal()
        || !definition.sources().contains(evidence.localVerification().object()))
      throw new IllegalArgumentException(
          "Projection context differs from admitted artifact source/section");
    long sectionEnd = i.sectionDecodedOffset() + i.sectionDecodedBytes();
    boolean covered = false;
    for (var window : evidence.windows())
      if (window.offset() <= i.sectionDecodedOffset()
          && window.offset() + window.length() >= sectionEnd) covered = true;
    if (!covered)
      throw new IllegalArgumentException(
          "Admitted section requires explicit authenticated window coverage");
  }

  public String artifactId() {
    return definition.artifactId();
  }

  public String snapshotId() {
    return evidence.association().snapshotId();
  }

  public String sourceRole() {
    return evidence.association().role();
  }

  public String locale() {
    return definition.interpretation().locale();
  }
}
