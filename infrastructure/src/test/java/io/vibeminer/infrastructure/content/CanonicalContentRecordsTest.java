package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalContentRecordsTest {
  static final String C = "c".repeat(32), E = "e".repeat(32);

  static MetadataContentProof historical() {
    var identity =
        new ResolvedSourceIdentity("wow.root.tsfm.v1v2", C + ";" + E + ";64;80", "fixture-root-v1");
    var alias =
        new VerifiedSourceAlias(
            identity,
            new StoredObject(new ObjectDigest("a".repeat(64)), 80),
            new SourceVerificationEvidence(
                identity.verificationSemantics(),
                "https://us.cdn.blizzard.com/fixture",
                Instant.EPOCH));
    return new MetadataContentProof(
        alias, E, C, OptionalLong.of(64), Optional.empty(), Optional.empty());
  }

  @Test
  void historicalMetadataRoundTripDoesNotEarnCapabilities() {
    var value = historical();
    assertEquals(value, CanonicalContentRecords.decode(CanonicalContentRecords.encode(value)));
    var read =
        (MetadataContentProof)
            CanonicalContentRecords.decode(CanonicalContentRecords.encode(value));
    assertTrue(read.decodedAttestation().isEmpty());
    assertTrue(read.structuralAttestation().isEmpty());
    assertEquals(value.originalAlias(), read.originalAlias());
  }

  @Test
  void decodedAndGrammarAttestationsRemainSeparate() {
    var decoded =
        historical()
            .withAttestations(
                Optional.of(new DecodedContentAttestation(C, 64, "decoded-v1")), Optional.empty());
    assertEquals(decoded, CanonicalContentRecords.decode(CanonicalContentRecords.encode(decoded)));
    var complete =
        decoded.withAttestations(
            decoded.decodedAttestation(),
            Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "root-v1")));
    assertEquals(
        complete, CanonicalContentRecords.decode(CanonicalContentRecords.encode(complete)));
  }

  @Test
  void malformedTruncatedTrailingAndOversizedRecordsFailTyped() {
    var encoded = CanonicalContentRecords.encode(historical());
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalContentRecords.decode(Arrays.copyOf(encoded, encoded.length - 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanonicalContentRecords.decode(Arrays.copyOf(encoded, encoded.length + 1)));
    assertThrows(
        IllegalArgumentException.class, () -> CanonicalContentRecords.decode(new byte[65537]));
    var corrupt = encoded.clone();
    corrupt[4] = (byte) 0xff;
    assertThrows(IllegalArgumentException.class, () -> CanonicalContentRecords.decode(corrupt));
  }

  @Test
  void malformedRevisionCannotBeSilentlyReplacedDuringEncoding() {
    for (var revision : List.of("parser-\uD800", "parser-\uDC00")) {
      var decoded =
          historical()
              .withAttestations(
                  Optional.of(new DecodedContentAttestation(C, 64, revision)), Optional.empty());
      assertThrows(IllegalArgumentException.class, () -> CanonicalContentRecords.encode(decoded));
      var grammar =
          historical()
              .withAttestations(
                  Optional.of(new DecodedContentAttestation(C, 64, "decoded-v1")),
                  Optional.of(
                      new MetadataStructureAttestation(
                          MetadataStructureAttestation.Kind.ROOT, revision)));
      assertThrows(IllegalArgumentException.class, () -> CanonicalContentRecords.encode(grammar));
    }
    var valid =
        historical()
            .withAttestations(
                Optional.of(new DecodedContentAttestation(C, 64, "decoded-\uD83D\uDE00")),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.ROOT, "parser-\uD83D\uDE00")));
    assertEquals(valid, CanonicalContentRecords.decode(CanonicalContentRecords.encode(valid)));
  }
}
