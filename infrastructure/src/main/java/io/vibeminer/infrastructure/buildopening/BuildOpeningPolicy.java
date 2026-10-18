package io.vibeminer.infrastructure.buildopening;

import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.config.TactConfigParser;
import io.vibeminer.protocol.tact.download.DownloadInventoryReader;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryReader;
import io.vibeminer.protocol.tact.encoding.FullEncodingInventory;
import java.time.Duration;
import java.util.Objects;

/** Explicit finite initial ROOT/direct/loose profile, not a claim about every published build. */
public record BuildOpeningPolicy(
    long maximumWireBytes,
    long maximumAdditionalPhysicalBytes,
    long maximumRequests,
    long maximumDecodedWorkBytes,
    long maximumGrammarWorkBytes,
    long freeSpaceHeadroomBytes,
    Duration timeout,
    MetadataStreamBudgets metadata,
    LookupBudgets root,
    BuildMetadataDeclarationClassifier.Limits declarations) {
  public static final String SCOPE_REVISION = "wow-root-loose-metadata-opening-v1";

  public BuildOpeningPolicy {
    Objects.requireNonNull(timeout);
    Objects.requireNonNull(metadata);
    Objects.requireNonNull(root);
    Objects.requireNonNull(declarations);
    if (maximumWireBytes < 1
        || maximumAdditionalPhysicalBytes < 1
        || maximumRequests < 1
        || maximumDecodedWorkBytes < 1
        || maximumGrammarWorkBytes < 1
        || freeSpaceHeadroomBytes < 0
        || timeout.isZero()
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofHours(1)) > 0)
      throw new IllegalArgumentException("Positive bounded opening policy required");
  }

  /**
   * Sample-sized admission ceilings, independently bounded even when all objects replay locally.
   */
  public static BuildOpeningPolicy initialCohort() {
    var metadata =
        new MetadataStreamBudgets(
            1 << 20,
            512L << 20,
            8192,
            Duration.ofMinutes(30),
            2L << 30,
            2L << 30,
            new StreamingDecodedBlteInputStream.Limits(
                4 << 20, 65536, 512L << 20, 1L << 30, 512L << 20, 1L << 30, 100_000_000, 64 << 10),
            new TactConfigParser.Limits(1 << 20, 20000, 1 << 20, 100000, 1L << 30, 512L << 20),
            new FullEncodingInventory.Limits(
                1L << 30,
                50_000_000,
                100_000_000,
                1_000_000,
                32 << 20,
                64 << 10,
                65536,
                255,
                1_000_000,
                32 << 20,
                1 << 20),
            new DownloadInventoryReader.Limits(
                1L << 30, 10_000_000, 4096, 16384, 2 << 20, 512L << 20, 255),
            new DownloadSizeInventoryReader.Limits(
                1L << 30, 10_000_000, 4096, 16384, 2 << 20, 512L << 20));
    return new BuildOpeningPolicy(
        512L << 20,
        512L << 20,
        8192,
        2L << 30,
        2L << 30,
        512L << 20,
        Duration.ofMinutes(30),
        metadata,
        LookupBudgets.defaults(),
        BuildMetadataDeclarationClassifier.Limits.defaults());
  }
}
