package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.encodedsource.OfficialEncodedSourceLocator;
import java.nio.file.Path;
import java.time.*;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncodedSourceLocatorTest {
  @TempDir Path directory;

  @Test
  void selectedArchiveDeadlineRemainsAnIncompleteSearchInsteadOfAnIntegrityFailure()
      throws Exception {
    try (var setup = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var nanos = new AtomicLong();
      var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
      var client = setup.fixture.client();
      try (var gateway =
          new OfficialSelectedFileGateway(
              setup.store,
              new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
              new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
              new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
              clock,
              nanos::get)) {
        var candidates =
            gateway.prepareCandidates(
                setup.build,
                setup.config,
                setup.root,
                setup.variant,
                SelectedFileBudgets.defaults(),
                CancellationToken.none());
        var choice = OfficialSelectedFileGatewayTest.choice(candidates, setup.fixture.selectedEKey);
        setup.fixture.statuses.put(setup.fixture.route("HEAD", setup.fixture.selectedEKey), 404);
        var permit = gateway.probeLoose(choice, CancellationToken.none()).missing().orElseThrow();
        var budget = ArchiveSearchBudgets.defaults();
        setup.fixture.requestObserver =
            request -> {
              if (request.method().equals("GET")) nanos.set(budget.maximumDuration().toNanos() + 1);
            };
        var result = gateway.searchArchives(permit, budget, CancellationToken.none());
        assertFalse(result.complete());
        assertTrue(result.located().isEmpty());
        assertEquals(0, result.checkedArchives());
      }
    }
  }

  private static final OfficialEncodedSourceLocator.Policy POLICY =
      new OfficialEncodedSourceLocator.Policy(
          1 << 20, 16 << 20, 128 << 20, 512, 8L << 30, 16 << 20, 2048, Duration.ofMinutes(5));

  @Test
  void neutralLocatorChecksKnownSizeWithoutAnySelectedVariantOrPayloadTransfer() throws Exception {
    var fixture = new SelectedFileHttpFixture();
    var locator = locator(fixture);
    var found =
        locator.probeLoose(
            fixture.build(),
            fixture.selectedEKey,
            OptionalLong.of(fixture.selected.length),
            POLICY,
            CancellationToken.none());
    assertEquals(EncodedSourcePlan.LocationKind.LOOSE, found.kind());
    assertEquals(fixture.selected.length, found.childLength());
    assertEquals(1, fixture.requests.size());
    assertEquals("HEAD", fixture.requests.getFirst().method());
    assertEquals(
        EncodedSourceException.Code.INTEGRITY_FAILURE,
        assertThrows(
                EncodedSourceException.class,
                () ->
                    locator.probeLoose(
                        fixture.build(),
                        fixture.selectedEKey,
                        OptionalLong.of(fixture.selected.length + 1),
                        POLICY,
                        CancellationToken.none()))
            .code());
  }

  @Test
  void authenticatedArchiveGeometryUsesTheSameNeutralLocatorPolicy() throws Exception {
    var fixture = new SelectedFileHttpFixture();
    var found =
        locator(fixture)
            .searchArchives(
                fixture.build(),
                fixture.selectedEKey,
                OptionalLong.of(fixture.selected.length),
                POLICY,
                CancellationToken.none());
    var location = found.located().orElseThrow();
    assertEquals(EncodedSourcePlan.LocationKind.ARCHIVE, location.kind());
    assertEquals(fixture.archiveOffset, location.childOffset());
    assertEquals(fixture.selected.length, location.childLength());
    assertTrue(location.archiveProof().isPresent());
    assertTrue(
        fixture.requests.stream()
            .noneMatch(
                request ->
                    request.method().equals("GET")
                        && request.uri().getPath().endsWith(fixture.archiveKey)));
  }

  private static OfficialEncodedSourceLocator locator(SelectedFileHttpFixture fixture) {
    var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
    var client = fixture.client();
    return new OfficialEncodedSourceLocator(
        new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
        new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
        new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)));
  }
}
