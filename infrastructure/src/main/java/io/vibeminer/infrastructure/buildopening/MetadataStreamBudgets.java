package io.vibeminer.infrastructure.buildopening;

import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.config.TactConfigParser;
import io.vibeminer.protocol.tact.download.DownloadInventoryReader;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryReader;
import io.vibeminer.protocol.tact.encoding.FullEncodingInventory;
import java.time.Duration;
import java.util.Objects;

/** Explicit whole-operation admission and per-parser bounds; no implicit install-sized ceiling. */
public record MetadataStreamBudgets(
    int rangeSliceBytes,
    long maximumWireBytes,
    long maximumRequests,
    Duration timeout,
    long maximumDecodedWorkBytes,
    long maximumGrammarWorkBytes,
    StreamingDecodedBlteInputStream.Limits blte,
    TactConfigParser.Limits configuration,
    FullEncodingInventory.Limits encoding,
    DownloadInventoryReader.Limits download,
    DownloadSizeInventoryReader.Limits downloadSize) {
  public MetadataStreamBudgets {
    Objects.requireNonNull(timeout);
    Objects.requireNonNull(blte);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(encoding);
    Objects.requireNonNull(download);
    Objects.requireNonNull(downloadSize);
    if (rangeSliceBytes < 1
        || rangeSliceBytes > 4 * 1024 * 1024
        || maximumWireBytes < 1
        || maximumRequests < 1
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofHours(1)) > 0
        || maximumDecodedWorkBytes < 0
        || maximumGrammarWorkBytes < 0)
      throw new IllegalArgumentException("Invalid metadata stream budget");
  }

  /** Fresh publication decodes once before promotion and once under its local grammar lease. */
  public static long requiredDecodeWork(long decodedBytes) {
    return multiply(decodedBytes, 2);
  }

  /** EN validates and emits each page separately; conservatively charge two whole decoded walks. */
  public static long requiredGrammarWork(Kind kind, long decodedBytes) {
    return multiply(decodedBytes, kind == Kind.ENCODING ? 2 : 1);
  }

  private static long multiply(long bytes, int factor) {
    if (bytes < 0) throw new IllegalArgumentException("Negative decoded work");
    try {
      return Math.multiplyExact(bytes, factor);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Metadata work exceeds signed long", overflow);
    }
  }
}
