package io.vibeminer.domain.item;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemValuesUtf16Test {
  private static final String SCHEMA_SHA = "c".repeat(64);
  private static final List<String> MALFORMED =
      List.of(
          String.valueOf((char) 0xd800),
          String.valueOf((char) 0xd801),
          String.valueOf((char) 0xdc00),
          String.valueOf((char) 0xdfff),
          "revision-" + (char) 0xd800 + "x",
          "revision-" + (char) 0xdc00 + (char) 0xd800,
          "revision-" + (char) 0xd800 + (char) 0xd801,
          "revision-" + (char) 0xdc00 + (char) 0xdc01,
          "revision-" + new String(Character.toChars(0x1f680)) + (char) 0xd800);

  @Test
  void distinctMalformedDecoderStringsCannotBeAcceptedAsCollidingArtifacts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ItemContractsTest.definition(String.valueOf((char) 0xd800), "enUS", SCHEMA_SHA));
    assertThrows(
        IllegalArgumentException.class,
        () -> ItemContractsTest.definition(String.valueOf((char) 0xd801), "enUS", SCHEMA_SHA));
  }

  @Test
  void everyFreeformInterpretationFieldRejectsMalformedSequencesThroughPublicConstructor() {
    for (var value : MALFORMED)
      for (int field = 0; field < 4; field++) {
        int selected = field;
        assertThrows(IllegalArgumentException.class, () -> interpretation(selected, value));
      }
  }

  @Test
  void everyFreeformSchemaFieldRejectsMalformedSequencesThroughPublicConstructor() {
    for (var value : MALFORMED)
      for (int field = 0; field < 5; field++) {
        int selected = field;
        assertThrows(IllegalArgumentException.class, () -> schema(selected, value));
      }
  }

  @Test
  void asciiBmpAndSupplementaryGoldenArtifactIdsRemainUnchanged() {
    // Captured from the existing v1 format and independently calculated using length-prefixed
    // UTF-8 and big-endian fields. Validation must not change serialization or its separator.
    assertEquals(
        "d6bf1aa82c7530b1c8ffd4f272b0573bfb2f97c8a01cc6edc5b0ece8386250f2",
        ItemContractsTest.definition("decoder-v1", "enUS", SCHEMA_SHA).artifactId());
    assertEquals(
        "22c8137b9c05f885f787f1a908e6e5f05fcd8702f19a1979c42b9ce8c077f2ec",
        ItemContractsTest.definition("decoder-é-東京", "enUS", SCHEMA_SHA).artifactId());
    assertEquals(
        "260b8ecd72dd3dcfaf42004a206801a204c9dba8de2a6460013c8888bcb91027",
        ItemContractsTest.definition(
                "decoder-"
                    + new String(Character.toChars(0x1f680))
                    + "-"
                    + new String(Character.toChars(0x10ffff)),
                "enUS",
                SCHEMA_SHA)
            .artifactId());
  }

  @Test
  void legitimatePairedUnicodeRemainsAcceptedInEveryIdentityFieldWithoutNormalization() {
    for (var value : List.of("révision-東京", "revision-" + new String(Character.toChars(0x1f680)))) {
      assertSame(value, ItemValues.text(value));
      for (int field = 0; field < 4; field++) assertNotNull(interpretation(field, value));
      for (int field = 0; field < 5; field++) assertNotNull(schema(field, value));
    }
    assertNotEquals(
        ItemContractsTest.definition("revision-é", "enUS", SCHEMA_SHA).artifactId(),
        ItemContractsTest.definition("revision-e\u0301", "enUS", SCHEMA_SHA).artifactId());
  }

  @Test
  void existingUtf16LengthBoundaryKeepsCompletePairsAndRejectsTruncatedPair() {
    var paired = new String(Character.toChars(0x1f680)).repeat(2048);
    assertSame(paired, ItemValues.text(paired));
    assertThrows(
        IllegalArgumentException.class, () -> ItemValues.text("x".repeat(4095) + (char) 0xd800));
    assertThrows(IllegalArgumentException.class, () -> ItemValues.text(paired + "x"));
  }

  @Test
  void nonhashedVerifierMetadataUsesTheSameWellFormedPersistenceBoundary() {
    var source =
        ItemContractsTest.definition("decoder-v1", "enUS", SCHEMA_SHA).sources().getFirst();
    for (var value : MALFORMED) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ItemProjectionEvidence.LocalVerification(source, Instant.EPOCH, value));
      assertThrows(
          IllegalArgumentException.class,
          () -> new ItemProjectionEvidence.ChunkAttestation(0, 0, 1, "a".repeat(32), 0, 1, value));
    }
  }

  private static ItemArtifactDefinition.Interpretation interpretation(int selected, String value) {
    var i = ItemContractsTest.definition("decoder-v1", "enUS", SCHEMA_SHA).interpretation();
    return new ItemArtifactDefinition.Interpretation(
        selected == 0 ? value : i.decoderRevision(),
        selected == 1 ? value : i.indexRevision(),
        i.tableHash(),
        i.layoutHash(),
        i.sectionOrdinal(),
        i.sectionDecodedOffset(),
        i.sectionDecodedBytes(),
        i.schema(),
        i.locale(),
        selected == 2 ? value : i.optionsRevision(),
        selected == 3 ? value : i.proofSemantics());
  }

  private static ItemArtifactDefinition.Schema schema(int selected, String value) {
    var s = ItemContractsTest.schema(SCHEMA_SHA);
    return new ItemArtifactDefinition.Schema(
        selected == 0 ? value : s.provider(),
        selected == 1 ? value : s.revision(),
        selected == 2 ? value : s.definitionPath(),
        s.definitionSha256(),
        s.selectionDigest(),
        selected == 3 ? value : s.license(),
        selected == 4 ? value : s.attribution(),
        s.fields());
  }
}
