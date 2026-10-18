package io.vibeminer.domain.item;

import java.util.Objects;

/** Contextual evidence for one shared name artifact. Construction is not readiness/publication. */
public record PreparedSparseNames(
    SparseNameArtifactDefinition definition, ItemProjectionEvidence evidence, Coverage coverage) {
  public record Coverage(
      int sectionOrdinal,
      long physicalRows,
      long unindexedCopyDeclarations,
      int excludedSections,
      long projectedUtf8Bytes) {
    public Coverage {
      if (sectionOrdinal < 0
          || physicalRows < 0
          || unindexedCopyDeclarations < 0
          || excludedSections < 0
          || projectedUtf8Bytes < 0)
        throw new IllegalArgumentException("Invalid admitted physical name coverage");
    }
  }

  public PreparedSparseNames {
    Objects.requireNonNull(definition);
    Objects.requireNonNull(evidence);
    Objects.requireNonNull(coverage);
    var i = definition.interpretation();
    var v = evidence.association().descriptor().association();
    var b = v.build();
    var s = i.schema();
    if (!definition.sources().getFirst().equals(evidence.localVerification().object())
        || coverage.sectionOrdinal() != i.sectionOrdinal()
        || !b.productId().equals("wow")
        || !b.region().equals("us")
        || !b.versionLabel().equals("12.1.5.70448")
        || v.fileDataId() != 1572924
        || !v.contentKey().equals("bb0ecbdc2fd4988ddd3c1c5e95c34165")
        || (v.localeFlags() & 2) == 0
        || !i.locale().equals("enUS")
        || i.tableHash() != 0x919be54eL
        || i.layoutHash() != 0x1c17d17fL
        || i.sectionOrdinal() != 0
        || !s.provider().equals("WoWDBDefs")
        || !s.revision().equals("b9a63096cdd99b26967f17f9515377ae62cadfd7")
        || !s.definitionPath().equals("definitions/ItemSparse.dbd")
        || !s.definitionSha256()
            .equals("8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a"))
      throw new IllegalArgumentException(
          "Name artifact differs from exact retained cohort/source/interpretation");
    requireWindow(evidence, 0, i.metadataPrefixBytes());
    requireWindow(evidence, i.recordAreaOffset(), i.recordAreaBytes());
    requireWindow(evidence, i.suffixOffset(), i.suffixBytes());
    if (coverage.physicalRows() > i.recordAreaBytes() / 5
        || coverage.projectedUtf8Bytes() > i.recordAreaBytes() - coverage.physicalRows() * 5)
      throw new IllegalArgumentException(
          "Name coverage exceeds minimum five-string physical geometry");
  }

  private static void requireWindow(ItemProjectionEvidence e, long offset, long bytes) {
    long end = offset + bytes, covered = offset;
    // Windows need not be ordered or disjoint; bounded evidence permits a checked union.
    boolean advanced;
    do {
      advanced = false;
      for (var window : e.windows())
        if (window.offset() <= covered && window.offset() + window.length() > covered) {
          long next = Math.min(end, window.offset() + window.length());
          if (next > covered) {
            covered = next;
            advanced = true;
          }
        }
    } while (advanced && covered < end);
    if (covered != end)
      throw new IllegalArgumentException(
          "Name projection requires independent authenticated prefix/record/suffix coverage");
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

  public void requireMatch(ItemNameMatch match) {
    Objects.requireNonNull(match);
    if (match.outcome() == ItemNameMatch.Outcome.COPY_UNINDEXED
        && coverage.unindexedCopyDeclarations() == 0)
      throw new IllegalArgumentException("Copy result lacks admitted declarations");
    if (match.candidate().isPresent()) {
      var c = match.candidate().get();
      var i = definition.interpretation();
      if (c.sectionOrdinal() != coverage.sectionOrdinal()
          || c.physicalOrdinal() >= coverage.physicalRows()
          || c.decodedOffset() < i.recordAreaOffset()
          || c.decodedOffset() + c.recordBytes() > i.recordAreaOffset() + i.recordAreaBytes()
          || c.utf8Bytes() > coverage.projectedUtf8Bytes())
        throw new IllegalArgumentException("Candidate is outside admitted physical name scope");
    }
  }
}
