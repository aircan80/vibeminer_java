package io.vibeminer.infrastructure.selectedfile;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.selectedfile.SelectedFileException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectedFileRecordsTest {
  @Test
  void archiveAdvertisedAndAuthenticatedGeometryRoundTripWithoutInventingAgreement()
      throws Exception {
    var mapping =
        new ArchiveMappingProof(
            "f".repeat(32),
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "archive-page-v1",
            OptionalLong.of(12388),
            4148);
    var proof = archived(mapping);
    assertEquals(
        proof, SelectedFileRecords.representation(SelectedFileRecords.representation(proof)));
    var decoded = SelectedFileRecords.representation(SelectedFileRecords.representation(proof));
    assertEquals(
        OptionalLong.of(12388),
        decoded.sourceReceipt().archiveMapping().orElseThrow().advertisedIndexBytes());
    assertEquals(
        4148, decoded.sourceReceipt().archiveMapping().orElseThrow().authenticatedIndexBytes());
  }

  @Test
  void oldMetadataVersionPreservesUnknownArchiveGeometry() throws Exception {
    var unknown =
        new ArchiveMappingProof(
            "f".repeat(32), "1".repeat(64), "2".repeat(64), "3".repeat(64), "archive-page-v1");
    var proof = archived(unknown);
    byte[] modern = SelectedFileRecords.representation(proof);
    byte[] legacy = Arrays.copyOf(modern, modern.length - 9);
    legacy[8] = '1'; // VMER2 -> VMER1; remove absent-hint marker and authenticated-size sentinel.
    var restored =
        SelectedFileRecords.representation(legacy).sourceReceipt().archiveMapping().orElseThrow();
    assertFalse(restored.hasAuthenticatedIndexGeometry());
    assertTrue(restored.advertisedIndexBytes().isEmpty());
  }

  @Test
  void malformedUtf8AndTrailingOrOversizedMetadataFailIntegrity() throws Exception {
    var proof =
        SqliteSelectedFileCatalogTest.prospective(
            SqliteSelectedFileCatalogTest.slot(),
            SqliteSelectedFileCatalogTest.A,
            "fixture".getBytes(),
            "us.cdn.blizzard.com");
    byte[] bytes = SelectedFileRecords.representation(proof);
    byte[] target = "/fixture/".getBytes(StandardCharsets.UTF_8);
    int at = -1;
    for (int i = 0; i <= bytes.length - target.length; i++) {
      boolean match = true;
      for (int j = 0; j < target.length; j++) if (bytes[i + j] != target[j]) match = false;
      if (match) {
        at = i;
        break;
      }
    }
    assertTrue(at >= 0);
    bytes[at + 1] = (byte) 0xff;
    assertEquals(
        SelectedFileException.Code.INTEGRITY_FAILURE,
        assertThrows(SelectedFileException.class, () -> SelectedFileRecords.representation(bytes))
            .code());
    byte[] trailing =
        Arrays.copyOf(
            SelectedFileRecords.representation(proof),
            SelectedFileRecords.representation(proof).length + 1);
    assertThrows(SelectedFileException.class, () -> SelectedFileRecords.representation(trailing));
    assertThrows(
        SelectedFileException.class, () -> SelectedFileRecords.representation(new byte[65537]));
  }

  private static ContentRepresentation archived(ArchiveMappingProof mapping) throws Exception {
    var base =
        SqliteSelectedFileCatalogTest.prospective(
            SqliteSelectedFileCatalogTest.slot(),
            SqliteSelectedFileCatalogTest.A,
            "fixture".getBytes(),
            "us.cdn.blizzard.com");
    String url = "https://us.cdn.blizzard.com/fixture/archive";
    var encoded =
        new VerifiedSourceAlias(
            base.encoded().source(),
            base.encoded().object(),
            new SourceVerificationEvidence(
                base.encoded().source().verificationSemantics(), url, Instant.EPOCH));
    return new ContentRepresentation(
        base.slot(),
        encoded,
        base.scope(),
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.ARCHIVE,
            url,
            mapping.indexIdentity(),
            100,
            9,
            7,
            Instant.EPOCH,
            1,
            7,
            "4".repeat(64),
            Optional.of(mapping)));
  }

  @Test
  void legacyAssociationMetadataHasUnknownCandidateFactsWithoutInventingProof() throws Exception {
    var build = SqliteSelectedFileCatalogTest.build("legacy");
    var source = new ResolvedSourceIdentity("fixture", "root", "fixture-v1");
    var alias =
        new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest("a".repeat(64)), 4),
            new SourceVerificationEvidence(
                source.verificationSemantics(), "https://example.invalid/root", Instant.EPOCH));
    var representation =
        SqliteSelectedFileCatalogTest.prospective(
            SqliteSelectedFileCatalogTest.slot(),
            SqliteSelectedFileCatalogTest.A,
            "fixture".getBytes(),
            "us.cdn.blizzard.com");
    var current = SqliteSelectedFileCatalogTest.association(build, alias, alias, representation);
    var proof = current.proof();
    var old =
        new SelectedFileAssociation(
            current.descriptor(),
            alias,
            alias,
            new FileAssociationProof(
                proof.rootSource(),
                proof.encodingBootstrapSource(),
                proof.verifierRevision(),
                proof.proofDigest()),
            representation);
    byte[] modern = SelectedFileRecords.association(old);
    byte[] legacy = Arrays.copyOf(modern, modern.length - 5);
    legacy[8] = '1';
    var restored = SelectedFileRecords.association(legacy, build, representation);
    assertTrue(restored.proof().declaredDecodedBytes().isEmpty());
    assertTrue(restored.proof().authenticatedCandidates().isEmpty());
  }
}
