package io.vibeminer.application.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssociatedInstallInspectionTest {
  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  static final String CONTENT = "c".repeat(32), ACTUAL = "a".repeat(32), DECLARED = "b".repeat(32);

  record Fixture(InstallPlan plan, MetadataBuildAssociation association) {}

  static Fixture fixture() {
    var build =
        new BuildSnapshot(
            "wow",
            "Retail",
            "Published",
            "us",
            "second",
            "d".repeat(32),
            "e".repeat(32),
            NOW,
            false);
    var config =
        new SourceDocument(
            "https://us.cdn.blizzard.com/tpr/wow/config/dd/dd/" + build.buildConfig(),
            NOW,
            new byte[] {1, 2, 3});
    var configAlias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1"),
            new StoredObject(new ObjectDigest(config.sha256()), config.bytes().length),
            new SourceVerificationEvidence(
                "tact-config-raw-md5-v1", config.sourceUrl(), config.capturedAt()));
    var alias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "tact.install.v1", CONTENT + ";" + ACTUAL + ";100;80", "install-v1"),
            new StoredObject(new ObjectDigest("1".repeat(64)), 80),
            new SourceVerificationEvidence(
                "install-v1",
                "https://us.cdn.blizzard.com/tpr/wow/data/aa/aa/" + ACTUAL,
                NOW.minusSeconds(1)));
    var proof =
        new MetadataContentProof(
            alias,
            ACTUAL,
            CONTENT,
            OptionalLong.of(100),
            Optional.of(new DecodedContentAttestation(CONTENT, 100, "blte-v1")),
            Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.INSTALL, "in-v1")));
    var declaration =
        new MetadataDeclaration(
            build,
            configAlias,
            MetadataStructureAttestation.Kind.INSTALL,
            new ContentSlotKey("wow", CONTENT),
            Optional.of(DECLARED),
            OptionalLong.of(90),
            OptionalLong.of(100));
    return new Fixture(
        new InstallPlan(build, config, CONTENT, DECLARED, 100, 90),
        new MetadataBuildAssociation(declaration, UUID.randomUUID(), proof, NOW));
  }

  static InstallInspection inspection(InstallPlan plan, MetadataBuildAssociation association) {
    var actual = association.actualRepresentation().originalAlias();
    return new InstallInspection(
        plan, actual.object(), actual.evidence(), List.of(), List.of(), Optional.of(association));
  }

  @Test
  void associatedResultKeepsPlanBAndActualObjectAndOriginalEvidenceA() {
    var f = fixture();
    var result = inspection(f.plan(), f.association());
    assertEquals(DECLARED, result.plan().encodingKey());
    assertEquals(90, result.plan().encodedBytes());
    assertEquals(80, result.object().length());
    assertEquals(
        f.association().actualRepresentation().originalAlias().evidence(), result.verification());
    assertEquals(Optional.of(f.association()), result.association());
  }

  @Test
  void oldConstructorKeepsEmptyAssociationAndOriginalBehavior() {
    var f = fixture();
    var actual = f.association().actualRepresentation().originalAlias();
    var result =
        new InstallInspection(f.plan(), actual.object(), actual.evidence(), List.of(), List.of());
    assertTrue(result.association().isEmpty());
  }

  @Test
  void differentSnapshotOrDeclaredTupleCannotBorrowAssociation() {
    var f = fixture();
    var p = f.plan();
    var b = p.build();
    var different =
        new BuildSnapshot(
            b.productId(),
            b.editionLabel(),
            b.channel(),
            b.region(),
            "other",
            b.buildConfig(),
            b.cdnConfig(),
            b.observedAt(),
            b.fixture(),
            b.evidence());
    for (var plan :
        List.of(
            new InstallPlan(
                different,
                p.configuration(),
                p.contentKey(),
                p.encodingKey(),
                p.decodedBytes(),
                p.encodedBytes()),
            new InstallPlan(
                b,
                p.configuration(),
                p.contentKey(),
                "0".repeat(32),
                p.decodedBytes(),
                p.encodedBytes()),
            new InstallPlan(
                b, p.configuration(), p.contentKey(), p.encodingKey(), p.decodedBytes(), 91)))
      assertThrows(IllegalArgumentException.class, () -> inspection(plan, f.association()));
  }

  @Test
  void exactConfigurationDocumentDigestAndReceiptAreRequired() {
    var f = fixture();
    var p = f.plan();
    for (var document :
        List.of(
            new SourceDocument(p.configuration().sourceUrl(), NOW, new byte[] {4}),
            new SourceDocument(
                p.configuration().sourceUrl(), NOW.plusSeconds(1), p.configuration().bytes()),
            new SourceDocument(
                "https://kr.cdn.blizzard.com/tpr/wow/config/dd/dd/" + p.build().buildConfig(),
                NOW,
                p.configuration().bytes())))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              inspection(
                  new InstallPlan(
                      p.build(),
                      document,
                      p.contentKey(),
                      p.encodingKey(),
                      p.decodedBytes(),
                      p.encodedBytes()),
                  f.association()));
  }

  @Test
  void actualObjectAndEvidenceCannotBeReplaced() {
    var f = fixture();
    var actual = f.association().actualRepresentation().originalAlias();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InstallInspection(
                f.plan(),
                new StoredObject(new ObjectDigest("2".repeat(64)), 80),
                actual.evidence(),
                List.of(),
                List.of(),
                Optional.of(f.association())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InstallInspection(
                f.plan(),
                actual.object(),
                new SourceVerificationEvidence(
                    actual.evidence().verifierSemantics(), actual.evidence().sourceUrl(), NOW),
                List.of(),
                List.of(),
                Optional.of(f.association())));
  }

  @Test
  void rootAssociationCannotAuthorizeInstallInspection() {
    var f = fixture();
    var original = f.association().actualRepresentation();
    var alias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "wow.root.tsfm.v1v2",
                original.originalAlias().source().immutableSourceKey(),
                "root-v1"),
            original.object(),
            new SourceVerificationEvidence(
                "root-v1",
                original.originalAlias().evidence().sourceUrl(),
                original.originalAlias().evidence().verifiedAt()));
    var root =
        new MetadataContentProof(
            alias,
            ACTUAL,
            CONTENT,
            original.declaredDecodedBytes(),
            original.decodedAttestation(),
            Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "root-v1")));
    var d = f.association().declaration();
    var declaration =
        new MetadataDeclaration(
            d.build(),
            d.configuration(),
            MetadataStructureAttestation.Kind.ROOT,
            d.slot(),
            d.preferredEncodingKey(),
            d.declaredEncodedBytes(),
            d.declaredDecodedBytes());
    var association = new MetadataBuildAssociation(declaration, UUID.randomUUID(), root, NOW);
    assertThrows(IllegalArgumentException.class, () -> inspection(f.plan(), association));
  }
}
