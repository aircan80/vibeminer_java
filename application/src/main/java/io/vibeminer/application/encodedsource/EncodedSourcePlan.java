package io.vibeminer.application.encodedsource;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.selectedfile.*;
import java.time.Duration;
import java.util.*;

/** Immutable known-cost source tuple. Construction grants no transfer or association authority. */
public record EncodedSourcePlan(
    String sessionId,
    BuildSnapshot sourceBuild,
    ContentSlotKey slot,
    String encodingKey,
    long encodedBytes,
    Location location,
    Cost cost,
    Limits limits) {
  public enum LocationKind {
    LOOSE,
    ARCHIVE
  }

  public record Location(
      LocationKind kind,
      String resourceUrl,
      String resourceKey,
      long resourceBytes,
      long childOffset,
      long childLength,
      Optional<ArchiveMappingProof> archiveProof) {
    public Location {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(archiveProof);
      if (resourceUrl == null
          || resourceUrl.isBlank()
          || resourceUrl.length() > 8192
          || resourceUrl.chars().anyMatch(Character::isISOControl)
          || resourceKey == null
          || !resourceKey.matches("[0-9a-f]{32}")
          || resourceBytes < 1
          || childOffset < 0
          || childLength < 1
          || childOffset > resourceBytes
          || childLength > resourceBytes - childOffset
          || kind == LocationKind.LOOSE
              && (childOffset != 0 || childLength != resourceBytes || archiveProof.isPresent())
          || kind == LocationKind.ARCHIVE && archiveProof.isEmpty())
        throw new IllegalArgumentException("Invalid pinned encoded-source location");
    }
  }

  public record Cost(long maximumPayloadWireBytes, long maximumAdditionalPhysicalBytes) {
    public Cost {
      if (maximumPayloadWireBytes < 1 || maximumAdditionalPhysicalBytes < 1)
        throw new IllegalArgumentException("Explicit positive source cost required");
    }
  }

  public record Limits(
      int maximumHeaderBytes,
      int maximumChunks,
      long maximumEncodedBytes,
      int maximumRangeBytes,
      int maximumRequests,
      long maximumWireBytes,
      Duration maximumDuration) {
    public Limits {
      Objects.requireNonNull(maximumDuration);
      if (maximumHeaderBytes < 1
          || maximumChunks < 1
          || maximumEncodedBytes < 1
          || maximumRangeBytes < 1
          || maximumRangeBytes > 4 * 1024 * 1024
          || maximumRequests < 1
          || maximumWireBytes < 1
          || maximumDuration.isNegative()
          || maximumDuration.isZero()
          || maximumDuration.compareTo(Duration.ofMinutes(10)) > 0)
        throw new IllegalArgumentException("Invalid bounded encoded-source limits");
    }
  }

  public EncodedSourcePlan {
    Objects.requireNonNull(sourceBuild);
    Objects.requireNonNull(slot);
    Objects.requireNonNull(location);
    Objects.requireNonNull(cost);
    Objects.requireNonNull(limits);
    if (sessionId == null
        || sessionId.isBlank()
        || sessionId.length() > 256
        || sessionId.chars().anyMatch(Character::isISOControl)
        || encodingKey == null
        || !encodingKey.matches("[0-9a-f]{32}")
        || encodedBytes < 1
        || encodedBytes != location.childLength()
        || location.kind() == LocationKind.LOOSE && !encodingKey.equals(location.resourceKey())
        || encodedBytes > limits.maximumEncodedBytes()
        || encodedBytes > cost.maximumPayloadWireBytes()
        || encodedBytes > cost.maximumAdditionalPhysicalBytes()
        || cost.maximumPayloadWireBytes() > limits.maximumWireBytes()
        || cost.maximumAdditionalPhysicalBytes() > limits.maximumEncodedBytes())
      throw new IllegalArgumentException("Encoded source tuple/cost/limits disagree");
    long requests =
        encodedBytes / limits.maximumRangeBytes()
            + (encodedBytes % limits.maximumRangeBytes() == 0 ? 0 : 1);
    if (requests > limits.maximumRequests())
      throw new IllegalArgumentException("Encoded source exceeds authorized request limit");
  }
}
