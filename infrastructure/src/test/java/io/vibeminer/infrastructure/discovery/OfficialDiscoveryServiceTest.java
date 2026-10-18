package io.vibeminer.infrastructure.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class OfficialDiscoveryServiceTest {
  private static final String A = "00000000000000000000000000000001";
  private static final String B = "00000000000000000000000000000002";
  private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
  private static final String HEADER =
      "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!STRING:0|ProductConfig!HEX:16\n";
  private static final String CDNS =
      "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\nus|tpr/wow|cdn.example.invalid\n";

  @Test
  void selectsOnlyRequestedRegionAndPreservesConfigIdentityDespiteBlankOptionalColumn() {
    var requested = new ArrayList<URI>();
    var service =
        service(
            HEADER
                + "eu|"
                + B
                + "|"
                + A
                + "||101|demo.101|\nus|"
                + A
                + "|"
                + B
                + "||100|demo.100|\n",
            CDNS,
            requested);
    var builds = service.refresh("wowf", "us").toCompletableFuture().join();
    assertEquals(1, builds.size());
    assertEquals("wowf", builds.getFirst().productId());
    assertEquals(A, builds.getFirst().buildConfig());
    assertEquals(B, builds.getFirst().cdnConfig());
    assertEquals("demo.100", builds.getFirst().versionLabel());
    assertFalse(builds.getFirst().fixture());
    assertEquals("100", builds.getFirst().evidence().upstreamBuildId());
    assertEquals(2, builds.getFirst().evidence().documents().size());
    assertEquals(
        "https://us.version.battle.net/v2/products/wowf/versions",
        builds.getFirst().evidence().documents().getFirst().sourceUrl());
    assertFalse(builds.getFirst().editionLabel().contains("Forever"));
    assertEquals(
        List.of(
            URI.create("https://us.version.battle.net/v2/products/wowf/versions"),
            URI.create("https://us.version.battle.net/v2/products/wowf/cdns")),
        requested);
    var observation = service.lastObservation().orElseThrow();
    assertEquals(NOW, observation.observedAt());
    assertEquals(64, observation.versions().sha256().length());
    assertEquals("", observation.versionsDocument().rows().getFirst().get("KeyRing"));
  }

  @Test
  void sameVersionLabelWithDifferentConfigsRemainsDistinctAndProductsNeverCollapse() {
    var service =
        service(
            HEADER + "us|" + A + "|" + B + "||100|same|\nus|" + B + "|" + B + "||100|same|\n",
            CDNS,
            new ArrayList<>());
    var builds = service.refresh("wow", "us").toCompletableFuture().join();
    assertEquals(2, builds.size());
    assertNotEquals(builds.get(0).snapshotId(), builds.get(1).snapshotId());
    var other = service.refresh("wowf", "us").toCompletableFuture().join();
    assertNotEquals(builds.getFirst().snapshotId(), other.getFirst().snapshotId());
  }

  @Test
  void neverFallsBackToAnotherRegionOrCdn() {
    var service = service(HEADER + "eu|" + A + "|" + B + "||100|same|\n", CDNS, new ArrayList<>());
    assertThrows(
        CompletionException.class, () -> service.refresh("wow", "us").toCompletableFuture().join());
    var noCdn =
        service(
            HEADER + "us|" + A + "|" + B + "||100|same|\n",
            CDNS.replace("us|", "eu|"),
            new ArrayList<>());
    assertThrows(
        CompletionException.class, () -> noCdn.refresh("wow", "us").toCompletableFuture().join());
  }

  @Test
  void conflictingOrDuplicateCdnRegionRowsAreRejectedInsteadOfSelectingArbitrarily() {
    for (var extra :
        List.of("us|another/path|other.example.invalid\n", "us|tpr/wow|cdn.example.invalid\n")) {
      var service =
          service(HEADER + "us|" + A + "|" + B + "||100|same|\n", CDNS + extra, new ArrayList<>());
      assertThrows(
          CompletionException.class,
          () -> service.refresh("wow", "us").toCompletableFuture().join());
    }
  }

  @Test
  void validatesInputBeforeMakingAnyRequest() {
    var requests = new ArrayList<URI>();
    var service = service(HEADER, CDNS, requests);
    for (var product : List.of("../wow", "wow/versions", "wow?x", "https://evil.invalid", "")) {
      assertThrows(
          CompletionException.class,
          () -> service.refresh(product, "us").toCompletableFuture().join());
    }
    assertThrows(
        CompletionException.class,
        () -> service.refresh("wow", "us/../eu").toCompletableFuture().join());
    assertTrue(requests.isEmpty());
  }

  @Test
  void rejectsMalformedIdentityAndRequiredColumns() {
    var badIdentity = service(HEADER + "us|bad|" + B + "||100|same|\n", CDNS, new ArrayList<>());
    assertThrows(
        CompletionException.class,
        () -> badIdentity.refresh("wow", "us").toCompletableFuture().join());
    var badHeader = service("Region!STRING:0\nus\n", CDNS, new ArrayList<>());
    assertThrows(
        CompletionException.class,
        () -> badHeader.refresh("wow", "us").toCompletableFuture().join());
  }

  @Test
  void cancellationStopsBothPendingRequestsAndReleasesRefreshSlot() {
    var pending = new ArrayList<CompletableFuture<CapturedMetadata>>();
    var service =
        new OfficialDiscoveryService(
            uri -> {
              var request = new CompletableFuture<CapturedMetadata>();
              pending.add(request);
              return request;
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    var jobs = new ArrayList<CompletableFuture<List<io.vibeminer.domain.BuildSnapshot>>>();
    for (int i = 0; i < 4; i++) jobs.add(service.refresh("wow", "us").toCompletableFuture());
    assertThrows(
        CompletionException.class, () -> service.refresh("wow", "us").toCompletableFuture().join());
    jobs.forEach(job -> job.cancel(true));
    assertTrue(pending.stream().allMatch(CompletableFuture::isCancelled));
    var next = service.refresh("wow", "us").toCompletableFuture();
    assertFalse(next.isDone());
    next.cancel(true);
  }

  @Test
  void canceledFacadeCannotFreeSlotWhileBothSourceFuturesIgnoreCancellation() {
    var pending = new ArrayList<CompletableFuture<CapturedMetadata>>();
    var uris = new ArrayList<URI>();
    var service =
        new OfficialDiscoveryService(
            uri -> {
              var request =
                  new CompletableFuture<CapturedMetadata>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                      return false;
                    }
                  };
              pending.add(request);
              uris.add(uri);
              return request;
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    for (int i = 0; i < 4; i++) service.refresh("wow", "us").toCompletableFuture().cancel(true);
    assertEquals(8, pending.size());
    assertThrows(
        CompletionException.class,
        () ->
            service
                .refresh("wow", "us")
                .toCompletableFuture()
                .orTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .join());
    assertEquals(8, pending.size(), "Canceled facades retain actual transport capacity");
    for (int i = 0; i < 2; i++)
      pending
          .get(i)
          .complete(
              new CapturedMetadata(
                  uris.get(i),
                  NOW,
                  (i == 0 ? HEADER + "us|" + A + "|" + B + "||100|same|\n" : CDNS)
                      .getBytes(StandardCharsets.UTF_8)));
    var next = service.refresh("wow", "us").toCompletableFuture();
    assertFalse(next.isDone());
    assertEquals(10, pending.size());
    next.cancel(true);
    for (int i = 2; i < pending.size(); i++)
      pending.get(i).completeExceptionally(new IllegalStateException("retired"));
  }

  @Test
  void secondFetchStartupFailureRetainsFirstIgnoredCancellationUntilActualRetirement() {
    var firstRequests = new ArrayList<CompletableFuture<CapturedMetadata>>();
    var service =
        new OfficialDiscoveryService(
            uri -> {
              if (uri.getPath().endsWith("/cdns"))
                throw new IllegalStateException("cannot start CDN request");
              var first =
                  new CompletableFuture<CapturedMetadata>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                      return false;
                    }
                  };
              firstRequests.add(first);
              return first;
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    var failed = new ArrayList<CompletableFuture<List<io.vibeminer.domain.BuildSnapshot>>>();
    for (int i = 0; i < 4; i++) failed.add(service.refresh("wow", "us").toCompletableFuture());
    assertTrue(failed.stream().noneMatch(CompletableFuture::isDone));
    assertThrows(
        CompletionException.class, () -> service.refresh("wow", "us").toCompletableFuture().join());
    assertEquals(4, firstRequests.size());
    firstRequests.forEach(f -> f.completeExceptionally(new IllegalStateException("retired")));
    failed.forEach(f -> assertThrows(CompletionException.class, f::join));
    var next = service.refresh("wow", "us").toCompletableFuture();
    assertEquals(5, firstRequests.size());
    firstRequests.getLast().completeExceptionally(new IllegalStateException("retired"));
    assertThrows(CompletionException.class, next::join);
  }

  @Test
  void capturedMetadataCannotBeChangedByCaller() {
    var bytes = "original".getBytes(StandardCharsets.UTF_8);
    var metadata =
        new CapturedMetadata(URI.create("https://us.version.battle.net/wow/versions"), NOW, bytes);
    bytes[0] = 'x';
    metadata.bytes()[0] = 'z';
    assertEquals("original", new String(metadata.bytes(), StandardCharsets.UTF_8));
  }

  private static OfficialDiscoveryService service(
      String versions, String cdns, List<URI> requested) {
    return new OfficialDiscoveryService(
        uri -> {
          requested.add(uri);
          return CompletableFuture.completedFuture(
              new CapturedMetadata(
                  uri,
                  NOW,
                  (uri.getPath().endsWith("/versions") ? versions : cdns)
                      .getBytes(StandardCharsets.UTF_8)));
        },
        Clock.fixed(NOW, ZoneOffset.UTC));
  }
}
