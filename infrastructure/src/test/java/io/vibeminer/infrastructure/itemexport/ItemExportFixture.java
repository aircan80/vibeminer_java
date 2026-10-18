package io.vibeminer.infrastructure.itemexport;

import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import java.util.*;

final class ItemExportFixture {
  static io.vibeminer.domain.selectedfile.SelectedFileAssociation association(
      boolean sparse, java.time.Instant observed) {
    var build =
        new BuildSnapshot(
            "wow",
            "Fixture",
            "current",
            "us",
            "12.1.5.70448",
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
            build,
            rootSource,
            sparse ? 1572924 : 841626,
            sparse ? "bb0ecbdc2fd4988ddd3c1c5e95c34165" : "1df6b6dbae36878cea020a62b15b9bd6",
            sparse ? 0x202 : 1,
            2,
            3,
            4,
            5,
            Optional.empty());
    var descriptor =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            variant,
            (sparse ? "9" : "f").repeat(32),
            io.vibeminer.domain.selectedfile.ContentRepresentation.VERIFIER_SEMANTICS);
    var source =
        io.vibeminer.domain.selectedfile.ContentRepresentation.encodedIdentity(
            (sparse ? "9" : "f").repeat(32));
    var evidence =
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/encoded", observed);
    var encoded =
        new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest((sparse ? "b" : "a").repeat(64)), 738344),
            evidence);
    var receipt =
        new io.vibeminer.domain.selectedfile.EncodedSourceReceipt(
            io.vibeminer.domain.selectedfile.EncodedSourceReceipt.Kind.LOOSE,
            evidence.sourceUrl(),
            (sparse ? "9" : "f").repeat(32),
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
                    (sparse ? "9" : "f").repeat(32),
                    java.util.OptionalLong.of(738344),
                    java.util.OptionalLong.empty())));
    return new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
        descriptor, config, root, proof, representation);
  }

  static SparseNameArtifactDefinition definition() {
    var schema =
        new SparseNameArtifactDefinition.Schema(
            "WoWDBDefs",
            "b9a63096cdd99b26967f17f9515377ae62cadfd7",
            "definitions/ItemSparse.dbd",
            "8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a",
            "5".repeat(64),
            "CC BY-SA 4.0",
            "WoWDBDefs contributors",
            SparseNameArtifactDefinition.PREFIX_FIELDS);
    return new SparseNameArtifactDefinition(
        List.of(association(true, java.time.Instant.EPOCH).representation().encoded().object()),
        new SparseNameArtifactDefinition.Interpretation(
            "decoder-v1",
            "index-v1",
            0x919be54eL,
            0x1c17d17fL,
            0,
            100,
            100,
            1000,
            1100,
            100,
            schema,
            "enUS",
            "physical-only",
            "encoded-window-v1"));
  }

  static PreparedSparseNames prepared() {
    var a = association(true, java.time.Instant.EPOCH);
    var evidence =
        new ItemProjectionEvidence(
            a,
            new ItemProjectionEvidence.LocalVerification(
                a.representation().encoded().object(), java.time.Instant.EPOCH, "sha256-v1"),
            List.of(
                new ItemProjectionEvidence.ChunkAttestation(
                    0, 36, 738308, "6".repeat(32), 0, 3000000, "chunk-v1")),
            List.of(new ItemProjectionEvidence.DecodedWindow(0, 1200)),
            Optional.empty());
    return new PreparedSparseNames(
        definition(), evidence, new PreparedSparseNames.Coverage(0, 2, 1, 48, 3));
  }

  static PreparedItemProjection item() {
    var a = association(false, java.time.Instant.EPOCH);
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
            "7".repeat(64),
            "CC BY-SA 4.0",
            "WoWDBDefs contributors",
            fields);
    var definition =
        new ItemArtifactDefinition(
            List.of(a.representation().encoded().object()),
            new ItemArtifactDefinition.Interpretation(
                "decoder-v1",
                "index-v1",
                0x50238ec2L,
                0x996192aaL,
                0,
                0,
                100,
                schema,
                "enUS",
                "physical-only",
                "encoded-window-v1"));
    var evidence =
        new ItemProjectionEvidence(
            a,
            new ItemProjectionEvidence.LocalVerification(
                a.representation().encoded().object(), java.time.Instant.EPOCH, "sha256-v1"),
            List.of(
                new ItemProjectionEvidence.ChunkAttestation(
                    0, 36, 738308, "6".repeat(32), 0, 3000000, "chunk-v1")),
            List.of(new ItemProjectionEvidence.DecodedWindow(0, 100)),
            Optional.empty());
    return new PreparedItemProjection(
        definition, evidence, new PreparedItemProjection.Coverage(0, 2, 1, 48));
  }

  static io.vibeminer.application.itemexport.ItemEvidenceSnapshot snapshot() {
    var item = item();
    var row =
        new ItemPhysicalRow(
            101,
            0,
            0,
            List.of(
                new ItemPhysicalRow.IntegerCandidate(
                    ItemArtifactDefinition.Field.CLASS_ID,
                    0xffffffffL,
                    32,
                    ItemPhysicalRow.PhysicalInterpretation.SIGNED_TWOS_COMPLEMENT,
                    -1,
                    32,
                    ItemArtifactDefinition.Sign.SIGNED),
                new ItemPhysicalRow.IntegerCandidate(
                    ItemArtifactDefinition.Field.SUBCLASS_ID,
                    255,
                    8,
                    ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
                    255,
                    8,
                    ItemArtifactDefinition.Sign.UNSIGNED),
                new ItemPhysicalRow.IntegerCandidate(
                    ItemArtifactDefinition.Field.INVENTORY_TYPE,
                    128,
                    8,
                    ItemPhysicalRow.PhysicalInterpretation.SIGNED_TWOS_COMPLEMENT,
                    -128,
                    8,
                    ItemArtifactDefinition.Sign.SIGNED),
                new ItemPhysicalRow.IntegerCandidate(
                    ItemArtifactDefinition.Field.ICON_FILE_DATA_ID,
                    1,
                    32,
                    ItemPhysicalRow.PhysicalInterpretation.SIGNED_TWOS_COMPLEMENT,
                    1,
                    32,
                    ItemArtifactDefinition.Sign.SIGNED)));
    return new io.vibeminer.application.itemexport.ItemEvidenceSnapshot(
        new io.vibeminer.application.itemexport.ItemEvidenceSelection(item, 101, Optional.empty()),
        row,
        Optional.empty(),
        java.time.Instant.EPOCH.plusSeconds(1),
        java.time.Instant.EPOCH.plusSeconds(2),
        "8".repeat(64));
  }

  static io.vibeminer.application.itemexport.ItemEvidenceSnapshot paired(
      ItemNameMatch match, int chunks, String revision) {
    var base = snapshot();
    var sparse = prepared();
    var evidence = sparse.evidence();
    var a = evidence.association();
    var p = a.proof();
    var changedAssociation =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            a.descriptor(),
            a.configuration(),
            a.root(),
            new io.vibeminer.domain.selectedfile.FileAssociationProof(
                p.rootSource(),
                p.encodingBootstrapSource(),
                p.verifierRevision(),
                p.proofDigest(),
                OptionalLong.of(4000000),
                p.authenticatedCandidates()),
            a.representation());
    var receipts = new ArrayList<ItemProjectionEvidence.ChunkAttestation>();
    for (int i = 0; i < chunks; i++)
      receipts.add(
          new ItemProjectionEvidence.ChunkAttestation(
              i, 36 + i * 100L, 100, "6".repeat(32), i * 1000L, 1000, revision));
    sparse =
        new PreparedSparseNames(
            sparse.definition(),
            new ItemProjectionEvidence(
                changedAssociation,
                evidence.localVerification(),
                receipts,
                evidence.windows(),
                Optional.empty()),
            sparse.coverage());
    return new io.vibeminer.application.itemexport.ItemEvidenceSnapshot(
        new io.vibeminer.application.itemexport.ItemEvidenceSelection(
            base.selection().item(),
            101,
            Optional.of(new PreparedItemNames(base.selection().item(), sparse))),
        base.row(),
        Optional.of(match),
        base.verificationStartedAt(),
        base.verificationCompletedAt(),
        base.generationFingerprint());
  }

  static io.vibeminer.application.itemexport.ItemEvidenceSnapshot edition(
      String text, List<SourceDocument> documents) {
    var base = snapshot();
    var item = base.selection().item();
    var e = item.evidence();
    var a = e.association();
    var d = a.descriptor();
    var v = d.association();
    var b = v.build();
    var build =
        new BuildSnapshot(
            b.productId(),
            text,
            b.channel(),
            b.region(),
            b.versionLabel(),
            b.buildConfig(),
            b.cdnConfig(),
            b.observedAt(),
            b.fixture(),
            new BuildEvidence(
                b.evidence().upstreamBuildId(), b.evidence().productConfig(), documents));
    var variant =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            build,
            v.rootSource(),
            v.fileDataId(),
            v.contentKey(),
            v.localeFlags(),
            v.contentFlags(),
            v.rawFlagsFirst(),
            v.rawFlagsSecond(),
            v.rawFlagsThird(),
            v.nameHash());
    var association =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
                variant, d.actualEncodingKey(), d.encodedVerifierSemantics()),
            a.configuration(),
            a.root(),
            a.proof(),
            a.representation());
    var changed =
        new PreparedItemProjection(
            item.definition(),
            new ItemProjectionEvidence(
                association, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded()),
            item.coverage());
    return new io.vibeminer.application.itemexport.ItemEvidenceSnapshot(
        new io.vibeminer.application.itemexport.ItemEvidenceSelection(
            changed, 101, Optional.empty()),
        base.row(),
        Optional.empty(),
        base.verificationStartedAt(),
        base.verificationCompletedAt(),
        base.generationFingerprint());
  }

  static io.vibeminer.application.itemexport.ItemEvidenceSnapshot integer(
      int width, long bits, ItemArtifactDefinition.Sign sign, long candidate) {
    var base = snapshot();
    var item = base.selection().item();
    var i = item.definition().interpretation();
    var s = i.schema();
    var fields = new ArrayList<>(s.fields());
    fields.set(
        0,
        new ItemArtifactDefinition.FieldBinding(
            ItemArtifactDefinition.Field.CLASS_ID, 0, width, sign));
    var schema =
        new ItemArtifactDefinition.Schema(
            s.provider(),
            s.revision(),
            s.definitionPath(),
            s.definitionSha256(),
            s.selectionDigest(),
            s.license(),
            s.attribution(),
            fields);
    var definition =
        new ItemArtifactDefinition(
            item.definition().sources(),
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
    var changed = new PreparedItemProjection(definition, item.evidence(), item.coverage());
    var values = new ArrayList<>(base.row().values());
    values.set(
        0,
        new ItemPhysicalRow.IntegerCandidate(
            ItemArtifactDefinition.Field.CLASS_ID,
            bits,
            width,
            ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
            candidate,
            width,
            sign));
    return new io.vibeminer.application.itemexport.ItemEvidenceSnapshot(
        new io.vibeminer.application.itemexport.ItemEvidenceSelection(
            changed, 101, Optional.empty()),
        new ItemPhysicalRow(101, 0, 0, values),
        Optional.empty(),
        base.verificationStartedAt(),
        base.verificationCompletedAt(),
        base.generationFingerprint());
  }

  static DisplayCandidate candidate() {
    return new DisplayCandidate("αZ", 101, 0, 0, 100, 20, 8);
  }
}
