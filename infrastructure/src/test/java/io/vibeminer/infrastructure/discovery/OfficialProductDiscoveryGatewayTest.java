package io.vibeminer.infrastructure.discovery;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.discoveryhistory.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class OfficialProductDiscoveryGatewayTest {
  static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
  static final String VH =
      "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n";
  static final String CH = "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n";

  static String row(String region) {
    return region + "|" + "a".repeat(32) + "|" + "b".repeat(32) + "|same\n";
  }

  static OfficialDiscoveryService service(String versions, String cdns, AtomicInteger calls) {
    return new OfficialDiscoveryService(
        uri -> {
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(
              new CapturedMetadata(
                  uri,
                  NOW.minusSeconds(uri.getPath().endsWith("versions") ? 2 : 1),
                  (uri.getPath().endsWith("versions") ? versions : cdns)
                      .getBytes(StandardCharsets.UTF_8)));
        },
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void pairedCaptureUsesExactlyTwoRequestsAndSharesEvidenceAcrossActualRegions() {
    var calls = new AtomicInteger();
    var result =
        service(
                VH + row("us") + row("eu"),
                CH + "us|tpr/wow|cdn.invalid\neu|tpr/wow|cdn.invalid\n",
                calls)
            .observeProduct("wow", CancellationToken.none());
    assertEquals(2, calls.get());
    assertEquals(
        List.of("us", "eu"), result.events().stream().map(DiscoveryEvent::region).toList());
    assertTrue(result.failures().isEmpty());
    for (var event : result.events()) {
      assertSame(result.versions(), event.versions());
      assertSame(result.cdns(), event.cdns());
      assertEquals(NOW, event.completedAt());
      assertEquals(result.parserRevision(), event.parserRevision());
      assertEquals(event, new DiscoveryObservationMapper().replay(event));
    }
    assertNotEquals(result.events().get(0).eventId(), result.events().get(1).eventId());
  }

  @Test
  void regionalFailureDoesNotDiscardHealthyRegionOrFetchAgain() {
    var calls = new AtomicInteger();
    var result =
        service(VH + row("us") + row("eu"), CH + "us|tpr/wow|cdn.invalid\n", calls)
            .observeProduct("wow", CancellationToken.none());
    assertEquals(2, calls.get());
    assertEquals("us", result.events().getFirst().region());
    assertEquals("eu", result.failures().getFirst().scope().region());
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION, result.failures().getFirst().code());
  }

  @Test
  void malformedRegionsRemainDiagnosedAndEmptyPublicationIsUnknown() {
    var result =
        service(
                VH + row("../bad") + row("us"),
                CH + "us|tpr/wow|cdn.invalid\n",
                new AtomicInteger())
            .observeProduct("wow", CancellationToken.none());
    assertEquals(1, result.events().size());
    assertTrue(result.diagnostic().isPresent());
    assertTrue(result.diagnostic().orElseThrow().length() <= 4096);
    var empty =
        service(VH, CH, new AtomicInteger()).observeProduct("wow", CancellationToken.none());
    assertTrue(empty.events().isEmpty());
    assertTrue(empty.failures().isEmpty());
    assertTrue(empty.diagnostic().isPresent());
  }

  @Test
  void duplicateRegionRowsProduceOneOutcomeAndUnsupportedRoutesRemainObserved() {
    var result =
        service(
                VH + row("us") + row("us"),
                CH + "us|unsupported-path|cdn.invalid\n",
                new AtomicInteger())
            .observeProduct("wow", CancellationToken.none());
    assertEquals(1, result.events().size());
    assertEquals(2, result.events().getFirst().builds().size());
  }

  @Test
  void moreThan64ActualRegionsIsTypedLimitNotPartialCapture() {
    var versions = new StringBuilder(VH);
    var cdns = new StringBuilder(CH);
    for (int i = 0; i < 65; i++) {
      var region = "r" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
      versions.append(row(region));
      cdns.append(region + "|path|host\n");
    }
    assertEquals(
        DiscoveryHistoryException.Code.LIMIT_EXCEEDED,
        assertThrows(
                DiscoveryHistoryException.class,
                () ->
                    service(versions.toString(), cdns.toString(), new AtomicInteger())
                        .observeProduct("wow", CancellationToken.none()))
            .code());
  }

  @Test
  void invalidAndPrecancelledCallsDoNotRetrieve() {
    var calls = new AtomicInteger();
    var gateway = service(VH, CH, calls);
    assertThrows(CancellationException.class, () -> gateway.observeProduct("wow", () -> true));
    assertThrows(
        DiscoveryHistoryException.class,
        () -> gateway.observeProduct("../bad", CancellationToken.none()));
    assertEquals(0, calls.get());
  }

  @Test
  void malformedWholeDocumentFailsWithoutInventedRegionalEvents() {
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(
                DiscoveryHistoryException.class,
                () ->
                    service("bad", CH, new AtomicInteger())
                        .observeProduct("wow", CancellationToken.none()))
            .code());
  }

  @Test
  void productMappingSharesFourCaptureCapacityWithRegionalDiscovery() throws Exception {
    var entered = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            entered.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("mapping timeout");
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            return NOW;
          }
        };
    var gateway =
        new OfficialDiscoveryService(
            uri ->
                CompletableFuture.completedFuture(
                    new CapturedMetadata(
                        uri,
                        NOW.minusSeconds(1),
                        (uri.getPath().endsWith("versions")
                                ? VH + row("us")
                                : CH + "us|path|host\n")
                            .getBytes(StandardCharsets.UTF_8))),
            clock);
    try (var workers = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++)
        jobs.add(workers.submit(() -> gateway.observeProduct("wow", CancellationToken.none())));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(
            DiscoveryHistoryException.Code.BUSY,
            assertThrows(
                    DiscoveryHistoryException.class,
                    () -> gateway.observe("wow", "us", CancellationToken.none()))
                .code());
      } finally {
        release.countDown();
      }
      for (var job : jobs) job.get(3, TimeUnit.SECONDS);
      assertEquals(1, gateway.observeProduct("wow", CancellationToken.none()).events().size());
    }
  }

  @Test
  void canceledProductWaitsForBothIgnoredSourceFutures() throws Exception {
    var entered = new CountDownLatch(2);
    var canceled = new CountDownLatch(2);
    var stop = new AtomicBoolean();
    var requests = new CopyOnWriteArrayList<CompletableFuture<CapturedMetadata>>();
    var gateway =
        new OfficialDiscoveryService(
            uri -> {
              var future =
                  new CompletableFuture<CapturedMetadata>() {
                    public boolean cancel(boolean interrupt) {
                      canceled.countDown();
                      return false;
                    }
                  };
              requests.add(future);
              entered.countDown();
              return future;
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    try (var worker = Executors.newSingleThreadExecutor()) {
      var job = worker.submit(() -> gateway.observeProduct("wow", stop::get));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        stop.set(true);
        assertTrue(canceled.await(3, TimeUnit.SECONDS));
        assertFalse(job.isDone());
        requests.forEach(f -> f.completeExceptionally(new IllegalStateException("retired")));
        assertInstanceOf(
            CancellationException.class,
            assertThrows(ExecutionException.class, () -> job.get(3, TimeUnit.SECONDS)).getCause());
      } finally {
        requests.forEach(f -> f.completeExceptionally(new IllegalStateException("cleanup")));
      }
    }
  }

  @Test
  void substitutedCapturedRoleIsTypedCorruptionForEmptyAndPopulatedProduct() {
    for (boolean empty : List.of(false, true))
      for (String role : List.of("versions", "cdns")) {
        var gateway =
            new OfficialDiscoveryService(
                uri -> {
                  var source =
                      uri.getPath().endsWith(role)
                          ? java.net.URI.create(uri.toString().replace("/wow/", "/other/"))
                          : uri;
                  return CompletableFuture.completedFuture(
                      new CapturedMetadata(
                          source,
                          NOW.minusSeconds(1),
                          (uri.getPath().endsWith("versions")
                                  ? VH + (empty ? "" : row("us"))
                                  : CH + "us|path|host\n")
                              .getBytes(StandardCharsets.UTF_8)));
                },
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(
            DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
            assertThrows(
                    DiscoveryHistoryException.class,
                    () -> gateway.observeProduct("wow", CancellationToken.none()))
                .code());
      }
  }
}
