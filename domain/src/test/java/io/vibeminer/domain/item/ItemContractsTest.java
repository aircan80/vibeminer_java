package io.vibeminer.domain.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ItemContractsTest {
  static ItemArtifactDefinition.Schema schema(String sha) {
    var bindings = new ArrayList<ItemArtifactDefinition.FieldBinding>();
    for (var field : ItemArtifactDefinition.Field.values())
      bindings.add(
          new ItemArtifactDefinition.FieldBinding(
              field, field.ordinal(), 32, ItemArtifactDefinition.Sign.UNSIGNED));
    return new ItemArtifactDefinition.Schema(
        "WoWDBDefs",
        "revision",
        "definitions/Item.dbd",
        sha,
        "b".repeat(64),
        "CC BY-SA 4.0",
        "WoWDBDefs contributors",
        bindings);
  }

  static ItemArtifactDefinition definition(String decoder, String locale, String schemaSha) {
    var interpretation =
        new ItemArtifactDefinition.Interpretation(
            decoder,
            "index-v1",
            0x50238ec2L,
            0x996192aaL,
            0,
            7152,
            2129287,
            schema(schemaSha),
            locale,
            "physical-only-v1",
            "blte-window-v1");
    return new ItemArtifactDefinition(
        List.of(new StoredObject(new ObjectDigest("a".repeat(64)), 738344)), interpretation);
  }

  @Test
  void artifactIdentityDependsOnPhysicalAndInterpretationInputsNotBuildObservation() {
    var original = definition("decoder-v1", "enUS", "c".repeat(64));
    assertEquals(
        original.artifactId(), definition("decoder-v1", "enUS", "c".repeat(64)).artifactId());
    assertNotEquals(
        original.artifactId(), definition("decoder-v2", "enUS", "c".repeat(64)).artifactId());
    assertNotEquals(
        original.artifactId(), definition("decoder-v1", "frFR", "c".repeat(64)).artifactId());
    assertNotEquals(
        original.artifactId(), definition("decoder-v1", "enUS", "d".repeat(64)).artifactId());
    assertEquals(64, original.artifactId().length());
  }

  @Test
  void sourceAndBindingsAreImmutableAndExactFourFieldsRequired() {
    var sources = new ArrayList<>(definition("v1", "enUS", "c".repeat(64)).sources());
    var artifact =
        new ItemArtifactDefinition(
            sources, definition("v1", "enUS", "c".repeat(64)).interpretation());
    sources.clear();
    assertEquals(1, artifact.sources().size());
    assertThrows(UnsupportedOperationException.class, () -> artifact.sources().clear());
    var base = schema("c".repeat(64));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemArtifactDefinition.Schema(
                base.provider(),
                base.revision(),
                base.definitionPath(),
                base.definitionSha256(),
                base.selectionDigest(),
                base.license(),
                base.attribution(),
                base.fields().subList(0, 3)));
  }

  @Test
  void rawBitsAndCandidateSignednessStaySeparate() {
    var value =
        new ItemPhysicalRow.IntegerCandidate(
            ItemArtifactDefinition.Field.CLASS_ID,
            0xffffffffL,
            32,
            ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
            -1,
            32,
            ItemArtifactDefinition.Sign.SIGNED);
    assertEquals(4294967295L, value.rawBits());
    assertEquals(-1, value.candidateValue());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemPhysicalRow.IntegerCandidate(
                ItemArtifactDefinition.Field.CLASS_ID,
                256,
                8,
                ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
                0,
                8,
                ItemArtifactDefinition.Sign.UNSIGNED));
  }

  @Test
  void unsignedIdsAndScopedLookupShapesAreStrict() {
    var coverage = new PreparedItemProjection.Coverage(0, 2, 3, 4);
    var copy =
        new ItemLookupResult(
            0xffffffffL,
            ItemLookupResult.Outcome.COPY_UNINDEXED,
            Optional.empty(),
            OptionalLong.of(1),
            coverage);
    assertEquals(0xffffffffL, copy.requestedId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemLookupResult(
                -1,
                ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
                Optional.empty(),
                OptionalLong.empty(),
                coverage));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemLookupResult(
                1,
                ItemLookupResult.Outcome.PHYSICAL_MATCH,
                Optional.empty(),
                OptionalLong.empty(),
                coverage));
  }

  static io.vibeminer.domain.selectedfile.SelectedFileAssociation association(
      String version, java.time.Instant observed) {
    var build =
        new BuildSnapshot(
            "wow",
            "Fixture",
            "current",
            "us",
            version,
            "c".repeat(32),
            "d".repeat(32),
            observed,
            true);
    var configSource = new ResolvedSourceIdentity("config", "c".repeat(32), "v1");
    var rootSource = new ResolvedSourceIdentity("root", "root", "v1");
    var config =
        new VerifiedSourceAlias(
            configSource,
            new StoredObject(new ObjectDigest("1".repeat(64)), 10),
            new SourceVerificationEvidence("v1", "https://us.cdn.blizzard.com/config", observed));
    var root =
        new VerifiedSourceAlias(
            rootSource,
            new StoredObject(new ObjectDigest("2".repeat(64)), 20),
            new SourceVerificationEvidence("v1", "https://us.cdn.blizzard.com/root", observed));
    var variant =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            build, rootSource, 841626, "e".repeat(32), 1, 2, 3, 4, 5, Optional.empty());
    var descriptor =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            variant,
            "f".repeat(32),
            io.vibeminer.domain.selectedfile.ContentRepresentation.VERIFIER_SEMANTICS);
    var source =
        io.vibeminer.domain.selectedfile.ContentRepresentation.encodedIdentity("f".repeat(32));
    var evidence =
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/encoded", observed);
    var encoded =
        new VerifiedSourceAlias(
            source, new StoredObject(new ObjectDigest("a".repeat(64)), 738344), evidence);
    var receipt =
        new io.vibeminer.domain.selectedfile.EncodedSourceReceipt(
            io.vibeminer.domain.selectedfile.EncodedSourceReceipt.Kind.LOOSE,
            evidence.sourceUrl(),
            "f".repeat(32),
            738344,
            0,
            738344,
            observed,
            1,
            738344,
            "3".repeat(64),
            Optional.empty());
    var representation =
        new io.vibeminer.domain.selectedfile.ContentRepresentation(
            variant.slot(),
            encoded,
            io.vibeminer.domain.selectedfile.EncodedScope.ZERO_HEADER_WHOLE_ENCODED,
            receipt);
    var proof =
        new io.vibeminer.domain.selectedfile.FileAssociationProof(
            rootSource,
            new ResolvedSourceIdentity("encoding", "encoding", "v1"),
            "association-v2",
            "4".repeat(64),
            java.util.OptionalLong.of(3000000),
            List.of(
                new io.vibeminer.domain.selectedfile.EncodedCandidate(
                    "f".repeat(32),
                    java.util.OptionalLong.of(738344),
                    java.util.OptionalLong.empty())));
    return new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
        descriptor, config, root, proof, representation);
  }

  static ItemProjectionEvidence evidence(String version, java.time.Instant time) {
    var association = association(version, time);
    return new ItemProjectionEvidence(
        association,
        new ItemProjectionEvidence.LocalVerification(
            association.representation().encoded().object(), time, "sha256-v1"),
        List.of(
            new ItemProjectionEvidence.ChunkAttestation(
                0, 8, 738336, "f".repeat(32), 0, 3000000, "blte-window-v1")),
        List.of(new ItemProjectionEvidence.DecodedWindow(7152, 2129287)),
        Optional.empty());
  }

  @Test
  void sharedArtifactKeepsDifferentBuildAndObservationContextsSeparate() {
    var definition = definition("v1", "enUS", "c".repeat(64));
    var coverage = new PreparedItemProjection.Coverage(0, 59859, 153925, 49);
    var a =
        new PreparedItemProjection(definition, evidence("1", java.time.Instant.EPOCH), coverage);
    var b =
        new PreparedItemProjection(
            definition, evidence("2", java.time.Instant.ofEpochSecond(100)), coverage);
    assertEquals(a.artifactId(), b.artifactId());
    assertNotEquals(a.snapshotId(), b.snapshotId());
    assertTrue(a.evidence().fullDecoded().isEmpty());
    var cursor =
        new ItemPageCursor(
            a.artifactId(),
            a.snapshotId(),
            a.sourceRole(),
            a.locale(),
            ItemPageCursor.QUERY_REVISION,
            42,
            50);
    assertDoesNotThrow(() -> cursor.requireContext(a, 50));
    assertThrows(IllegalArgumentException.class, () -> cursor.requireContext(b, 50));
    assertThrows(IllegalArgumentException.class, () -> cursor.requireContext(a, 100));
  }

  @Test
  void windowAndFullCKeyCapabilitiesCannotBeInferredOrMismatched() {
    var base = evidence("1", java.time.Instant.EPOCH);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemProjectionEvidence(
                base.association(),
                base.localVerification(),
                base.chunks(),
                List.of(new ItemProjectionEvidence.DecodedWindow(3000000, 1)),
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemProjectionEvidence(
                base.association(),
                base.localVerification(),
                base.chunks(),
                base.windows(),
                Optional.of(
                    new io.vibeminer.domain.content.DecodedContentAttestation(
                        "a".repeat(32), 3000000, "full-v1"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemProjectionEvidence(
                base.association(),
                base.localVerification(),
                base.chunks(),
                base.windows(),
                Optional.of(
                    new io.vibeminer.domain.content.DecodedContentAttestation(
                        "e".repeat(32), 100, "full-v1"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemPageCursor(
                "a".repeat(64),
                "b".repeat(64),
                base.association().role(),
                "enUS",
                ItemPageCursor.QUERY_REVISION,
                0x100000000L,
                50));
  }

  @Test
  void changedSourceSectionAndProofSemanticsInvalidateArtifact() {
    var original = definition("v1", "enUS", "c".repeat(64));
    var i = original.interpretation();
    var movedSection =
        new ItemArtifactDefinition.Interpretation(
            i.decoderRevision(),
            i.indexRevision(),
            i.tableHash(),
            i.layoutHash(),
            1,
            i.sectionDecodedOffset(),
            i.sectionDecodedBytes(),
            i.schema(),
            i.locale(),
            i.optionsRevision(),
            i.proofSemantics());
    var changedProof =
        new ItemArtifactDefinition.Interpretation(
            i.decoderRevision(),
            i.indexRevision(),
            i.tableHash(),
            i.layoutHash(),
            i.sectionOrdinal(),
            i.sectionDecodedOffset(),
            i.sectionDecodedBytes(),
            i.schema(),
            i.locale(),
            i.optionsRevision(),
            "different-scope-v2");
    assertNotEquals(
        original.artifactId(),
        new ItemArtifactDefinition(original.sources(), movedSection).artifactId());
    assertNotEquals(
        original.artifactId(),
        new ItemArtifactDefinition(original.sources(), changedProof).artifactId());
    assertNotEquals(
        original.artifactId(),
        new ItemArtifactDefinition(
                List.of(new StoredObject(new ObjectDigest("b".repeat(64)), 738344)), i)
            .artifactId());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemArtifactDefinition.Interpretation(
                i.decoderRevision(),
                i.indexRevision(),
                i.tableHash(),
                i.layoutHash(),
                0,
                Long.MAX_VALUE,
                1,
                i.schema(),
                i.locale(),
                i.optionsRevision(),
                i.proofSemantics()));
  }

  @Test
  void declaredAndFullDecodedBoundsConstrainEveryConsumedChunk() {
    var base = evidence("1", java.time.Instant.EPOCH);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemProjectionEvidence(
                base.association(),
                base.localVerification(),
                base.chunks(),
                base.windows(),
                Optional.of(
                    new io.vibeminer.domain.content.DecodedContentAttestation(
                        "e".repeat(32), 3000001, "full-v1"))));
    var excessive =
        List.of(
            new ItemProjectionEvidence.ChunkAttestation(
                0, 8, 738336, "f".repeat(32), 0, 3000001, "blte-window-v1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemProjectionEvidence(
                base.association(),
                base.localVerification(),
                excessive,
                base.windows(),
                Optional.empty()));
  }
}
