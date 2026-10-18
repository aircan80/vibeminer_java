package io.vibeminer.domain.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ItemNamesContractsTest {
  @Test
  void malformedUtf16InterpretationAndSchemaTextCannotAliasArtifactHashes() {
    var valid = definition("name-v1", "enUS", schemaSha());
    var i = valid.interpretation();
    var s = i.schema();
    for (String malformed : List.of("revision-\ud800", "revision-\ud801", "revision-\udc00")) {
      assertThrows(
          IllegalArgumentException.class, () -> definition(malformed, "enUS", schemaSha()));
      for (int position = 0; position < 3; position++) {
        int slot = position;
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SparseNameArtifactDefinition.Interpretation(
                    i.decoderRevision(),
                    slot == 0 ? malformed : i.indexRevision(),
                    i.tableHash(),
                    i.layoutHash(),
                    i.sectionOrdinal(),
                    i.metadataPrefixBytes(),
                    i.recordAreaOffset(),
                    i.recordAreaBytes(),
                    i.suffixOffset(),
                    i.suffixBytes(),
                    s,
                    i.locale(),
                    slot == 1 ? malformed : i.optionsRevision(),
                    slot == 2 ? malformed : i.proofSemantics()));
      }
      for (int position = 0; position < 5; position++) {
        int slot = position;
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SparseNameArtifactDefinition.Schema(
                    slot == 0 ? malformed : s.provider(),
                    slot == 1 ? malformed : s.revision(),
                    slot == 2 ? malformed : s.definitionPath(),
                    s.definitionSha256(),
                    s.selectionDigest(),
                    slot == 3 ? malformed : s.license(),
                    slot == 4 ? malformed : s.attribution(),
                    s.prefixFields()));
      }
    }
  }

  @Test
  void matchingTableLayoutCannotBorrowAnUnreviewedItemSchema() {
    var unsupported =
        new PreparedItemProjection(
            ItemContractsTest.definition("item-v1", "enUS", "c".repeat(64)),
            evidence(false, Instant.EPOCH),
            item(Instant.EPOCH).coverage());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PreparedItemNames(unsupported, names(Instant.EPOCH)));
  }

  static SparseNameArtifactDefinition definition(String decoder, String locale, String sha) {
    var schema =
        new SparseNameArtifactDefinition.Schema(
            "WoWDBDefs",
            "b9a63096cdd99b26967f17f9515377ae62cadfd7",
            "definitions/ItemSparse.dbd",
            sha,
            "c".repeat(64),
            "CC BY-SA-4.0",
            "WoWDBDefs contributors",
            List.of(
                "Description_lang",
                "Display3_lang",
                "Display2_lang",
                "Display1_lang",
                "Display_lang"));
    return new SparseNameArtifactDefinition(
        List.of(new StoredObject(new ObjectDigest("b".repeat(64)), 738344)),
        new SparseNameArtifactDefinition.Interpretation(
            decoder,
            "names-index-v1",
            0x919be54eL,
            0x1c17d17fL,
            0,
            4040,
            5252,
            100,
            5352,
            36,
            schema,
            locale,
            "physical-only-copies-excluded-v1",
            "complete-encoded-and-windows-v1"));
  }

  static String schemaSha() {
    return "8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a";
  }

  static ItemProjectionEvidence evidence(boolean sparse, Instant time) {
    var association = ItemNamesFixture.association(sparse, time);
    return new ItemProjectionEvidence(
        association,
        new ItemProjectionEvidence.LocalVerification(
            association.representation().encoded().object(), time, "local-v1"),
        List.of(
            new ItemProjectionEvidence.ChunkAttestation(
                0, 8, 738336, "d".repeat(32), 0, 3000000, "chunk-v1")),
        sparse
            ? List.of(
                new ItemProjectionEvidence.DecodedWindow(0, 4040),
                new ItemProjectionEvidence.DecodedWindow(5252, 100),
                new ItemProjectionEvidence.DecodedWindow(5352, 36))
            : List.of(new ItemProjectionEvidence.DecodedWindow(7152, 2129287)),
        Optional.empty());
  }

  static PreparedSparseNames names(Instant time) {
    return new PreparedSparseNames(
        definition("name-v1", "enUS", schemaSha()),
        evidence(true, time),
        new PreparedSparseNames.Coverage(0, 2, 1, 48, 8));
  }

  static PreparedItemProjection item(Instant time) {
    var base = ItemContractsTest.definition("item-v1", "enUS", "c".repeat(64));
    var i = base.interpretation();
    var fields =
        List.of(
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.CLASS_ID, 0, 32, ItemArtifactDefinition.Sign.SIGNED),
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.SUBCLASS_ID,
                1,
                8,
                ItemArtifactDefinition.Sign.UNSIGNED),
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.INVENTORY_TYPE,
                3,
                8,
                ItemArtifactDefinition.Sign.SIGNED),
            new ItemArtifactDefinition.FieldBinding(
                ItemArtifactDefinition.Field.ICON_FILE_DATA_ID,
                6,
                32,
                ItemArtifactDefinition.Sign.SIGNED));
    var schema =
        new ItemArtifactDefinition.Schema(
            "WoWDBDefs",
            "b9a63096cdd99b26967f17f9515377ae62cadfd7",
            "definitions/Item.dbd",
            "076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120",
            "c".repeat(64),
            "CC BY-SA-4.0",
            "WoWDBDefs contributors",
            fields);
    var definition =
        new ItemArtifactDefinition(
            base.sources(),
            new ItemArtifactDefinition.Interpretation(
                i.decoderRevision(),
                i.indexRevision(),
                i.tableHash(),
                i.layoutHash(),
                i.sectionOrdinal(),
                i.sectionDecodedOffset(),
                i.sectionDecodedBytes(),
                schema,
                i.locale(),
                i.optionsRevision(),
                i.proofSemantics()));
    return new PreparedItemProjection(
        definition, evidence(false, time), new PreparedItemProjection.Coverage(0, 2, 1, 49));
  }

  @Test
  void sharedNameIdentityExcludesItemAndObservationButChangesWithInterpretation() {
    var a = names(Instant.EPOCH);
    var b = names(Instant.ofEpochSecond(100));
    assertEquals(a.artifactId(), b.artifactId());
    assertNotEquals(a.evidence(), b.evidence());
    assertNotEquals(a.artifactId(), definition("name-v2", "enUS", schemaSha()).artifactId());
    assertNotEquals(a.artifactId(), definition("name-v1", "frFR", schemaSha()).artifactId());
    assertNotEquals(a.artifactId(), definition("name-v1", "enUS", "e".repeat(64)).artifactId());
    assertEquals(
        a.artifactId(), new PreparedItemNames(item(Instant.EPOCH), a).names().artifactId());
  }

  @Test
  void displayHasStrictUtf8PositiveIdAndPrefixGeometryWithoutNormalization() {
    var empty = new DisplayCandidate("", 101, 0, 0, 5252, 5, 5);
    assertEquals("", empty.value());
    var unicode = new DisplayCandidate("A\u0301α", 101, 0, 0, 5252, 11, 10);
    assertEquals("A\u0301α", unicode.value());
    assertThrows(
        IllegalArgumentException.class, () -> new DisplayCandidate("α", 101, 0, 0, 5252, 6, 6));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DisplayCandidate("\ud800", 101, 0, 0, 5252, 20, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DisplayCandidate("a\0b", 101, 0, 0, 5252, 20, 20));
    assertThrows(
        IllegalArgumentException.class, () -> new DisplayCandidate("", 0, 0, 0, 5252, 5, 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DisplayCandidate("", 101, 0, 0, Long.MAX_VALUE, 5, 5));
  }

  @Test
  void physicalEmptyNameCopyAndScopedNonmatchRemainDistinct() {
    var physical =
        new ItemNameMatch(
            101,
            ItemNameMatch.Outcome.PHYSICAL_NAME,
            Optional.of(new DisplayCandidate("", 101, 0, 0, 5252, 5, 5)),
            OptionalLong.empty());
    var copy =
        new ItemNameMatch(
            303, ItemNameMatch.Outcome.COPY_UNINDEXED, Optional.empty(), OptionalLong.of(101));
    var missing =
        new ItemNameMatch(
            404, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, Optional.empty(), OptionalLong.empty());
    names(Instant.EPOCH).requireMatch(physical);
    names(Instant.EPOCH).requireMatch(copy);
    names(Instant.EPOCH).requireMatch(missing);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemNameMatch(
                101,
                ItemNameMatch.Outcome.COPY_UNINDEXED,
                physical.candidate(),
                OptionalLong.of(102)));
  }

  @Test
  void pairedCursorBindsBothArtifactsRolesAndLocale() {
    var pair = new PreparedItemNames(item(Instant.EPOCH), names(Instant.ofEpochSecond(100)));
    var cursor =
        new ItemNamesCursor(
            pair.item().artifactId(),
            pair.names().artifactId(),
            pair.snapshotId(),
            pair.item().sourceRole(),
            pair.names().sourceRole(),
            pair.locale(),
            ItemNamesCursor.QUERY_REVISION,
            ItemNamesCursor.OPTIONS_REVISION,
            101,
            50);
    cursor.requireContext(pair, 50);
    assertThrows(IllegalArgumentException.class, () -> cursor.requireContext(pair, 25));
    var other =
        new PreparedSparseNames(
            definition("name-v2", "enUS", schemaSha()),
            names(Instant.EPOCH).evidence(),
            names(Instant.EPOCH).coverage());
    assertThrows(
        IllegalArgumentException.class,
        () -> cursor.requireContext(new PreparedItemNames(pair.item(), other), 50));
    assertTrue(pair.names().evidence().fullDecoded().isEmpty());
  }

  @Test
  void independentRequiredNameWindowsCannotBeBorrowedFromItemProof() {
    var original = names(Instant.EPOCH);
    var e = original.evidence();
    for (int omit = 0; omit < 3; omit++) {
      var windows = new ArrayList<>(e.windows());
      windows.remove(omit);
      var partial =
          new ItemProjectionEvidence(
              e.association(), e.localVerification(), e.chunks(), windows, Optional.empty());
      assertThrows(
          IllegalArgumentException.class,
          () -> new PreparedSparseNames(original.definition(), partial, original.coverage()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreparedSparseNames(
                original.definition(), evidence(false, Instant.EPOCH), original.coverage()));
    var split =
        new ItemProjectionEvidence(
            e.association(),
            e.localVerification(),
            e.chunks(),
            List.of(
                new ItemProjectionEvidence.DecodedWindow(5302, 50),
                new ItemProjectionEvidence.DecodedWindow(0, 4040),
                new ItemProjectionEvidence.DecodedWindow(5252, 50),
                new ItemProjectionEvidence.DecodedWindow(5352, 36)),
            Optional.empty());
    assertDoesNotThrow(
        () -> new PreparedSparseNames(original.definition(), split, original.coverage()));
  }

  @Test
  void missingNamesDoNotRemoveItemRowsOrInventCopyValues() {
    var pair = new PreparedItemNames(item(Instant.EPOCH), names(Instant.EPOCH));
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var binding : pair.item().definition().interpretation().schema().fields())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              binding.field(),
              1,
              8,
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              1,
              binding.candidateBits(),
              binding.sign()));
    var row = new ItemPhysicalRow(101, 0, 0, values);
    var missing =
        new ItemNameMatch(
            101, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, Optional.empty(), OptionalLong.empty());
    var joined = new NamedItemRow(row, missing);
    pair.requireRow(joined);
    assertSame(row, joined.item());
    var lookup =
        new ItemLookupResult(
            101,
            ItemLookupResult.Outcome.PHYSICAL_MATCH,
            Optional.of(row),
            OptionalLong.empty(),
            pair.item().coverage());
    pair.requireLookup(new JoinedItemLookup(lookup, Optional.of(missing)));
    var copy =
        new ItemLookupResult(
            303,
            ItemLookupResult.Outcome.COPY_UNINDEXED,
            Optional.empty(),
            OptionalLong.of(101),
            pair.item().coverage());
    pair.requireLookup(new JoinedItemLookup(copy, Optional.empty()));
    assertThrows(
        IllegalArgumentException.class, () -> new JoinedItemLookup(copy, Optional.of(missing)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NamedItemRow(
                row,
                new ItemNameMatch(
                    102,
                    ItemNameMatch.Outcome.NO_PHYSICAL_MATCH,
                    Optional.empty(),
                    OptionalLong.empty())));
  }

  @Test
  void nameCandidatesCannotEscapeIndependentCoverage() {
    var n = names(Instant.EPOCH);
    for (var candidate :
        List.of(
            new DisplayCandidate("", 101, 2, 0, 5252, 5, 5),
            new DisplayCandidate("", 101, 0, 1, 5252, 5, 5),
            new DisplayCandidate("", 101, 0, 0, 5251, 5, 5),
            new DisplayCandidate("", 101, 0, 0, 5350, 5, 5)))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              n.requireMatch(
                  new ItemNameMatch(
                      101,
                      ItemNameMatch.Outcome.PHYSICAL_NAME,
                      Optional.of(candidate),
                      OptionalLong.empty())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PreparedSparseNames(
                n.definition(), n.evidence(), new PreparedSparseNames.Coverage(0, 100, 1, 48, 8)));
  }

  @Test
  void onePhysicalSourceAndPinnedPrefixAreImmutable() {
    var d = definition("name-v1", "enUS", schemaSha());
    var sources = new ArrayList<>(d.sources());
    var original = new SparseNameArtifactDefinition(sources, d.interpretation());
    sources.clear();
    assertEquals(1, original.sources().size());
    assertThrows(UnsupportedOperationException.class, () -> original.sources().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SparseNameArtifactDefinition(
                List.of(d.sources().getFirst(), d.sources().getFirst()), d.interpretation()));
    var s = d.interpretation().schema();
    var fields = new ArrayList<>(s.prefixFields());
    Collections.swap(fields, 3, 4);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SparseNameArtifactDefinition.Schema(
                s.provider(),
                s.revision(),
                s.definitionPath(),
                s.definitionSha256(),
                s.selectionDigest(),
                s.license(),
                s.attribution(),
                fields));
  }

  @Test
  void pairedLocaleAndSourceRoleCannotBeSubstituted() {
    var item = item(Instant.EPOCH);
    var i = item.definition().interpretation();
    var otherLocale =
        new ItemArtifactDefinition.Interpretation(
            i.decoderRevision(),
            i.indexRevision(),
            i.tableHash(),
            i.layoutHash(),
            i.sectionOrdinal(),
            i.sectionDecodedOffset(),
            i.sectionDecodedBytes(),
            i.schema(),
            "frFR",
            i.optionsRevision(),
            i.proofSemantics());
    var changed =
        new PreparedItemProjection(
            new ItemArtifactDefinition(item.definition().sources(), otherLocale),
            item.evidence(),
            item.coverage());
    assertThrows(
        IllegalArgumentException.class, () -> new PreparedItemNames(changed, names(Instant.EPOCH)));
    var pair = new PreparedItemNames(item, names(Instant.EPOCH));
    var swapped =
        new ItemNamesCursor(
            item.artifactId(),
            pair.names().artifactId(),
            pair.snapshotId(),
            pair.names().sourceRole(),
            item.sourceRole(),
            "enUS",
            ItemNamesCursor.QUERY_REVISION,
            ItemNamesCursor.OPTIONS_REVISION,
            0xffffffffL,
            50);
    assertThrows(IllegalArgumentException.class, () -> swapped.requireContext(pair, 50));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemNamesCursor(
                item.artifactId(),
                pair.names().artifactId(),
                pair.snapshotId(),
                item.sourceRole(),
                pair.names().sourceRole(),
                "enUS",
                ItemNamesCursor.QUERY_REVISION,
                "other-options",
                1,
                50));
  }

  @Test
  void anotherSnapshotCannotBorrowEitherContextDespiteMatchingVersionLabel() {
    var original = names(Instant.EPOCH);
    var e = original.evidence();
    var a = e.association();
    var v = a.descriptor().association();
    var b = v.build();
    var otherBuild =
        new BuildSnapshot(
            b.productId(),
            b.editionLabel(),
            b.channel(),
            b.region(),
            b.versionLabel(),
            "7".repeat(32),
            b.cdnConfig(),
            b.observedAt(),
            b.fixture(),
            b.evidence());
    var otherVariant =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            otherBuild,
            v.rootSource(),
            v.fileDataId(),
            v.contentKey(),
            v.localeFlags(),
            v.contentFlags(),
            v.rawFlagsFirst(),
            v.rawFlagsSecond(),
            v.rawFlagsThird(),
            v.nameHash());
    var descriptor =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            otherVariant,
            a.descriptor().actualEncodingKey(),
            a.descriptor().encodedVerifierSemantics());
    var association =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            descriptor, a.configuration(), a.root(), a.proof(), a.representation());
    var evidence =
        new ItemProjectionEvidence(
            association, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded());
    var other = new PreparedSparseNames(original.definition(), evidence, original.coverage());
    assertEquals(original.artifactId(), other.artifactId());
    assertThrows(
        IllegalArgumentException.class, () -> new PreparedItemNames(item(Instant.EPOCH), other));
  }

  @Test
  void zeroBoundaryOverflowAndWrongLookupShapesAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DisplayCandidate("", 0x100000000L, 0, 0, 0, 5, 5));
    assertThrows(
        IllegalArgumentException.class, () -> new DisplayCandidate("", 1, 0, 0, 0, 65536, 5));
    assertThrows(IllegalArgumentException.class, () -> new DisplayCandidate("", 1, 0, 0, 0, 5, 4));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ItemNameMatch(
                1, ItemNameMatch.Outcome.COPY_UNINDEXED, Optional.empty(), OptionalLong.of(0)));
    var scoped =
        new ItemLookupResult(
            0,
            ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
            Optional.empty(),
            OptionalLong.empty(),
            item(Instant.EPOCH).coverage());
    assertThrows(
        IllegalArgumentException.class, () -> new JoinedItemLookup(scoped, Optional.empty()));
    var d = definition("names-v1", "enUS", schemaSha());
    var i = d.interpretation();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SparseNameArtifactDefinition.Interpretation(
                i.decoderRevision(),
                i.indexRevision(),
                i.tableHash(),
                i.layoutHash(),
                0,
                1,
                Long.MAX_VALUE,
                1,
                0,
                1,
                i.schema(),
                i.locale(),
                i.optionsRevision(),
                i.proofSemantics()));
  }
}
