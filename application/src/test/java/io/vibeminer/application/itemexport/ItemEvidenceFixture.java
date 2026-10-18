package io.vibeminer.application.itemexport;

import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import java.time.Instant;
import java.util.*;

final class ItemEvidenceFixture {
  static ItemArtifactDefinition.Schema baseItemSchema(String sha) {
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

  static ItemArtifactDefinition baseItemDefinition(
      String decoder, String locale, String schemaSha) {
    var interpretation =
        new ItemArtifactDefinition.Interpretation(
            decoder,
            "index-v1",
            0x50238ec2L,
            0x996192aaL,
            0,
            7152,
            2129287,
            baseItemSchema(schemaSha),
            locale,
            "physical-only-v1",
            "blte-window-v1");
    return new ItemArtifactDefinition(
        List.of(new StoredObject(new ObjectDigest("a".repeat(64)), 738344)), interpretation);
  }

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
    var association = association(sparse, time);
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
    var base = baseItemDefinition("item-v1", "enUS", "c".repeat(64));
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
}
