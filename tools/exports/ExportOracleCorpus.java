package io.vibeminer.infrastructure.itemexport;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class ExportOracleCorpus {
  public static void main(String[] args) throws Exception {
    var root = Path.of(args[0]);
    Files.createDirectories(root);
    var encoder = new ItemEvidenceJsonEncoder();
    for (int width = 1; width <= 64; width++) {
      long mask = width == 64 ? -1L : (1L << width) - 1;
      for (long bits : new long[] {0, 1, mask, 1L << (width - 1)}) {
        var snap =
            ItemExportFixture.integer(width, bits, ItemArtifactDefinition.Sign.UNSIGNED, bits);
        Files.write(
            root.resolve("raw-" + width + "-" + Long.toUnsignedString(bits) + ".json"),
            encoder.encode(snap, Instant.EPOCH, CancellationToken.none()));
      }
    }
    for (long n :
        new long[] {
          Long.MIN_VALUE,
          Long.MAX_VALUE,
          -1,
          0,
          1,
          9007199254740991L,
          9007199254740992L,
          9007199254740993L
        })
      Files.write(
          root.resolve("signed-" + n + ".json"),
          encoder.encode(
              ItemExportFixture.integer(64, n, ItemArtifactDefinition.Sign.SIGNED, n),
              Instant.MAX,
              CancellationToken.none()));
    Files.write(
        root.resolve("unicode.json"),
        encoder.encode(
            ItemExportFixture.edition("e\u0301é😀\"\\\t\b\f", List.of()),
            Instant.MIN,
            CancellationToken.none()));
    byte[] canary =
        "PUBLIC-AUTHORED-PAYLOAD-CANARY".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(
        root.resolve("canary.json"),
        encoder.encode(
            ItemExportFixture.edition(
                "Public authored label",
                List.of(
                    new io.vibeminer.domain.SourceDocument(
                        "https://example.invalid/fixture", Instant.EPOCH, canary))),
            Instant.EPOCH,
            CancellationToken.none()));
    int base =
        encoder.encode(
                ItemExportFixture.edition("x", List.of()), Instant.EPOCH, CancellationToken.none())
            .length;
    int fill = 2097152 - base + 1;
    for (int delta = -1; delta <= 0; delta++)
      Files.write(
          root.resolve("ceiling-" + delta + ".json"),
          encoder.encode(
              ItemExportFixture.edition("x".repeat(fill + delta), List.of()),
              Instant.EPOCH,
              CancellationToken.none()));
    try {
      encoder.encode(
          ItemExportFixture.edition("x".repeat(fill + 1), List.of()),
          Instant.EPOCH,
          CancellationToken.none());
      throw new AssertionError("oversized document accepted");
    } catch (io.vibeminer.application.itemexport.ItemEvidenceException e) {
      if (e.code() != io.vibeminer.application.itemexport.ItemEvidenceException.Code.LIMIT_EXCEEDED)
        throw e;
    }
    for (var outcome : ItemNameMatch.Outcome.values()) {
      var match =
          new ItemNameMatch(
              101,
              outcome,
              outcome == ItemNameMatch.Outcome.PHYSICAL_NAME
                  ? Optional.of(new DisplayCandidate("", 101, 0, 0, 100, 5, 5))
                  : Optional.empty(),
              outcome == ItemNameMatch.Outcome.COPY_UNINDEXED
                  ? OptionalLong.of(202)
                  : OptionalLong.empty());
      Files.write(
          root.resolve("name-" + outcome + ".json"),
          encoder.encode(
              ItemExportFixture.paired(match, 3780, "chunk-v1"),
              Instant.EPOCH,
              CancellationToken.none()));
    }
  }
}
