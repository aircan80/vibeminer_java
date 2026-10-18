package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.infrastructure.lookup.OfficialSelectedFileGatewayTest.choice;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectedFileRetainedProofTest {
  @TempDir Path directory;

  @Test
  void durableEncodedReceiptAndDeclaredOnlyAssociationReopenWithNewStoreAndNoHttp()
      throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var plan = s.candidates();
      var located =
          s.gateway
              .probeLoose(choice(plan, s.fixture.secondSelectedEKey), CancellationToken.none())
              .located()
              .orElseThrow();
      var retained = s.gateway.acquireEncoded(located, ignored -> {}, CancellationToken.none());
      assertEquals(EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS, retained.scope());
      assertEquals(7, plan.proof().declaredDecodedBytes().orElseThrow());
      var association =
          new SelectedFileAssociation(
              new SelectedFileDescriptor(
                  s.variant,
                  s.fixture.secondSelectedEKey,
                  ContentRepresentation.VERIFIER_SEMANTICS),
              s.config,
              s.root,
              plan.proof(),
              retained);
      s.gateway.close();
      s.store.close();
      s.fixture.rejectNetwork = true;
      try (var reopened = new FileObjectStore(directory);
          var gateway = gateway(reopened, s.fixture, new AtomicLong())) {
        assertEquals(
            retained,
            gateway.inspectRetained(
                association, SelectedFileBudgets.defaults(), CancellationToken.none()));
      }
    }
  }

  @Test
  void
      changedDecodedDeclarationCandidatesDigestOrBootstrapKindCannotMasqueradeAsOriginalAttestation()
          throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var plan = s.candidates();
      var retained =
          s.gateway.acquireEncoded(
              s.gateway
                  .probeLoose(choice(plan, s.fixture.selectedEKey), CancellationToken.none())
                  .located()
                  .orElseThrow(),
              ignored -> {},
              CancellationToken.none());
      var descriptor =
          new SelectedFileDescriptor(
              s.variant, s.fixture.selectedEKey, ContentRepresentation.VERIFIER_SEMANTICS);
      var p = plan.proof();
      var changedKind =
          new ResolvedSourceIdentity(
              "other.encoding",
              p.encodingBootstrapSource().immutableSourceKey(),
              p.encodingBootstrapSource().verificationSemantics());
      var altered =
          List.of(
              new FileAssociationProof(
                  p.rootSource(),
                  p.encodingBootstrapSource(),
                  p.verifierRevision(),
                  p.proofDigest(),
                  OptionalLong.of(8),
                  p.authenticatedCandidates()),
              new FileAssociationProof(
                  p.rootSource(),
                  changedKind,
                  p.verifierRevision(),
                  p.proofDigest(),
                  p.declaredDecodedBytes(),
                  p.authenticatedCandidates()),
              new FileAssociationProof(
                  p.rootSource(),
                  p.encodingBootstrapSource(),
                  p.verifierRevision(),
                  "0".repeat(64),
                  p.declaredDecodedBytes(),
                  p.authenticatedCandidates()),
              new FileAssociationProof(
                  p.rootSource(),
                  p.encodingBootstrapSource(),
                  p.verifierRevision(),
                  p.proofDigest(),
                  p.declaredDecodedBytes(),
                  List.of(p.authenticatedCandidates().getLast())),
              new FileAssociationProof(
                  p.rootSource(),
                  p.encodingBootstrapSource(),
                  p.verifierRevision(),
                  p.proofDigest()));
      s.fixture.rejectNetwork = true;
      for (var proof : altered) {
        var association =
            new SelectedFileAssociation(descriptor, s.config, s.root, proof, retained);
        var error =
            assertThrows(
                SelectedFileException.class,
                () ->
                    s.gateway.inspectRetained(
                        association, SelectedFileBudgets.defaults(), CancellationToken.none()));
        assertEquals(SelectedFileException.Code.INTEGRITY_FAILURE, error.code());
      }
    }
  }

  @Test
  void canonicalPlanLifetimeUsesMonotonicClockEvenWhenWallClockDoesNotAdvance() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var nanos = new AtomicLong();
      try (var gateway = gateway(s.store, s.fixture, nanos)) {
        var plan =
            gateway.prepareCandidates(
                s.build,
                s.config,
                s.root,
                s.variant,
                SelectedFileBudgets.defaults(),
                CancellationToken.none());
        int before = s.fixture.requests.size();
        nanos.set(Duration.ofMinutes(11).toNanos());
        var error =
            assertThrows(
                SelectedFileException.class,
                () -> gateway.validateCandidatePlan(plan, CancellationToken.none()));
        assertEquals(SelectedFileException.Code.INVALID_PLAN, error.code());
        assertEquals(before, s.fixture.requests.size());
      }
    }
  }

  private static OfficialSelectedFileGateway gateway(
      ObjectStore store, SelectedFileHttpFixture fixture, AtomicLong nanos) {
    var client = fixture.client();
    var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
    return new OfficialSelectedFileGateway(
        store,
        new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
        new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
        new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
        clock,
        nanos::get);
  }
}
