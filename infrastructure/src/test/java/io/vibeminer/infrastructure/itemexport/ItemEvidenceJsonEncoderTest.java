package io.vibeminer.infrastructure.itemexport;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.itemexport.*;
import io.vibeminer.domain.SourceDocument;
import io.vibeminer.domain.item.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ItemEvidenceJsonEncoderTest {
  final ItemEvidenceJsonEncoder encoder = new ItemEvidenceJsonEncoder();
  final Instant created = Instant.EPOCH.plusSeconds(3);

  String json(ItemEvidenceSnapshot s) {
    return new String(encoder.encode(s, created, CancellationToken.none()), StandardCharsets.UTF_8);
  }

  @Test
  void explicitlyProjectsRawIntegersAndHistoricalProofWithoutPayloads() {
    String json = json(ItemExportFixture.snapshot());
    assertTrue(json.contains("\"rawHex\":\"ffffffff\""));
    assertTrue(json.contains("\"candidateDecimal\":\"-1\""));
    assertTrue(json.contains("\"state\":\"NOT_INCLUDED\""));
    assertTrue(json.contains("\"documentCreatedAt\":\"1970-01-01T00:00:03Z\""));
    assertTrue(json.contains("\"verifiedAt\":\"1970-01-01T00:00:00Z\""));
    assertFalse(json.contains("committedAt"));
    assertFalse(json.contains("\"bytes\":"));
  }

  @Test
  void strictUnicodePreservesSupplementaryCombiningAndEscaping() {
    String text = "e\u0301é😀\"\\\t\b\f";
    String json = json(ItemExportFixture.edition(text, List.of()));
    assertTrue(json.contains("e\u0301é😀\\\"\\\\\\u0009\\u0008\\u000c"));
    for (String invalid : List.of("bad\ud800", "bad\udc00"))
      assertEquals(
          ItemEvidenceException.Code.INVALID_REQUEST,
          assertThrows(
                  ItemEvidenceException.class,
                  () -> json(ItemExportFixture.edition(invalid, List.of())))
              .code());
  }

  @Test
  void rawSourceCanariesNeverEnterThePublicProjection() {
    byte[] secret =
        "PRIVATE-SOURCE-BYTES-CANARY-/home/private/DBD".getBytes(StandardCharsets.UTF_8);
    var doc = new SourceDocument("https://us.cdn.blizzard.com/versions", Instant.EPOCH, secret);
    String json = json(ItemExportFixture.edition("Public label", List.of(doc)));
    assertTrue(json.contains(doc.sha256()));
    assertTrue(json.contains(doc.sourceUrl()));
    assertFalse(json.contains("PRIVATE-SOURCE"));
    assertFalse(json.contains(Base64.getEncoder().encodeToString(secret)));
    assertFalse(json.contains("/home/private"));
  }

  @Test
  void allNameStatesPreservePhysicalEmptyNamesAndNeverInheritCopies() {
    for (var match :
        List.of(
            new ItemNameMatch(
                101,
                ItemNameMatch.Outcome.PHYSICAL_NAME,
                Optional.of(new DisplayCandidate("", 101, 0, 0, 100, 5, 5)),
                OptionalLong.empty()),
            new ItemNameMatch(
                101, ItemNameMatch.Outcome.COPY_UNINDEXED, Optional.empty(), OptionalLong.of(202)),
            new ItemNameMatch(
                101,
                ItemNameMatch.Outcome.NO_PHYSICAL_MATCH,
                Optional.empty(),
                OptionalLong.empty()))) {
      String json = json(ItemExportFixture.paired(match, 2, "chunk-v1"));
      assertTrue(json.contains("\"state\":\"" + match.outcome() + "\""));
      if (match.candidate().isPresent()) assertTrue(json.contains("\"value\":\"\""));
      else assertTrue(json.contains("\"candidate\":null"));
      assertTrue(json.contains("\"sparseTableFileDataId\":1572924"));
      assertTrue(json.contains("\"fullDecoded\":null"));
    }
  }

  @Test
  void full3780ChunkReceiptFitsShortRevisionsButNeverTruncatesOversizeProof() {
    var match =
        new ItemNameMatch(
            101, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, Optional.empty(), OptionalLong.empty());
    byte[] bytes =
        encoder.encode(
            ItemExportFixture.paired(match, 3780, "chunk-v1"), created, CancellationToken.none());
    String json = new String(bytes, StandardCharsets.UTF_8);
    assertTrue(json.contains("\"ordinal\":3779"));
    assertTrue(bytes.length < ItemEvidenceReceipt.MAXIMUM_UTF8_BYTES);
    assertEquals(
        ItemEvidenceException.Code.LIMIT_EXCEEDED,
        assertThrows(
                ItemEvidenceException.class,
                () -> json(ItemExportFixture.paired(match, 3780, "\\".repeat(400))))
            .code());
  }

  @Test
  void ceilingIsExactAndCheckedOnEncodedBytes() {
    int base =
        encoder.encode(ItemExportFixture.edition("x", List.of()), created, CancellationToken.none())
            .length;
    int fill = Math.toIntExact(ItemEvidenceReceipt.MAXIMUM_UTF8_BYTES - base + 1);
    assertEquals(
        ItemEvidenceReceipt.MAXIMUM_UTF8_BYTES,
        encoder.encode(
                ItemExportFixture.edition("x".repeat(fill), List.of()),
                created,
                CancellationToken.none())
            .length);
    assertEquals(
        ItemEvidenceException.Code.LIMIT_EXCEEDED,
        assertThrows(
                ItemEvidenceException.class,
                () -> json(ItemExportFixture.edition("x".repeat(fill + 1), List.of())))
            .code());
  }

  @Test
  void rawWidthsAndUnsigned64UseLosslessBitPatterns() {
    for (int width = 1; width <= 64; width++) {
      long bits = width == 64 ? -1L : (1L << width) - 1;
      String expected =
          java.math.BigInteger.ONE.shiftLeft(width).subtract(java.math.BigInteger.ONE).toString(16);
      String json =
          json(ItemExportFixture.integer(width, bits, ItemArtifactDefinition.Sign.UNSIGNED, bits));
      assertTrue(json.contains("\"rawWidth\":" + width + ",\"rawHex\":\"" + expected + "\""));
      String decimal =
          java.math.BigInteger.ONE.shiftLeft(width).subtract(java.math.BigInteger.ONE).toString();
      assertTrue(json.contains("\"candidateDecimal\":\"" + decimal + "\""));
    }
    assertTrue(
        json(ItemExportFixture.integer(
                64, Long.MIN_VALUE, ItemArtifactDefinition.Sign.SIGNED, Long.MIN_VALUE))
            .contains("\"candidateDecimal\":\"-9223372036854775808\""));
  }

  @Test
  void cancellationAndExpandedYearInstantsRemainExplicit() {
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> encoder.encode(ItemExportFixture.snapshot(), created, () -> true));
    String json =
        new String(
            encoder.encode(ItemExportFixture.snapshot(), Instant.MAX, CancellationToken.none()),
            StandardCharsets.UTF_8);
    assertTrue(json.contains(Instant.MAX.toString()));
  }
}
