package io.vibeminer.infrastructure.item;

import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import java.util.*;

final class SparseNameStorageFixture {
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

  static DisplayCandidate candidate() {
    return new DisplayCandidate("αZ", 101, 0, 0, 100, 20, 8);
  }
}
