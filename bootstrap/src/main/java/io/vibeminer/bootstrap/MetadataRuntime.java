package io.vibeminer.bootstrap;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildreadiness.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.infrastructure.discovery.OfficialDiscoveryService;
import io.vibeminer.infrastructure.discoveryhistory.*;
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
  private final BuildLibraryService library;
  private final BuildReadinessService readiness;
  private final DiscoveryService discovery;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object resumeMonitor = new Object();
  private final io.vibeminer.infrastructure.buildretention.SqliteDownloadAllPresence
      downloadAllPresence;
  private CompletableFuture<Boolean> resumeDownloads;
  private CompletableFuture<Boolean> pendingContentInitialization;

  private MetadataRuntime(
      Path catalogPath,
      HttpClient client,
      ExecutorService workers,
      DiscoveryHistoryService history,
      WorkspaceService workspaces,
      BuildLibraryService library) {
    downloadAllPresence =
        new io.vibeminer.infrastructure.buildretention.SqliteDownloadAllPresence(catalogPath);
    this.client = client;
    this.workers = workers;
    this.history = history;
    this.workspaces = workspaces;
    this.library = library;
    readiness =
        new DefaultBuildReadinessService(
            new io.vibeminer.infrastructure.buildreadiness.SqliteBuildReadinessRepository(
                catalogPath, BuildMiningRuntime.reservationPolicy()),
            workers);
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
    return open(dataDirectory, clients, decorator, gateway -> gateway);
  }

  /** Both observation facades borrow the same official source, client, catalog and worker. */
  static MetadataRuntime open(
      Path dataDirectory,
      Supplier<HttpClient> clients,
      UnaryOperator<DiscoveryObservationGateway> decorator,
      UnaryOperator<BuildLibraryProductGateway> productDecorator) {
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
      var historyRepository = new SqliteDiscoveryHistoryRepository(catalog);
      var history =
          new DefaultDiscoveryHistoryService(
              Objects.requireNonNull(decorator.apply(official)), historyRepository, workers);
      var libraryRepository = new SqliteBuildLibraryRepository(catalog);
      var library =
          new DefaultBuildLibraryService(
              Objects.requireNonNull(productDecorator.apply(official)),
              historyRepository,
              libraryRepository,
              libraryRepository,
              new SqliteBuildLibraryReader(catalog),
              io.vibeminer.application.discoveryhistory.DiscoveryHistoryLimits.defaults(),
              workers,
              Clock.systemUTC(),
              new io.vibeminer.infrastructure.discoveryhistory.SqliteLegacyBuildObservationImporter(
                  catalog));
      var workspaces =
          new DefaultWorkspaceService(
              new SqliteWorkspaceRepository(catalog), workers, Clock.systemUTC());
      return new MetadataRuntime(
          dataDirectory.resolve("catalog.sqlite"), client, workers, history, workspaces, library);
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

  BuildLibraryService library() {
    return library;
  }

  BuildReadinessService readiness() {
    return readiness;
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

  /**
   * One metadata-worker startup check; the borrowed content owner alone validates saved authority.
   * False means no stored Download all hint. True means owner initialization completed, not that
   * any download, scope readiness, or retention succeeded. Callers cannot cancel the owned single
   * flight.
   */
  CompletionStage<Boolean> resumeAuthorizedDownloads(LazyBootstrapService contentOwner) {
    Objects.requireNonNull(contentOwner);
    CompletableFuture<Boolean> result;
    synchronized (resumeMonitor) {
      if (resumeDownloads != null) return resumeDownloads.minimalCompletionStage();
      result = new CompletableFuture<>();
      resumeDownloads = result;
    }
    if (closed.get())
      result.completeExceptionally(new IllegalStateException("Metadata runtime closed"));
    else
      try {
        workers.execute(() -> checkAuthorizedDownloads(contentOwner, result));
      } catch (RuntimeException | Error rejected) {
        result.completeExceptionally(rejected);
      }
    return result.minimalCompletionStage();
  }

  private void checkAuthorizedDownloads(
      LazyBootstrapService contentOwner, CompletableFuture<Boolean> result) {
    try {
      requireResumeOpen();
      boolean present = downloadAllPresence.hasDownloadAll(closed::get);
      requireResumeOpen();
      if (!present) {
        result.complete(false);
        return;
      }
      CompletionStage<Boolean> initialized;
      synchronized (resumeMonitor) {
        requireResumeOpen();
        initialized =
            contentOwner.dispatchResources(
                resources ->
                    closed.get()
                        ? CompletableFuture.failedFuture(
                            new IllegalStateException("Metadata runtime closed"))
                        : CompletableFuture.completedFuture(true),
                Function.identity());
        pendingContentInitialization = initialized.toCompletableFuture();
      }
      initialized.whenComplete(
          (value, failure) -> {
            if (closed.get())
              result.completeExceptionally(new IllegalStateException("Metadata runtime closed"));
            else if (failure != null) result.completeExceptionally(failure);
            else result.complete(value);
          });
    } catch (Throwable failure) {
      result.completeExceptionally(failure);
    }
  }

  private void requireResumeOpen() {
    if (closed.get()) throw new IllegalStateException("Metadata runtime closed");
  }

  @Override
  public void close() {
    CompletableFuture<Boolean> pendingResume, pendingContent;
    synchronized (resumeMonitor) {
      if (!closed.compareAndSet(false, true)) return;
      pendingResume = resumeDownloads;
      pendingContent = pendingContentInitialization;
    }
    if (pendingResume != null)
      pendingResume.completeExceptionally(new IllegalStateException("Metadata runtime closed"));
    if (pendingContent != null) pendingContent.cancel(false);
    readiness.close();
    library.close();
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
