package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.item.ItemProjectionException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.DecodedContentAttestation;
import io.vibeminer.domain.item.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ItemProjectionRecordsTest {
  static ItemArtifactDefinition definition() {
    var bindings = new ArrayList<ItemArtifactDefinition.FieldBinding>();
    for (var field : ItemArtifactDefinition.Field.values())
      bindings.add(
          new ItemArtifactDefinition.FieldBinding(
              field, field.ordinal() + 1, 32, ItemArtifactDefinition.Sign.SIGNED));
    var schema =
        new ItemArtifactDefinition.Schema(
            "WoWDBDefs — fixture",
            "revision",
            "Item.dbd",
            "a".repeat(64),
            "b".repeat(64),
            "CC BY-SA 4.0",
            "Contributors",
            bindings);
    return new ItemArtifactDefinition(
        List.of(
            ItemSourceFixture.association("fixture", Instant.EPOCH)
                .representation()
                .encoded()
                .object()),
        new ItemArtifactDefinition.Interpretation(
            "decoder-v1",
            "index-v1",
            0x50238ec2L,
            0x996192aaL,
            0,
            664,
            38,
            schema,
            "enUS",
            "physical-only",
            "encoded-and-window-v1"));
  }

  static ItemProjectionEvidence evidence(boolean full) {
    var association = ItemSourceFixture.association("fixture", Instant.ofEpochSecond(123, 456));
    return new ItemProjectionEvidence(
        association,
        new ItemProjectionEvidence.LocalVerification(
            association.representation().encoded().object(),
            Instant.ofEpochSecond(789, 123456789),
            "sha256-v1"),
        List.of(
            new ItemProjectionEvidence.ChunkAttestation(
                0,
                36,
                association.representation().encoded().object().length() - 36,
                "f".repeat(32),
                0,
                702,
                "chunk-md5-v1")),
        List.of(
            new ItemProjectionEvidence.DecodedWindow(0, 664),
            new ItemProjectionEvidence.DecodedWindow(664, 38)),
        full
            ? Optional.of(
                new DecodedContentAttestation(
                    association.descriptor().association().contentKey(), 702, "decoded-md5-v1"))
            : Optional.empty());
  }

  static ItemPhysicalRow row() {
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var field : ItemArtifactDefinition.Field.values())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              field,
              -1L,
              64,
              field.ordinal() % 2 == 0
                  ? ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS
                  : ItemPhysicalRow.PhysicalInterpretation.SIGNED_TWOS_COMPLEMENT,
              -1L,
              64,
              field.ordinal() % 2 == 0
                  ? ItemArtifactDefinition.Sign.UNSIGNED
                  : ItemArtifactDefinition.Sign.SIGNED));
    return new ItemPhysicalRow(0xffffffffL, 7, 0, values);
  }

  @Test
  void definitionRoundTripRecomputesIdentityAndPreservesInterpretation() {
    var value = definition();
    var restored = ItemProjectionRecords.definition(ItemProjectionRecords.definition(value));
    assertEquals(value, restored);
    assertEquals(value.artifactId(), restored.artifactId());
    assertThrows(UnsupportedOperationException.class, () -> restored.sources().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> restored.interpretation().schema().fields().clear());
    byte[] changed = ItemProjectionRecords.definition(value);
    // A valid changed source digest must derive a different key, never reuse a serialized claim.
    byte[] needle =
        value
            .sources()
            .getFirst()
            .digest()
            .value()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int at = indexOf(changed, needle);
    assertTrue(at >= 0);
    changed[at] = changed[at] == 'a' ? (byte) 'b' : (byte) 'a';
    assertNotEquals(value.artifactId(), ItemProjectionRecords.definition(changed).artifactId());
  }

  @Test
  void evidenceRoundTripPreservesOriginalReceiptsTimesAndSeparateCapabilities() {
    for (boolean full : new boolean[] {false, true}) {
      var value = evidence(full);
      var restored =
          ItemProjectionRecords.evidence(
              ItemProjectionRecords.evidence(value),
              value.association().descriptor().association().build());
      assertEquals(value, restored);
      assertEquals(full, restored.fullDecoded().isPresent());
      assertEquals(
          value.association().proof().authenticatedCandidates(),
          restored.association().proof().authenticatedCandidates());
      assertThrows(UnsupportedOperationException.class, () -> restored.chunks().clear());
    }
  }

  @Test
  void rowsPreserveAllUnsigned64BitsAndCandidateInterpretations() {
    var value = row();
    var restored = ItemProjectionRecords.row(ItemProjectionRecords.row(value));
    assertEquals(value, restored);
    assertEquals(-1L, restored.values().getFirst().rawBits());
    assertEquals(0xffffffffL, restored.recordId());
    assertEquals(
        ItemArtifactDefinition.Sign.UNSIGNED, restored.values().getFirst().candidateSign());
    assertThrows(UnsupportedOperationException.class, () -> restored.values().clear());
  }

  @Test
  void archivedOriginalSourceAndIndexGeometryRemainUnchanged() {
    var e = evidence(true);
    var a = e.association();
    var r = a.representation();
    String archive = "9".repeat(32), url = "https://us.cdn.blizzard.com/archive/" + archive;
    var encoded =
        new VerifiedSourceAlias(
            r.encoded().source(),
            r.encoded().object(),
            new SourceVerificationEvidence(
                r.encoded().evidence().verifierSemantics(),
                url,
                r.encoded().evidence().verifiedAt()));
    var mapping =
        new io.vibeminer.domain.selectedfile.ArchiveMappingProof(
            archive,
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "index-v1",
            OptionalLong.of(12388),
            4148);
    var receipt =
        new io.vibeminer.domain.selectedfile.EncodedSourceReceipt(
            io.vibeminer.domain.selectedfile.EncodedSourceReceipt.Kind.ARCHIVE,
            url,
            archive,
            r.encoded().object().length() + 100,
            100,
            r.encoded().object().length(),
            Instant.ofEpochSecond(234, 567),
            2,
            r.encoded().object().length(),
            "4".repeat(64),
            Optional.of(mapping));
    var representation =
        new io.vibeminer.domain.selectedfile.ContentRepresentation(
            r.slot(), encoded, r.scope(), receipt);
    var association =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            a.descriptor(), a.configuration(), a.root(), a.proof(), representation);
    var value =
        new ItemProjectionEvidence(
            association, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded());
    assertEquals(
        value,
        ItemProjectionRecords.evidence(
            ItemProjectionRecords.evidence(value), a.descriptor().association().build()));
  }

  @Test
  void everyIncompleteAndTrailingRecordIsTypedCorrupt() {
    var e = evidence(true);
    assertTruncations(
        ItemProjectionRecords.definition(definition()), ItemProjectionRecords::definition);
    assertTruncations(ItemProjectionRecords.row(row()), ItemProjectionRecords::row);
    assertTruncations(
        ItemProjectionRecords.evidence(e),
        bytes ->
            ItemProjectionRecords.evidence(
                bytes, e.association().descriptor().association().build()));
  }

  @Test
  void malformedUtf8VersionLengthAndOversizedRecordsAreRejected() {
    byte[] bytes = ItemProjectionRecords.definition(definition());
    for (int mode = 0; mode < 3; mode++) {
      byte[] bad = bytes.clone();
      if (mode == 0) bad[4] = (byte) 0xff;
      if (mode == 1) bad[4] = 'X';
      if (mode == 2) ByteBuffer.wrap(bad).putInt(Integer.MAX_VALUE);
      corrupt(() -> ItemProjectionRecords.definition(bad));
    }
    corrupt(
        () ->
            ItemProjectionRecords.evidence(
                new byte[ItemProjectionRecords.MAX_RECORD_BYTES + 1],
                evidence(false).association().descriptor().association().build()));
    corrupt(() -> ItemProjectionRecords.row(new byte[ItemProjectionRecords.MAX_ROW_BYTES + 1]));
  }

  @Test
  void impossibleCollectionCountsFailBeforeAllocation() {
    byte[] definition = ItemProjectionRecords.definition(definition());
    int countAt = 4 + ByteBuffer.wrap(definition).getInt();
    ByteBuffer.wrap(definition).putInt(countAt, Integer.MAX_VALUE);
    corrupt(() -> ItemProjectionRecords.definition(definition));
    byte[] row = ItemProjectionRecords.row(row());
    int rowCountAt = 4 + ByteBuffer.wrap(row).getInt() + 8 + 4 + 4;
    ByteBuffer.wrap(row).putInt(rowCountAt, Integer.MAX_VALUE);
    corrupt(() -> ItemProjectionRecords.row(row));
  }

  @Test
  void evidenceCannotBeReboundToAnotherExactSnapshot() {
    var value = evidence(false);
    var wrong =
        ItemSourceFixture.association("another-build", Instant.EPOCH)
            .descriptor()
            .association()
            .build();
    corrupt(() -> ItemProjectionRecords.evidence(ItemProjectionRecords.evidence(value), wrong));
  }

  @Test
  void aggregateEncodingLimitStopsOversizedBoundedMetadata() {
    var base = evidence(false);
    var chunks = new ArrayList<ItemProjectionEvidence.ChunkAttestation>();
    for (int i = 0; i < 702; i++)
      chunks.add(
          new ItemProjectionEvidence.ChunkAttestation(
              i, i, 1, "f".repeat(32), i, 1, "r".repeat(4096)));
    var oversized =
        new ItemProjectionEvidence(
            base.association(),
            base.localVerification(),
            chunks,
            List.of(new ItemProjectionEvidence.DecodedWindow(0, 702)),
            Optional.empty());
    var failure =
        assertThrows(
            ItemProjectionException.class, () -> ItemProjectionRecords.evidence(oversized));
    assertEquals(ItemProjectionException.Code.LIMIT_EXCEEDED, failure.code());
  }

  @Test
  void invalidJavaSurrogatesCannotSilentlyRewriteInterpretationOrNestedProvenance() {
    var definition = definition();
    var i = definition.interpretation();
    var s = i.schema();
    var badSchema =
        new ItemArtifactDefinition.Schema(
            "unpaired-\ud800",
            s.revision(),
            s.definitionPath(),
            s.definitionSha256(),
            s.selectionDigest(),
            s.license(),
            s.attribution(),
            s.fields());
    var badDefinition =
        new ItemArtifactDefinition(
            definition.sources(),
            new ItemArtifactDefinition.Interpretation(
                i.decoderRevision(),
                i.indexRevision(),
                i.tableHash(),
                i.layoutHash(),
                i.sectionOrdinal(),
                i.sectionDecodedOffset(),
                i.sectionDecodedBytes(),
                badSchema,
                i.locale(),
                i.optionsRevision(),
                i.proofSemantics()));
    corrupt(() -> ItemProjectionRecords.definition(badDefinition));
    var e = evidence(false);
    var a = e.association();
    var c = a.configuration();
    var badConfig =
        new VerifiedSourceAlias(
            c.source(),
            c.object(),
            new SourceVerificationEvidence(
                c.evidence().verifierSemantics(),
                "https://example.invalid/unpaired-\ud800",
                c.evidence().verifiedAt()));
    var badAssociation =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            a.descriptor(), badConfig, a.root(), a.proof(), a.representation());
    var badEvidence =
        new ItemProjectionEvidence(
            badAssociation, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded());
    corrupt(() -> ItemProjectionRecords.evidence(badEvidence));
  }

  static void assertTruncations(byte[] encoded, java.util.function.Function<byte[], ?> decoder) {
    for (int n = 0; n < encoded.length; n++) {
      byte[] shortRecord = Arrays.copyOf(encoded, n);
      corrupt(() -> decoder.apply(shortRecord));
    }
    corrupt(() -> decoder.apply(Arrays.copyOf(encoded, encoded.length + 1)));
  }

  static void corrupt(Runnable operation) {
    assertEquals(
        ItemProjectionException.Code.CORRUPT,
        assertThrows(ItemProjectionException.class, operation::run).code());
  }

  static int indexOf(byte[] bytes, byte[] needle) {
    outer:
    for (int i = 0; i <= bytes.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (bytes[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
