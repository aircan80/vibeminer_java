package io.vibeminer.desktop;

import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import java.util.*;

final class ItemPresentationFixture {
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
            build,
            rootSource,
            841626,
            "1df6b6dbae36878cea020a62b15b9bd6",
            1,
            2,
            3,
            4,
            5,
            Optional.empty());
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

  static PreparedItemProjection handle() {
    return new PreparedItemProjection(
        definition("test-decoder", "enUS", "c".repeat(64)),
        evidence("12.1.5.70448", java.time.Instant.EPOCH),
        new PreparedItemProjection.Coverage(0, 2, 1, 1));
  }
}
