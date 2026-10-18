package io.vibeminer.bootstrap;

import io.vibeminer.application.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.discovery.OfficialDiscoveryService;
import io.vibeminer.infrastructure.discoveryhistory.SqliteDiscoveryHistoryRepository;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Metadata-only owner. Construction opens no database, content store or remote request. */
final class MetadataRuntime implements AutoCloseable {
  private final HttpClient client;
  private final ExecutorService workers;
  private final DiscoveryHistoryService history;
  private final WorkspaceService workspaces;
  private final DiscoveryService discovery;
  private final AtomicBoolean closed = new AtomicBoolean();

  private MetadataRuntime(
      HttpClient client,
      ExecutorService workers,
      DiscoveryHistoryService history,
      WorkspaceService workspaces) {
    this.client = client;
    this.workers = workers;
    this.history = history;
    this.workspaces = workspaces;
    discovery =
        new DiscoveryService() {
          public List<BuildSnapshot> fixtureBuilds() {
            return FixtureDiscovery.builds();
          }

          public CompletionStage<List<BuildSnapshot>> refresh(String productId, String region) {
            var observation = history.refreshObservation(productId, region);
            var projection = new CompletableFuture<List<BuildSnapshot>>();
            observation
                .completion()
                .whenComplete(
                    (event, failure) -> {
                      if (failure != null) projection.completeExceptionally(failure);
                      else
                        projection.complete(
                            event.builds().stream()
                                .map(DiscoveryEvent.ObservedBuild::build)
                                .toList());
                    });
            projection.whenComplete(
                (value, failure) -> {
                  if (projection.isCancelled()) observation.requestCancellation();
                });
            return projection;
          }
        };
  }

  static MetadataRuntime open(Path dataDirectory) {
    return open(
        dataDirectory,
        () ->
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
        gateway -> gateway);
  }

  /** Composition seam: the supplied client is owned; the gateway decorator adds no authority. */
  static MetadataRuntime open(
      Path dataDirectory,
      Supplier<HttpClient> clients,
      UnaryOperator<DiscoveryObservationGateway> decorator) {
    var workers =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            task -> {
              var thread = new Thread(task, "vibeminer-workspaces");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    HttpClient client = null;
    try {
      var catalog = new SqliteCatalog(dataDirectory.resolve("catalog.sqlite"));
      client = Objects.requireNonNull(clients.get());
      var official = new OfficialDiscoveryService(client);
      var history =
          new DefaultDiscoveryHistoryService(
              Objects.requireNonNull(decorator.apply(official)),
              new SqliteDiscoveryHistoryRepository(catalog),
              workers);
      var workspaces =
          new DefaultWorkspaceService(
              new SqliteWorkspaceRepository(catalog), workers, Clock.systemUTC());
      return new MetadataRuntime(client, workers, history, workspaces);
    } catch (RuntimeException | Error failure) {
      workers.shutdown();
      if (client != null)
        try {
          client.shutdownNow();
        } catch (RuntimeException suppressed) {
          failure.addSuppressed(suppressed);
        }
      throw failure;
    }
  }

  DiscoveryHistoryService history() {
    return history;
  }

  WorkspaceService workspaces() {
    return workspaces;
  }

  DiscoveryService discovery() {
    return discovery;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    history.close();
    // Preserve accepted queued runnables: each sees the closed service and settles without I/O.
    // shutdownNow would discard their commit-aware completion stages indefinitely.
    workers.shutdown();
    RuntimeException clientFailure = null;
    try {
      client.shutdownNow();
    } catch (RuntimeException failed) {
      clientFailure = failed;
    }
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      while (!workers.isTerminated()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          var failure =
              new IllegalStateException(
                  "Metadata workers have not retired; shutdown remains incomplete");
          if (clientFailure != null) failure.addSuppressed(clientFailure);
          throw failure;
        }
        try {
          workers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException failed) {
          interrupted = true;
        }
      }
      if (clientFailure != null) throw clientFailure;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
