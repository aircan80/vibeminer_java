package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialSelectedFileGatewayTest {
  @TempDir Path directory;

  @Test
  void candidatesReplayActualRetainedVariantPreserveAllAlternativesAndNeverFetchFilePayload()
      throws Exception {
    try (var setup = new Setup(directory, false)) {
      var plan = setup.candidates();
      assertEquals(2, plan.candidates().size());
      assertEquals(7, plan.proof().declaredDecodedBytes().orElseThrow());
      assertEquals(plan.candidates(), plan.proof().authenticatedCandidates());
      assertEquals(setup.variant, plan.variant());
      assertEquals(setup.root.source(), plan.proof().rootSource());
      assertTrue(
          setup.fixture.requests.stream()
              .noneMatch(r -> r.uri().getPath().endsWith(setup.fixture.selectedEKey)));
      var wrong =
          new FileVariantAssociation(
              setup.build,
              setup.root.source(),
              42,
              "f".repeat(32),
              setup.variant.localeFlags(),
              setup.variant.contentFlags(),
              setup.variant.rawFlagsFirst(),
              setup.variant.rawFlagsSecond(),
              setup.variant.rawFlagsThird(),
              setup.variant.nameHash());
      assertThrows(
          SelectedFileException.class,
          () ->
              setup.gateway.prepareCandidates(
                  setup.build,
                  setup.config,
                  setup.root,
                  wrong,
                  SelectedFileBudgets.defaults(),
                  CancellationToken.none()));
    }
  }

  @Test
  void looseUnknownModeRetentionIsSinglePassEncodedOnlyAndOfflineReceiptSurvives()
      throws Exception {
    try (var setup = new Setup(directory, false)) {
      var plan = setup.candidates();
      var choice = choice(plan, setup.fixture.selectedEKey);
      var located =
          setup.gateway.probeLoose(choice, CancellationToken.none()).located().orElseThrow();
      assertEquals(setup.fixture.selected.length, located.encodedBytes());
      var observed = new AtomicReference<ContentRepresentation>();
      var retained = setup.gateway.acquireEncoded(located, observed::set, CancellationToken.none());
      assertEquals(observed.get(), retained);
      assertEquals(EncodedScope.ZERO_HEADER_WHOLE_ENCODED, retained.scope());
      assertEquals(setup.fixture.selected.length, retained.sourceReceipt().payloadResponseBytes());
      assertTrue(
          setup.fixture.requests.stream()
                  .filter(
                      r ->
                          r.method().equals("GET")
                              && r.uri().getPath().endsWith(setup.fixture.selectedEKey))
                  .count()
              == 1);
      setup.fixture.rejectNetwork = true;
      var association =
          new SelectedFileAssociation(
              new SelectedFileDescriptor(
                  setup.variant,
                  setup.fixture.selectedEKey,
                  ContentRepresentation.VERIFIER_SEMANTICS),
              setup.config,
              setup.root,
              plan.proof(),
              retained);
      assertEquals(
          retained,
          setup.gateway.inspectRetained(
              association, SelectedFileBudgets.defaults(), CancellationToken.none()));
    }
  }

  @Test
  void onlyExplicit404PermitAllowsAuthenticatedArchiveLookupAndChildOnlyAcquisition()
      throws Exception {
    try (var setup = new Setup(directory, false)) {
      var plan = setup.candidates();
      setup.fixture.statuses.put(setup.fixture.route("HEAD", setup.fixture.selectedEKey), 404);
      var probe =
          setup.gateway.probeLoose(
              choice(plan, setup.fixture.selectedEKey), CancellationToken.none());
      assertTrue(probe.located().isEmpty());
      assertTrue(
          setup.fixture.requests.stream().noneMatch(r -> r.uri().getPath().endsWith(".index")));
      var found =
          setup
              .gateway
              .searchArchives(
                  probe.missing().orElseThrow(),
                  ArchiveSearchBudgets.defaults(),
                  CancellationToken.none())
              .located()
              .orElseThrow();
      assertEquals(setup.fixture.archiveOffset, found.locator().childOffset());
      assertEquals(FileLocator.Kind.ARCHIVE, found.locator().kind());
      var retained = setup.gateway.acquireEncoded(found, ignored -> {}, CancellationToken.none());
      assertEquals(EncodedSourceReceipt.Kind.ARCHIVE, retained.sourceReceipt().kind());
      assertEquals(setup.fixture.archiveKey, retained.sourceReceipt().resourceKey());
      assertEquals(setup.fixture.selected.length, retained.encoded().object().length());
    }
  }

  @Test
  void non404LooseResponsesNeverTriggerArchiveFallback() throws Exception {
    for (int status : new int[] {403, 410, 416, 503})
      try (var setup = new Setup(directory.resolve("s" + status), false)) {
        var plan = setup.candidates();
        setup.fixture.statuses.put(setup.fixture.route("HEAD", setup.fixture.selectedEKey), status);
        assertThrows(
            SelectedFileException.class,
            () ->
                setup.gateway.probeLoose(
                    choice(plan, setup.fixture.selectedEKey), CancellationToken.none()));
        assertTrue(
            setup.fixture.requests.stream().noneMatch(r -> r.uri().getPath().endsWith(".index")));
      }
  }

  @Test
  void staleAdvisoryIndexSizeRemainsVisibleWithoutOverridingAuthenticatedGeometry()
      throws Exception {
    try (var setup = new Setup(directory, false, OptionalLong.of(12388))) {
      var plan = setup.candidates();
      setup.fixture.statuses.put(setup.fixture.route("HEAD", setup.fixture.selectedEKey), 404);
      var permit =
          setup
              .gateway
              .probeLoose(choice(plan, setup.fixture.selectedEKey), CancellationToken.none())
              .missing()
              .orElseThrow();
      var found =
          setup
              .gateway
              .searchArchives(permit, ArchiveSearchBudgets.defaults(), CancellationToken.none())
              .located()
              .orElseThrow();
      var proof = found.locator().archiveProof().orElseThrow();
      assertEquals(12388, proof.advertisedIndexBytes().orElseThrow());
      assertEquals(4148, proof.authenticatedIndexBytes());
      var retained = setup.gateway.acquireEncoded(found, ignored -> {}, CancellationToken.none());
      assertEquals(proof, retained.sourceReceipt().archiveMapping().orElseThrow());
    }
  }

  static CandidateChoice choice(CandidatePlan plan, String key) {
    return new CandidateChoice(
        plan,
        plan.candidates().stream()
            .filter(c -> c.encodingKey().equals(key))
            .findFirst()
            .orElseThrow());
  }

  static final class Setup implements AutoCloseable {
    final SelectedFileHttpFixture fixture;
    final FileObjectStore store;
    final BuildSnapshot build;
    final VerifiedSourceAlias config, root;
    final FileVariantAssociation variant;
    final OfficialSelectedFileGateway gateway;

    Setup(Path directory, boolean unknown) throws Exception {
      this(directory, unknown, OptionalLong.empty());
    }

    Setup(Path directory, boolean unknown, OptionalLong hint) throws Exception {
      fixture = new SelectedFileHttpFixture(unknown, hint);
      store = new FileObjectStore(directory);
      build = fixture.build();
      var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
      var client = fixture.client();
      var transport = new PinnedCdnTransport(client, clock, Duration.ofSeconds(1));
      var range = new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1));
      try (var roots = new OfficialFileLookupGateway(store, transport, range, clock)) {
        var plan =
            roots.prepareRoot(
                build, Optional.empty(), LookupBudgets.defaults(), CancellationToken.none());
        config = plan.configuration();
        root =
            roots.acquireRoot(
                new RootSelectionPlan(plan, plan.candidates().getFirst()),
                CancellationToken.none());
        var v =
            roots
                .lookupRetained(
                    build,
                    config,
                    root,
                    Set.of(42L),
                    LookupBudgets.defaults(),
                    CancellationToken.none())
                .variants()
                .stream()
                .filter(vv -> vv.contentKey().equals(fixture.selectedCKey))
                .findFirst()
                .orElseThrow();
        variant =
            new FileVariantAssociation(
                build,
                root.source(),
                v.fileDataId(),
                v.contentKey(),
                v.localeFlags(),
                v.contentFlags(),
                v.rawFlagsFirst(),
                v.rawFlagsSecond(),
                v.rawFlagsThird(),
                v.nameHash());
      }
      fixture.requests.clear();
      gateway =
          new OfficialSelectedFileGateway(
              store,
              transport,
              range,
              new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
              clock);
    }

    CandidatePlan candidates() {
      return gateway.prepareCandidates(
          build, config, root, variant, SelectedFileBudgets.defaults(), CancellationToken.none());
    }

    public void close() {
      gateway.close();
      store.close();
    }
  }
}
