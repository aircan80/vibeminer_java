package io.vibeminer.application.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ContentContractsTest {
  static final String C = "a".repeat(32), E = "b".repeat(32);

  static BuildSnapshot build(String product) {
    return new BuildSnapshot(
        product,
        "Fixture",
        "current",
        "us",
        "1",
        "c".repeat(32),
        "d".repeat(32),
        Instant.EPOCH,
        true);
  }

  static VerifiedSourceAlias configuration() {
    var source =
        new ResolvedSourceIdentity("tact.config.raw-md5", "c".repeat(32), "tact-config-raw-md5-v1");
    return alias(source, 10);
  }

  static VerifiedSourceAlias alias(ResolvedSourceIdentity source, long bytes) {
    return new VerifiedSourceAlias(
        source,
        new StoredObject(new ObjectDigest("e".repeat(64)), bytes),
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/fixture", Instant.EPOCH));
  }

  static MetadataContentProof historical() {
    return new MetadataContentProof(
        alias(
            new ResolvedSourceIdentity("wow.root.tsfm.v1v2", C + ";" + E + ";100;80", "root.v1"),
            80),
        E,
        C,
        OptionalLong.of(100),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void requestBindsExactReviewedBuildConfigurationAndCost() {
    var request =
        new MetadataContentRequest(
            build("wow"),
            configuration(),
            MetadataStructureAttestation.Kind.ROOT,
            new ContentSlotKey("wow", C),
            E,
            80,
            OptionalLong.of(100));
    assertEquals(E, request.requestedEncodingKey());
    assertEquals(80, request.requestedEncodedBytes());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentRequest(
                build("wowf"),
                configuration(),
                MetadataStructureAttestation.Kind.ROOT,
                new ContentSlotKey("wow", C),
                E,
                80,
                OptionalLong.of(100)));
    var wrong =
        alias(
            new ResolvedSourceIdentity(
                "tact.config.raw-md5", "f".repeat(32), "tact-config-raw-md5-v1"),
            10);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentRequest(
                build("wow"),
                wrong,
                MetadataStructureAttestation.Kind.ROOT,
                new ContentSlotKey("wow", C),
                E,
                80,
                OptionalLong.of(100)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataContentRequest(
                build("wow"),
                configuration(),
                MetadataStructureAttestation.Kind.ROOT,
                new ContentSlotKey("wow", C),
                E,
                0,
                OptionalLong.empty()));
  }

  @Test
  void publicationRequiresBothDecodedAndMatchingStructuralCapability() {
    var historical = historical();
    assertEquals(
        ContentClaimException.Code.INVALID_STATE,
        assertThrows(
                ContentClaimException.class,
                () -> ContentProofPolicy.requirePublicationReady(historical))
            .code());
    var decoded = new DecodedContentAttestation(C, 100, "md5.v1");
    var protocol = historical.withAttestations(Optional.of(decoded), Optional.empty());
    assertThrows(
        ContentClaimException.class, () -> ContentProofPolicy.requirePublicationReady(protocol));
    var complete =
        protocol.withAttestations(
            Optional.of(decoded),
            Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "tsfm.v2")));
    assertDoesNotThrow(() -> ContentProofPolicy.requirePublicationReady(complete));
  }

  @Test
  void proofUpgradePreservesEveryOriginalFactAndNeverDropsCapabilities() {
    var original = historical();
    var decoded = new DecodedContentAttestation(C, 100, "md5.v1");
    var upgraded = original.withAttestations(Optional.of(decoded), Optional.empty());
    assertDoesNotThrow(() -> ContentProofPolicy.requireMonotonicUpgrade(original, upgraded));
    assertThrows(
        ContentClaimException.class,
        () -> ContentProofPolicy.requireMonotonicUpgrade(upgraded, original));
    var differentOrigin =
        new MetadataContentProof(
            new VerifiedSourceAlias(
                original.originalAlias().source(),
                original.object(),
                new SourceVerificationEvidence(
                    original.originalAlias().source().verificationSemantics(),
                    "https://level3.ssl.blizzard.com/different",
                    Instant.EPOCH)),
            E,
            C,
            OptionalLong.of(100),
            Optional.of(decoded),
            Optional.empty());
    assertThrows(
        ContentClaimException.class,
        () -> ContentProofPolicy.requireMonotonicUpgrade(original, differentOrigin));
  }

  @Test
  void encodedPublicationStaysTypedAndCannotReplaceHistoricalMetadataProof() {
    var source = ContentRepresentation.encodedIdentity(E);
    var value = alias(source, 80);
    var observation =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            value.evidence().sourceUrl(),
            E,
            80,
            0,
            80,
            Instant.EPOCH,
            1,
            80,
            "f".repeat(64),
            Optional.empty());
    var encoded =
        new EncodedContentProof(
            new ContentRepresentation(
                new ContentSlotKey("wow", C),
                value,
                EncodedScope.ZERO_HEADER_WHOLE_ENCODED,
                observation));
    assertDoesNotThrow(() -> ContentProofPolicy.requirePublicationReady(encoded));
    assertDoesNotThrow(() -> ContentProofPolicy.requireMonotonicUpgrade(encoded, encoded));
    assertThrows(
        ContentClaimException.class,
        () -> ContentProofPolicy.requireMonotonicUpgrade(encoded, historical()));
    assertThrows(
        ContentClaimException.class,
        () -> ContentProofPolicy.requireMonotonicUpgrade(historical(), encoded));
  }
}
