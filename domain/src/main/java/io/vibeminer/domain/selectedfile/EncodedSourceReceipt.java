package io.vibeminer.domain.selectedfile;

import io.vibeminer.domain.SourceVerificationEvidence;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Actual acquisition observations, preserved unchanged when another build reuses bytes. */
public record EncodedSourceReceipt(
    Kind kind,
    String actualResourceUrl,
    String resourceKey,
    long parentWholeBytes,
    long childOffset,
    long childEncodedBytes,
    Instant observedAt,
    long payloadRequestCount,
    long payloadResponseBytes,
    String observationDigest,
    Optional<ArchiveMappingProof> archiveMapping) {
  public enum Kind {
    LOOSE,
    ARCHIVE
  }

  public EncodedSourceReceipt {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(archiveMapping);
    resourceKey = SelectedFileValues.key(resourceKey);
    observationDigest = SelectedFileValues.digest(observationDigest);
    new SourceVerificationEvidence("encoded-source-observation-v1", actualResourceUrl, observedAt);
    if (parentWholeBytes < 1
        || childOffset < 0
        || childEncodedBytes < 1
        || childOffset > parentWholeBytes
        || childEncodedBytes > parentWholeBytes - childOffset
        || payloadRequestCount < 1
        || payloadResponseBytes < childEncodedBytes)
      throw new IllegalArgumentException("Invalid observed child resource geometry");
    if (kind == Kind.LOOSE
        && (childOffset != 0
            || parentWholeBytes != childEncodedBytes
            || archiveMapping.isPresent()))
      throw new IllegalArgumentException("Loose receipt requires exactly its whole object");
    if (kind == Kind.ARCHIVE
        && (archiveMapping.isEmpty() || !archiveMapping.get().indexIdentity().equals(resourceKey)))
      throw new IllegalArgumentException(
          "Archive receipt requires its authenticated index mapping");
  }
}
