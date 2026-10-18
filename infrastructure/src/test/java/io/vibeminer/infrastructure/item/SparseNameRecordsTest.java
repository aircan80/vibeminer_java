package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.itemnames.ItemNamesException;
import java.util.*;
import org.junit.jupiter.api.Test;

class SparseNameRecordsTest {
  @Test
  void exactSharedDefinitionAndCandidateRoundTrip() {
    var definition = SparseNameStorageFixture.definition();
    var restored = SparseNameRecords.definition(SparseNameRecords.definition(definition));
    assertEquals(definition, restored);
    assertEquals(definition.artifactId(), restored.artifactId());
    var row = SparseNameStorageFixture.candidate();
    assertEquals(row, SparseNameRecords.candidate(SparseNameRecords.candidate(row)));
    var copy =
        new io.vibeminer.infrastructure.itemnames.SparseNameProjectionSourceReader.CopyDeclaration(
            303, 101);
    assertEquals(copy, SparseNameRecords.copy(SparseNameRecords.copy(copy)));
  }

  @Test
  void malformedUtf8LengthsVersionsAndTrailingBytesFailTyped() {
    var row = SparseNameRecords.candidate(SparseNameStorageFixture.candidate());
    byte[] malformed = row.clone();
    malformed[malformed.length - 1] = (byte) 0xff;
    assertEquals(
        ItemNamesException.Code.CORRUPT,
        assertThrows(ItemNamesException.class, () -> SparseNameRecords.candidate(malformed))
            .code());
    assertThrows(
        ItemNamesException.class,
        () -> SparseNameRecords.candidate(Arrays.copyOf(row, row.length + 1)));
    byte[] bad = row.clone();
    bad[0] = 0;
    assertThrows(ItemNamesException.class, () -> SparseNameRecords.candidate(bad));
    for (int n = 0; n < row.length; n++) {
      var truncated = Arrays.copyOf(row, n);
      assertThrows(ItemNamesException.class, () -> SparseNameRecords.candidate(truncated));
    }
  }

  @Test
  void contextualProofPreservesIndependentOriginalHistory() {
    var value = SparseNameStorageFixture.prepared();
    var bytes = SparseNameRecords.evidence(value.evidence());
    assertEquals(
        value.evidence(),
        SparseNameRecords.evidence(
            bytes, value.evidence().association().descriptor().association().build()));
  }

  @Test
  void fullChunkManifestUsesTwoMiBProofBudgetAndRejectsLossyNestedText() {
    var e = SparseNameStorageFixture.prepared().evidence();
    var chunks = new ArrayList<io.vibeminer.domain.item.ItemProjectionEvidence.ChunkAttestation>();
    for (int n = 0; n < 4096; n++)
      chunks.add(
          new io.vibeminer.domain.item.ItemProjectionEvidence.ChunkAttestation(
              n, 36 + n, 1, "6".repeat(32), n, 1, "chunk-v1"));
    var large =
        new io.vibeminer.domain.item.ItemProjectionEvidence(
            e.association(), e.localVerification(), chunks, e.windows(), Optional.empty());
    var encoded = SparseNameRecords.evidence(large);
    assertTrue(encoded.length > 262144);
    assertTrue(encoded.length <= 2097152);
    assertEquals(
        large,
        SparseNameRecords.evidence(encoded, e.association().descriptor().association().build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new io.vibeminer.domain.item.ItemProjectionEvidence.LocalVerification(
                e.localVerification().object(), e.localVerification().verifiedAt(), "bad\ud800"));
    // Nested source receipts have a separate codec boundary and must not be rewritten either.
    var association = e.association();
    var config = association.configuration();
    var lossyConfig =
        new io.vibeminer.domain.VerifiedSourceAlias(
            config.source(),
            config.object(),
            new io.vibeminer.domain.SourceVerificationEvidence(
                config.evidence().verifierSemantics(),
                "https://example.invalid/bad\ud800",
                config.evidence().verifiedAt()));
    var lossyAssociation =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            association.descriptor(),
            lossyConfig,
            association.root(),
            association.proof(),
            association.representation());
    var lossy =
        new io.vibeminer.domain.item.ItemProjectionEvidence(
            lossyAssociation, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded());
    assertEquals(
        ItemNamesException.Code.CORRUPT,
        assertThrows(ItemNamesException.class, () -> SparseNameRecords.evidence(lossy)).code());
  }
}
