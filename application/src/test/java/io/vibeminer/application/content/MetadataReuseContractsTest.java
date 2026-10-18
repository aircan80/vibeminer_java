package io.vibeminer.application.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataReuseContractsTest {
  static final String CONTENT = "c".repeat(32), ACTUAL = "a".repeat(32), PREFERRED = "b".repeat(32);
  static final Instant ORIGINAL = Instant.parse("2026-01-01T00:00:00Z"),
      ASSOCIATED = ORIGINAL.plusSeconds(1);

  static BuildSnapshot build() {
    return new BuildSnapshot(
        "wow", "Retail", "Published", "us", "1.0", "d".repeat(32), "e".repeat(32), ORIGINAL, false);
  }

  static VerifiedSourceAlias configuration() {
    return new VerifiedSourceAlias(
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build().buildConfig(), "tact-config-raw-md5-v1"),
        new StoredObject(new ObjectDigest("f".repeat(64)), 50),
        new SourceVerificationEvidence(
            "tact-config-raw-md5-v1",
            "https://us.cdn.blizzard.com/tpr/wow/config/dd/dd/" + build().buildConfig(),
            ORIGINAL));
  }

  static MetadataContentProof proof(boolean decoded, boolean grammar) {
    var alias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "tact.install.v1",
                CONTENT + ";" + ACTUAL + ";100;80",
                "original-install-verifier-v1"),
            new StoredObject(new ObjectDigest("1".repeat(64)), 80),
            new SourceVerificationEvidence(
                "original-install-verifier-v1",
                "https://us.cdn.blizzard.com/tpr/wow/data/aa/aa/" + ACTUAL,
                ORIGINAL));
    return new MetadataContentProof(
        alias,
        ACTUAL,
        CONTENT,
        OptionalLong.of(100),
        decoded
            ? Optional.of(new DecodedContentAttestation(CONTENT, 100, "blte-nz-full-ckey-v1"))
            : Optional.empty(),
        grammar
            ? Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.INSTALL, "install-manifest-v1-r1"))
            : Optional.empty());
  }

  static MetadataDeclaration declaration(
      Optional<String> key, OptionalLong encoded, OptionalLong decoded) {
    return new MetadataDeclaration(
        build(),
        configuration(),
        MetadataStructureAttestation.Kind.INSTALL,
        new ContentSlotKey("wow", CONTENT),
        key,
        encoded,
        decoded);
  }

  static MetadataDeclaration declaration() {
    return declaration(Optional.of(PREFERRED), OptionalLong.of(90), OptionalLong.of(100));
  }

  @Test
  void keepsDifferentBuildDeclarationAndActualOriginalReceiptSeparate() {
    var declaration = declaration();
    var actual = proof(true, true);
    var token = UUID.randomUUID();
    var plan = new MetadataReusePlan(UUID.randomUUID(), token, declaration, actual);
    assertEquals(PREFERRED, plan.declaration().preferredEncodingKey().orElseThrow());
    assertEquals(90, plan.declaration().declaredEncodedBytes().orElseThrow());
    assertEquals(ACTUAL, plan.actualRepresentation().actualEncodingKey());
    assertEquals(80, plan.actualRepresentation().object().length());
    assertEquals(0, plan.additionalPayloadTransferBytes());
    assertEquals(MetadataReusePlan.State.LOCAL_REPLAY_REQUIRED, plan.state());
    assertEquals(actual.originalAlias(), plan.actualRepresentation().originalAlias());
    var association = new MetadataBuildAssociation(declaration, token, actual, ASSOCIATED);
    assertEquals(declaration, association.declaration());
    assertEquals(actual, association.actualRepresentation());
    assertEquals(token, association.canonicalReservationToken());
    assertEquals(
        ORIGINAL, association.actualRepresentation().originalAlias().evidence().verifiedAt());
    assertEquals(ASSOCIATED, association.associatedAt());
    assertFalse(
        association
            .actualRepresentation()
            .originalAlias()
            .source()
            .immutableSourceKey()
            .contains(PREFERRED));
  }

  @Test
  void preservesUnknownConfigurationFactsWithoutInventingCurrentEncodingMembership() {
    var declaration = declaration(Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
    var plan =
        new MetadataReusePlan(UUID.randomUUID(), UUID.randomUUID(), declaration, proof(true, true));
    assertTrue(plan.declaration().preferredEncodingKey().isEmpty());
    assertTrue(plan.declaration().declaredEncodedBytes().isEmpty());
    assertTrue(plan.declaration().declaredDecodedBytes().isEmpty());
    assertEquals(0, plan.additionalPayloadTransferBytes());
  }

  @Test
  void historicalAndDecodedOnlyProofsCannotEnterStrongReuseValues() {
    for (var actual : List.of(proof(false, false), proof(true, false))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new MetadataReusePlan(UUID.randomUUID(), UUID.randomUUID(), declaration(), actual));
      assertThrows(
          IllegalArgumentException.class,
          () -> new MetadataBuildAssociation(declaration(), UUID.randomUUID(), actual, ASSOCIATED));
    }
  }

  @Test
  void independentlyDeclaredDecodedConflictFailsButEncodedDifferenceIsValid() {
    var wrong = declaration(Optional.of(PREFERRED), OptionalLong.of(90), OptionalLong.of(101));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataReusePlan(UUID.randomUUID(), UUID.randomUUID(), wrong, proof(true, true)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataBuildAssociation(wrong, UUID.randomUUID(), proof(true, true), ASSOCIATED));
    assertDoesNotThrow(
        () ->
            new MetadataReusePlan(
                UUID.randomUUID(), UUID.randomUUID(), declaration(), proof(true, true)));
  }

  @Test
  void exactConfigurationAndReviewedNamespaceAreRequired() {
    var config = configuration();
    var foreign =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "tact.config.raw-md5", "0".repeat(32), "tact-config-raw-md5-v1"),
            config.object(),
            config.evidence());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataDeclaration(
                build(),
                foreign,
                MetadataStructureAttestation.Kind.INSTALL,
                new ContentSlotKey("wow", CONTENT),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty()));
    var other =
        new BuildSnapshot(
            "wow_unknown",
            "Classic",
            "Published",
            "us",
            "1.0",
            build().buildConfig(),
            build().cdnConfig(),
            ORIGINAL,
            false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataDeclaration(
                other,
                config,
                MetadataStructureAttestation.Kind.INSTALL,
                new ContentSlotKey("wow", CONTENT),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty()));
  }

  @Test
  void classicBetaDeclarationPreservesExactProductAndOptionalFacts() {
    var b = build();
    var classic =
        new BuildSnapshot(
            "wow_classic_beta",
            "Classic Beta",
            b.channel(),
            b.region(),
            b.versionLabel(),
            b.buildConfig(),
            b.cdnConfig(),
            b.observedAt(),
            b.fixture());
    var value =
        new MetadataDeclaration(
            classic,
            configuration(),
            MetadataStructureAttestation.Kind.INSTALL,
            new ContentSlotKey("wow", CONTENT),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
    assertEquals(classic, value.build());
    assertEquals("wow", value.slot().namespace());
    assertTrue(value.declaredEncodedBytes().isEmpty());
  }

  @Test
  void candidateGeometryMustBeKnownOnlyWhenActuallyDeclaredAndPositive() {
    assertThrows(
        IllegalArgumentException.class,
        () -> declaration(Optional.empty(), OptionalLong.of(90), OptionalLong.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> declaration(Optional.of("bad"), OptionalLong.empty(), OptionalLong.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> declaration(Optional.of(PREFERRED), OptionalLong.of(0), OptionalLong.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> declaration(Optional.empty(), OptionalLong.empty(), OptionalLong.of(0)));
  }

  @Test
  void contentAndGrammarMustMatchActualFullProof() {
    var wrongContent =
        new MetadataDeclaration(
            build(),
            configuration(),
            MetadataStructureAttestation.Kind.INSTALL,
            new ContentSlotKey("wow", "2".repeat(32)),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
    var wrongKind =
        new MetadataDeclaration(
            build(),
            configuration(),
            MetadataStructureAttestation.Kind.ROOT,
            new ContentSlotKey("wow", CONTENT),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
    for (var declaration : List.of(wrongContent, wrongKind))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new MetadataReusePlan(
                  UUID.randomUUID(), UUID.randomUUID(), declaration, proof(true, true)));
  }

  @Test
  void requiredIdentityAndAssociationOriginRemainExplicit() {
    assertThrows(
        NullPointerException.class,
        () -> new MetadataReusePlan(null, UUID.randomUUID(), declaration(), proof(true, true)));
    assertThrows(
        NullPointerException.class,
        () -> new MetadataReusePlan(UUID.randomUUID(), null, declaration(), proof(true, true)));
    assertThrows(
        NullPointerException.class,
        () ->
            new MetadataBuildAssociation(
                declaration(), UUID.randomUUID(), proof(true, true), null));
  }

  @Test
  void sameEncodingDeclarationCannotDisagreeWithItsOwnActualLength() {
    var conflict = declaration(Optional.of(ACTUAL), OptionalLong.of(90), OptionalLong.of(100));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataReusePlan(
                UUID.randomUUID(), UUID.randomUUID(), conflict, proof(true, true)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MetadataBuildAssociation(
                conflict, UUID.randomUUID(), proof(true, true), ASSOCIATED));
  }

  @Test
  void rootCkeyOnlyDeclarationCanCarryAnActualRootProofWithoutInventedEnCandidate() {
    var install = proof(true, true);
    var original = install.originalAlias();
    var alias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "wow.root.tsfm.v1v2",
                original.source().immutableSourceKey(),
                "original-root-verifier-v1"),
            original.object(),
            new SourceVerificationEvidence(
                "original-root-verifier-v1", original.evidence().sourceUrl(), ORIGINAL));
    var rootProof =
        new MetadataContentProof(
            alias,
            ACTUAL,
            CONTENT,
            OptionalLong.of(100),
            install.decodedAttestation(),
            Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "wow-root-tsfm-v1v2-r1")));
    var declaration =
        new MetadataDeclaration(
            build(),
            configuration(),
            MetadataStructureAttestation.Kind.ROOT,
            new ContentSlotKey("wow", CONTENT),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
    var plan = new MetadataReusePlan(UUID.randomUUID(), UUID.randomUUID(), declaration, rootProof);
    assertTrue(plan.declaration().preferredEncodingKey().isEmpty());
    assertEquals(rootProof, plan.actualRepresentation());
    assertEquals(MetadataReusePlan.State.LOCAL_REPLAY_REQUIRED, plan.state());
  }
}
