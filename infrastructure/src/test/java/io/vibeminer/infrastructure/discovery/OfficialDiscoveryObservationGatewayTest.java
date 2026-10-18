package io.vibeminer.infrastructure.discovery;

import static io.vibeminer.infrastructure.discovery.DiscoveryObservationMapperTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.discoveryhistory.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OfficialDiscoveryObservationGatewayTest {
  @Test
  void overlappingOutOfOrderCapturesReturnTheirOwnExactDocumentsAndEventIds() throws Exception {
    var pending = new ConcurrentHashMap<URI, CompletableFuture<CapturedMetadata>>();
    var entered = new CountDownLatch(4);
    var service =
        new OfficialDiscoveryService(
            uri -> {
              var future = new CompletableFuture<CapturedMetadata>();
              pending.put(uri, future);
              entered.countDown();
              return future;
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a =
          CompletableFuture.supplyAsync(
              () -> service.observe("wow", "us", CancellationToken.none()), workers);
      var b =
          CompletableFuture.supplyAsync(
              () -> service.observe("wowf", "us", CancellationToken.none()), workers);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        complete(pending, "wowf", VERSIONS.replace("same", "second-capture"));
        var second = b.get(5, TimeUnit.SECONDS);
        assertEquals("second-capture", second.builds().getFirst().build().versionLabel());
        assertFalse(a.isDone());
        complete(pending, "wow", VERSIONS);
        var first = a.get(5, TimeUnit.SECONDS);
        assertEquals("same", first.builds().getFirst().build().versionLabel());
        assertEquals("wow", first.productId());
        assertEquals("wowf", second.productId());
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(first, new DiscoveryObservationMapper().replay(first));
        assertEquals(second, new DiscoveryObservationMapper().replay(second));
        assertTrue(first.versions().source().sourceUrl().contains("/wow/"));
        assertTrue(second.versions().source().sourceUrl().contains("/wowf/"));
      } finally {
        pending
            .values()
            .forEach(f -> f.completeExceptionally(new IllegalStateException("retired")));
      }
    }
  }

  @Test
  void blockingGatewayRequestsBothCancellationsButDoesNotRetireIgnoredIo() throws Exception {
    var requests = new CopyOnWriteArrayList<CompletableFuture<CapturedMetadata>>();
    var entered = new CountDownLatch(2);
    var canceled = new CountDownLatch(2);
    var cancellation = new AtomicBoolean();
    var service =
        new OfficialDiscoveryService(
            uri -> {
              var future =
                  new CompletableFuture<CapturedMetadata>() {
                    @Override
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
      var operation =
          CompletableFuture.supplyAsync(
              () -> service.observe("wow", "us", cancellation::get), worker);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        cancellation.set(true);
        assertTrue(canceled.await(5, TimeUnit.SECONDS));
        assertFalse(operation.isDone(), "Actual gateway worker must wait for ignored source IO");
        requests.forEach(
            f -> f.completeExceptionally(new IllegalStateException("actual source retired")));
        assertInstanceOf(
            CancellationException.class,
            assertThrows(ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS))
                .getCause());
      } finally {
        requests.forEach(f -> f.completeExceptionally(new IllegalStateException("retired")));
      }
    }
  }

  @Test
  void repeatedIdenticalCaptureGetsNewEventWithoutInventingAvailability() {
    var service =
        new OfficialDiscoveryService(
            uri ->
                CompletableFuture.completedFuture(
                    new CapturedMetadata(
                        uri,
                        NOW.minusSeconds(1),
                        (uri.getPath().endsWith("/versions") ? VERSIONS : CDNS)
                            .getBytes(StandardCharsets.UTF_8))),
            Clock.fixed(NOW, ZoneOffset.UTC));
    var first = service.observe("wow", "us", CancellationToken.none());
    var second = service.observe("wow", "us", CancellationToken.none());
    assertNotEquals(first.eventId(), second.eventId());
    assertEquals(first.builds().getFirst().build(), second.builds().getFirst().build());
    assertEquals(first.versions(), second.versions());
    assertEquals(first.cdns(), second.cdns());
    assertFalse(first.cdns().columns().contains("Servers"));
  }

  @Test
  void preCanceledOrInvalidGatewayInputCannotStartRequests() {
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var service =
        new OfficialDiscoveryService(
            uri -> {
              count.incrementAndGet();
              throw new AssertionError();
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThrows(CancellationException.class, () -> service.observe("wow", "us", () -> true));
    assertEquals(
        DiscoveryHistoryException.Code.INVALID_REQUEST,
        assertThrows(
                DiscoveryHistoryException.class,
                () -> service.observe("../wow", "us", CancellationToken.none()))
            .code());
    assertEquals(0, count.get());
  }

  @Test
  void checkedRemoteRetrievalFailurePreservesCauseWithoutMislabelingLocalStorage() {
    var remote = new java.io.IOException("official metadata HTTP503");
    var service =
        new OfficialDiscoveryService(
            uri -> CompletableFuture.failedFuture(remote), Clock.fixed(NOW, ZoneOffset.UTC));
    var failure =
        assertThrows(
            CompletionException.class,
            () -> service.observe("wow", "us", CancellationToken.none()));
    assertSame(remote, failure.getCause());
  }

  @Test
  void completedSourcesCannotReleaseCapacityWhilePrivateMappingStillRuns() throws Exception {
    var entered = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    Clock blocked =
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
              if (!release.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Mapper not released");
            } catch (InterruptedException failed) {
              throw new AssertionError(failed);
            }
            return NOW;
          }
        };
    var service =
        new OfficialDiscoveryService(
            uri ->
                CompletableFuture.completedFuture(
                    new CapturedMetadata(
                        uri,
                        NOW,
                        (uri.getPath().endsWith("versions") ? VERSIONS : CDNS)
                            .getBytes(StandardCharsets.UTF_8))),
            blocked);
    try (var workers = Executors.newFixedThreadPool(5)) {
      var jobs = new ArrayList<CompletableFuture<DiscoveryEvent>>();
      try {
        for (int i = 0; i < 4; i++)
          jobs.add(
              CompletableFuture.supplyAsync(
                  () -> service.observe("wow", "us", CancellationToken.none()), workers));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var fifth =
            CompletableFuture.supplyAsync(
                () -> service.observe("wow", "us", CancellationToken.none()), workers);
        jobs.add(fifth);
        assertEquals(
            DiscoveryHistoryException.Code.BUSY,
            assertInstanceOf(
                    DiscoveryHistoryException.class,
                    assertThrows(ExecutionException.class, () -> fifth.get(1, TimeUnit.SECONDS))
                        .getCause())
                .code());
      } finally {
        release.countDown();
        for (var job : jobs) {
          try {
            job.get(5, TimeUnit.SECONDS);
          } catch (ExecutionException expectedCapacityRejection) {
            assertInstanceOf(DiscoveryHistoryException.class, expectedCapacityRejection.getCause());
          }
        }
      }
    }
  }

  @Test
  void publicCancellationCannotRetirePrivateMappingAndSuccessCallbacksSeeReleasedPermit()
      throws Exception {
    var pending =
        new java.util.concurrent.CopyOnWriteArrayList<
            Map.Entry<URI, CompletableFuture<CapturedMetadata>>>();
    var entered = new CountDownLatch(3);
    var release = new CountDownLatch(1);
    var ordinal = new java.util.concurrent.atomic.AtomicInteger();
    Clock clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            if (ordinal.incrementAndGet() <= 3) {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("Mapping not released");
              } catch (InterruptedException failed) {
                throw new AssertionError(failed);
              }
            }
            return NOW;
          }
        };
    var service =
        new OfficialDiscoveryService(
            uri -> {
              var future = new CompletableFuture<CapturedMetadata>();
              pending.add(Map.entry(uri, future));
              return future;
            },
            clock);
    var facades = new ArrayList<CompletableFuture<List<io.vibeminer.domain.BuildSnapshot>>>();
    var mappingJobs = new ArrayList<CompletableFuture<Void>>();
    try (var workers = Executors.newFixedThreadPool(3)) {
      try {
        for (int i = 0; i < 4; i++) facades.add(service.refresh("wow", "us").toCompletableFuture());
        for (int i = 0; i < 3; i++) {
          int first = i * 2;
          mappingJobs.add(
              CompletableFuture.runAsync(
                  () -> {
                    completeRequest(pending.get(first));
                    completeRequest(pending.get(first + 1));
                  },
                  workers));
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        facades.subList(0, 3).forEach(f -> assertTrue(f.cancel(true)));
        assertThrows(
            CompletionException.class,
            () -> service.refresh("wow", "us").toCompletableFuture().join());
        assertEquals(8, pending.size(), "Canceled mapping still occupies its three permits");
        var reentered =
            new java.util.concurrent.atomic.AtomicReference<
                CompletableFuture<List<io.vibeminer.domain.BuildSnapshot>>>();
        facades
            .get(3)
            .whenComplete(
                (value, failure) ->
                    reentered.set(service.refresh("wow", "us").toCompletableFuture()));
        completeRequest(pending.get(6));
        completeRequest(pending.get(7));
        facades.get(3).get(5, TimeUnit.SECONDS);
        assertNotNull(reentered.get());
        assertFalse(reentered.get().isDone(), "Success callback must observe its released permit");
        assertEquals(10, pending.size());
        reentered.get().cancel(true);
      } finally {
        release.countDown();
        for (var job : mappingJobs) job.get(5, TimeUnit.SECONDS);
        pending.forEach(
            entry -> entry.getValue().completeExceptionally(new IllegalStateException("retired")));
      }
    }
  }

  static void completeRequest(Map.Entry<URI, CompletableFuture<CapturedMetadata>> request) {
    request
        .getValue()
        .complete(
            new CapturedMetadata(
                request.getKey(),
                NOW,
                (request.getKey().getPath().endsWith("versions") ? VERSIONS : CDNS)
                    .getBytes(StandardCharsets.UTF_8)));
  }

  static void complete(
      Map<URI, CompletableFuture<CapturedMetadata>> pending, String product, String versions) {
    String base = "https://us.version.battle.net/v2/products/" + product;
    var v = URI.create(base + "/versions");
    var c = URI.create(base + "/cdns");
    pending
        .get(v)
        .complete(
            new CapturedMetadata(
                v, NOW.minusSeconds(2), versions.getBytes(StandardCharsets.UTF_8)));
    pending
        .get(c)
        .complete(
            new CapturedMetadata(c, NOW.minusSeconds(1), CDNS.getBytes(StandardCharsets.UTF_8)));
  }
}
