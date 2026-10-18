package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.infrastructure.lookup.OfficialSelectedFileGatewayTest.choice;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.selectedfile.ContentRepresentation;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisition.PinnedCdnRangeTransport;
import io.vibeminer.infrastructure.encodedsource.OfficialEncodedSourcePublisher;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncodedSourcePublisherTest {
  @TempDir Path directory;

  @Test
  void selectedPayloadDeadlineIncludesAuthorityReplayBeforePhysicalPublication() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var nanos = new AtomicLong();
      var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
      var client = s.fixture.client();
      try (var gateway =
          new OfficialSelectedFileGateway(
              s.store,
              new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
              new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
              new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
              clock,
              nanos::get)) {
        var candidates =
            gateway.prepareCandidates(
                s.build,
                s.config,
                s.root,
                s.variant,
                SelectedFileBudgets.defaults(),
                CancellationToken.none());
        var located =
            gateway
                .probeLoose(choice(candidates, s.fixture.selectedEKey), CancellationToken.none())
                .located()
                .orElseThrow();
        CancellationToken advanceDuringReplay =
            () -> {
              nanos.set(candidates.budgets().maximumPayloadDuration().toNanos() + 1);
              return false;
            };
        var failure =
            assertThrows(
                EncodedAcquisitionFailure.class,
                () ->
                    gateway.acquireEncoded(
                        located, prospective -> fail("No promotion"), advanceDuringReplay));
        assertEquals(SelectedFileException.Code.BUDGET_EXCEEDED, failure.failure().code());
        assertEquals(
            EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      }
    }
  }

  @Test
  void extractedPublisherStreamsOneEncodedObjectAndReplaysOriginalReceiptWithoutHttp()
      throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var selected = choice(s.candidates(), s.fixture.selectedEKey);
      var located =
          s.gateway.probeLoose(selected, CancellationToken.none()).located().orElseThrow();
      var authorized = s.gateway.authorizeEncodedSource(located, CancellationToken.none());
      authorized.gateway().validate(authorized.plan(), CancellationToken.none());
      var publisher =
          new OfficialEncodedSourcePublisher(
              s.store,
              new PinnedCdnRangeTransport(
                  s.fixture.client(),
                  Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC),
                  Duration.ofSeconds(1)));
      var observed = new AtomicReference<ContentRepresentation>();
      var retained =
          publisher.acquire(
              authorized.plan(),
              prospective -> {
                observed.set(prospective);
                assertTrue(
                    s.store
                        .verify(prospective.encoded().object().digest(), CancellationToken.none())
                        .isEmpty());
              },
              CancellationToken.none());
      assertEquals(observed.get(), retained);
      assertEquals(
          1,
          s.fixture.requests.stream()
              .filter(
                  r ->
                      r.method().equals("GET")
                          && r.uri().getPath().endsWith(s.fixture.selectedEKey))
              .count());
      s.fixture.rejectNetwork = true;
      assertSame(
          retained,
          publisher.inspect(retained, authorized.plan().limits(), CancellationToken.none()));
      assertEquals(
          retained.sourceReceipt().actualResourceUrl(), retained.encoded().evidence().sourceUrl());
    }
  }

  @Test
  void refusedProspectiveGateNeverPromotesAndNeverBecomesASecondPublisherPath() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var located =
          s.gateway
              .probeLoose(choice(s.candidates(), s.fixture.selectedEKey), CancellationToken.none())
              .located()
              .orElseThrow();
      var authorized = s.gateway.authorizeEncodedSource(located, CancellationToken.none());
      var publisher =
          new OfficialEncodedSourcePublisher(
              s.store,
              new PinnedCdnRangeTransport(
                  s.fixture.client(),
                  Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC),
                  Duration.ofSeconds(1)));
      var proof = new AtomicReference<ContentRepresentation>();
      var failed =
          assertThrows(
              EncodedSourceGateway.AcquisitionFailure.class,
              () ->
                  publisher.acquire(
                      authorized.plan(),
                      prospective -> {
                        proof.set(prospective);
                        throw new IllegalStateException("Prospective authority refused");
                      },
                      CancellationToken.none()));
      assertEquals(
          EncodedSourceGateway.AcquisitionFailure.Publication.UNCERTAIN, failed.publication());
      assertTrue(
          s.store
              .verify(proof.get().encoded().object().digest(), CancellationToken.none())
              .isEmpty());
    }
  }
}
