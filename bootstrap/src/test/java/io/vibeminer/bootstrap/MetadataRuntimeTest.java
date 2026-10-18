package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataRuntimeTest {
  @TempDir Path directory;
  static final DiscoveryHistoryFilter FILTER =
      new DiscoveryHistoryFilter("wow", Optional.of("us"), Optional.empty());

  @Test
  void constructionIsPureAndRetainedHistoryDoesNotTakeTheContentStoreLock() {
    var client = new Client();
    try (var runtime = MetadataRuntime.open(directory, () -> client, gateway -> gateway)) {
      assertFalse(Files.exists(directory.resolve("catalog.sqlite")));
      assertFalse(Files.exists(directory.resolve("content")));
      try (var held = new FileObjectStore(directory.resolve("content"))) {
        assertTrue(
            runtime
                .history()
                .history(FILTER, Optional.empty(), 25)
                .toCompletableFuture()
                .join()
                .events()
                .isEmpty());
        assertTrue(runtime.workspaces().list().toCompletableFuture().join().isEmpty());
      }
      assertEquals(0, client.requests.get());
    }
    assertEquals(1, client.closed.get());
  }

  @Test
  void legacyRefreshProjectsExactlyThePersistedEventAndRestartSelectionStaysOffline()
      throws Exception {
    var event = event();
    var calls = new AtomicInteger();
    var client = new Client();
    try (var runtime =
        MetadataRuntime.open(
            directory,
            () -> client,
            real ->
                (p, r, t) -> {
                  calls.incrementAndGet();
                  return event;
                })) {
      var builds = runtime.discovery().refresh("wow", "us").toCompletableFuture().join();
      assertEquals(1, calls.get());
      assertEquals(event.builds().getFirst().build(), builds.getFirst());
      var workspace =
          runtime
              .workspaces()
              .save(builds.getFirst(), "enUS", "Authored observation")
              .toCompletableFuture()
              .join();
      assertEquals(builds.getFirst().snapshotId(), workspace.build().snapshotId());
      var page =
          runtime.history().history(FILTER, Optional.empty(), 25).toCompletableFuture().join();
      assertEquals(event.eventId(), page.events().getFirst().eventId());
    }
    try (var runtime =
        MetadataRuntime.open(
            directory,
            Client::new,
            real ->
                (p, r, t) -> {
                  throw new AssertionError("Offline history cannot observe");
                })) {
      var row = event.builds().getFirst();
      var selected =
          runtime
              .history()
              .select(
                  new DiscoveryObservationSelection(
                      event.eventId(), row.build().snapshotId(), row.versionsRowOrdinal()))
              .toCompletableFuture()
              .join();
      assertEquals(event, selected.event());
      assertEquals(
          DiscoveryHistorySelection.RemoteContentAvailability.NOT_CHECKED,
          selected.remoteContentAvailability());
    }
  }

  @Test
  void closeWaitsForIgnoredSourceIoThenDrainsCancelledQueuedRefreshesWithoutDroppingTheirTerminals()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var client = new Client();
    var calls = new AtomicInteger();
    var runtime =
        MetadataRuntime.open(
            directory,
            () -> client,
            real ->
                (p, r, t) -> {
                  calls.incrementAndGet();
                  entered.countDown();
                  try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException failed) {
                    throw new AssertionError(failed);
                  }
                  return event();
                });
    var operations = new ArrayList<DiscoveryHistoryService.RefreshOperation>();
    for (int i = 0; i < 4; i++) operations.add(runtime.history().refreshObservation("wow", "us"));
    CompletableFuture<Void> closing = null;
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      closing = CompletableFuture.runAsync(runtime::close);
      assertTrue(client.stopping.await(2, TimeUnit.SECONDS));
      assertFalse(closing.isDone());
      for (var operation : operations)
        assertFalse(operation.completion().toCompletableFuture().isDone());
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      for (var operation : operations)
        assertThrows(
            CompletionException.class, () -> operation.completion().toCompletableFuture().join());
      assertEquals(1, calls.get());
      assertEquals(0, client.requests.get());
    } finally {
      release.countDown();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      runtime.close();
    }
  }

  @Test
  void failedCompositionClosesOwnedClientAndCanRetry() {
    var client = new Client();
    assertThrows(
        IllegalStateException.class,
        () ->
            MetadataRuntime.open(
                directory,
                () -> client,
                real -> {
                  throw new IllegalStateException("Decorator failure");
                }));
    assertEquals(1, client.closed.get());
    try (var runtime = MetadataRuntime.open(directory, Client::new, real -> real)) {
      assertNotNull(runtime.history());
    }
  }

  static DiscoveryEvent event() {
    String base = "https://us.version.battle.net/v2/products/wow/";
    var now = Instant.EPOCH;
    var versions =
        new SourceDocument(
            base + "versions",
            now,
            ("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
                    + "a".repeat(32)
                    + "|"
                    + "b".repeat(32)
                    + "|authored\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var cdns =
        new SourceDocument(
            base + "cdns",
            now,
            "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\nus|tpr/wow|authored.invalid\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new DiscoveryObservationMapper()
        .map(UUID.randomUUID(), "wow", "us", now, versions, cdns);
  }

  static final class Client extends HttpClient {
    final AtomicInteger requests = new AtomicInteger(), closed = new AtomicInteger();
    final CountDownLatch stopping = new CountDownLatch(1);

    public void shutdownNow() {
      closed.incrementAndGet();
      stopping.countDown();
    }

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.of(Duration.ofSeconds(1));
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      throw new AssertionError();
    }

    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    public Version version() {
      return Version.HTTP_1_1;
    }

    public Optional<Executor> executor() {
      return Optional.empty();
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      requests.incrementAndGet();
      throw new AssertionError("Unexpected HTTP");
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      requests.incrementAndGet();
      throw new AssertionError("Unexpected HTTP");
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      requests.incrementAndGet();
      throw new AssertionError("Unexpected HTTP");
    }
  }
}
