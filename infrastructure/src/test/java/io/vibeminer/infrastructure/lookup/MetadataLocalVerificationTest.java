package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.content.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataLocalVerificationTest {
  @TempDir Path directory;

  @Test
  void historicalMissingCapabilitiesAreVerifiedLocallyWithoutChangingOriginalEvidence()
      throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory);
        var verifier = new OfficialMetadataLocalVerificationGateway(f.store)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var original = issued.originalProof();
      f.store.publish(
          new ByteArrayInputStream(f.rootFixture.encodedRoot),
          new io.vibeminer.domain.ExpectedObjectChecksum(
              io.vibeminer.domain.ChecksumAlgorithm.SHA256,
              original.object().digest().value(),
              f.rootFixture.encodedRoot.length),
          f.rootFixture.encodedRoot.length,
          CancellationToken.none());
      var fresh =
          verifier.verifyOriginal(
              issued.request(), original, LookupBudgets.defaults(), CancellationToken.none());
      assertSame(original.originalAlias(), fresh.originalAlias());
      assertEquals(original.declaredDecodedBytes(), fresh.declaredDecodedBytes());
      assertTrue(original.decodedAttestation().isEmpty());
      assertTrue(fresh.decodedAttestation().isPresent());
      assertEquals(
          MetadataStructureAttestation.Kind.ROOT,
          fresh.structuralAttestation().orElseThrow().kind());
    }
  }

  @Test
  void installKeepsHistoricalOptionalDeclarationsAndCapabilitiesUntouched() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory);
        var verifier = new OfficialMetadataLocalVerificationGateway(f.store)) {
      f.client.length = f.install.encodedBytes();
      var issued =
          f.gateway.prepareInstall(
              f.install, f.installConfiguration, f.installClaim, CancellationToken.none());
      var base = issued.originalProof();
      byte[] encoded = MetadataRepairExecutionTest.installBytes();
      publish(f.store, base, encoded);
      var original =
          new MetadataContentProof(
              base.originalAlias(),
              base.actualEncodingKey(),
              base.contentKey(),
              java.util.OptionalLong.empty(),
              java.util.Optional.of(
                  new DecodedContentAttestation(
                      base.contentKey(), f.install.decodedBytes(), "historical-decoder")),
              java.util.Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.INSTALL, "historical-parser")));
      var fresh =
          verifier.verifyOriginal(
              issued.request(), original, LookupBudgets.defaults(), CancellationToken.none());
      assertSame(original.originalAlias(), fresh.originalAlias());
      assertTrue(fresh.declaredDecodedBytes().isEmpty());
      assertEquals(
          "historical-decoder", original.decodedAttestation().orElseThrow().verifierRevision());
      assertEquals(
          "blte-nz-full-ckey-v1", fresh.decodedAttestation().orElseThrow().verifierRevision());
      assertEquals(
          "install-manifest-v1-r1", fresh.structuralAttestation().orElseThrow().parserRevision());
      assertEquals(1, f.client.requests.size()); // Only the earlier planning HEAD.
    }
  }

  @Test
  void missingCorruptAndCancelledOriginalNeverBecomeFreshProof() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory);
        var verifier = new OfficialMetadataLocalVerificationGateway(f.store)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var original = issued.originalProof();
      assertEquals(
          ContentClaimException.Code.MISSING_LOCAL,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      verifier.verifyOriginal(
                          issued.request(),
                          original,
                          LookupBudgets.defaults(),
                          CancellationToken.none()))
              .code());
      publish(f.store, original, f.rootFixture.encodedRoot);
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              verifier.verifyOriginal(
                  issued.request(), original, LookupBudgets.defaults(), () -> true));
      var path =
          directory
              .resolve("objects")
              .resolve(original.object().digest().value().substring(0, 2))
              .resolve(original.object().digest().value().substring(2));
      byte[] corrupt = f.rootFixture.encodedRoot.clone();
      corrupt[corrupt.length - 1] ^= 1;
      java.nio.file.Files.write(path, corrupt);
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      verifier.verifyOriginal(
                          issued.request(),
                          original,
                          LookupBudgets.defaults(),
                          CancellationToken.none()))
              .code());
      assertArrayEquals(corrupt, java.nio.file.Files.readAllBytes(path));
    }
  }

  @Test
  void installMissingAndPhysicalCorruptionExposeTypedLocalFailures() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory);
        var verifier = new OfficialMetadataLocalVerificationGateway(f.store)) {
      f.client.length = f.install.encodedBytes();
      var issued =
          f.gateway.prepareInstall(
              f.install, f.installConfiguration, f.installClaim, CancellationToken.none());
      var original = issued.originalProof();
      var missing =
          assertThrows(
              ContentClaimException.class,
              () ->
                  verifier.verifyOriginal(
                      issued.request(),
                      original,
                      LookupBudgets.defaults(),
                      CancellationToken.none()));
      assertEquals(ContentClaimException.Code.MISSING_LOCAL, missing.code());
      assertInstanceOf(ObjectStoreException.class, missing.getCause());
      publish(f.store, original, MetadataRepairExecutionTest.installBytes());
      var digest = original.object().digest().value();
      var path =
          directory.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2));
      byte[] damaged = java.nio.file.Files.readAllBytes(path);
      damaged[damaged.length - 1] ^= 1;
      java.nio.file.Files.write(path, damaged);
      var corrupt =
          assertThrows(
              ContentClaimException.class,
              () ->
                  verifier.verifyOriginal(
                      issued.request(),
                      original,
                      LookupBudgets.defaults(),
                      CancellationToken.none()));
      assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, corrupt.code());
      assertInstanceOf(ObjectStoreException.class, corrupt.getCause());
      assertEquals(1, f.client.requests.size());
    }
  }

  @Test
  void forgedOriginalRouteAndChangedCurrentDeclarationAreRejected() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory);
        var verifier = new OfficialMetadataLocalVerificationGateway(f.store)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var original = issued.originalProof();
      publish(f.store, original, f.rootFixture.encodedRoot);
      var alias = original.originalAlias();
      var forgedAlias =
          new io.vibeminer.domain.VerifiedSourceAlias(
              alias.source(),
              alias.object(),
              new io.vibeminer.domain.SourceVerificationEvidence(
                  alias.evidence().verifierSemantics(),
                  alias.evidence().sourceUrl().replace("us.cdn.blizzard.com", "evil.example"),
                  alias.evidence().verifiedAt()));
      var forged =
          new MetadataContentProof(
              forgedAlias,
              original.actualEncodingKey(),
              original.contentKey(),
              original.declaredDecodedBytes(),
              original.decodedAttestation(),
              original.structuralAttestation());
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      verifier.verifyOriginal(
                          issued.request(),
                          forged,
                          LookupBudgets.defaults(),
                          CancellationToken.none()))
              .code());
      var request = issued.request();
      var wrong =
          new MetadataContentRequest(
              request.build(),
              request.configuration(),
              request.kind(),
              request.slot(),
              "1".repeat(32),
              request.requestedEncodedBytes(),
              request.declaredDecodedBytes());
      var failure =
          assertThrows(
              ContentClaimException.class,
              () ->
                  verifier.verifyOriginal(
                      wrong, original, LookupBudgets.defaults(), CancellationToken.none()));
      assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, failure.code());
    }
  }

  @Test
  void supportedGrammarAndFullCkeyAreRequiredEvenWhenPhysicalShaIsValid() throws Exception {
    for (boolean wrongCkey : new boolean[] {true, false}) {
      try (var f =
              new MetadataRepairPlanningTest.Fixture(directory.resolve("grammar-" + wrongCkey));
          var verifier = new OfficialMetadataLocalVerificationGateway(f.store)) {
        var fixture = new RootLookupFixture(wrongCkey, !wrongCkey);
        try (var roots = new OfficialFileLookupGateway(f.store, fixture, f.clock)) {
          var plan =
              roots.prepareRoot(
                  fixture.build("us.cdn.blizzard.com", "3.0"),
                  java.util.Optional.empty(),
                  LookupBudgets.defaults(),
                  CancellationToken.none());
          var selection =
              new io.vibeminer.application.lookup.RootSelectionPlan(
                  plan, plan.candidates().getFirst());
          var claim =
              MetadataRepairPlanningTest.claim(
                  roots.rootIdentity(selection),
                  fixture.rootCKey,
                  fixture.rootEKey,
                  fixture.root.length,
                  fixture.encodedRoot);
          var original = (MetadataContentProof) claim.proof().orElseThrow();
          publish(f.store, original, fixture.encodedRoot);
          var request =
              new MetadataContentRequest(
                  plan.build(),
                  plan.configuration(),
                  MetadataStructureAttestation.Kind.ROOT,
                  claim.slot(),
                  claim.actualEncodingKey(),
                  claim.encodedBytes(),
                  java.util.OptionalLong.of(plan.decodedBytes()));
          fixture.rejectNetwork = true;
          assertEquals(
              ContentClaimException.Code.INTEGRITY_FAILURE,
              assertThrows(
                      ContentClaimException.class,
                      () ->
                          verifier.verifyOriginal(
                              request,
                              original,
                              LookupBudgets.defaults(),
                              CancellationToken.none()))
                  .code());
          assertTrue(
              f.store.verify(original.object().digest(), CancellationToken.none()).isPresent());
        }
      }
    }
  }

  static void publish(
      io.vibeminer.infrastructure.storage.FileObjectStore store,
      MetadataContentProof proof,
      byte[] bytes) {
    store.publish(
        new ByteArrayInputStream(bytes),
        new io.vibeminer.domain.ExpectedObjectChecksum(
            io.vibeminer.domain.ChecksumAlgorithm.SHA256,
            proof.object().digest().value(),
            bytes.length),
        bytes.length,
        CancellationToken.none());
  }
}
