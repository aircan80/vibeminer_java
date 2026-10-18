package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.bootstrap.DefaultBootstrapService;
import io.vibeminer.application.lookup.DefaultFileLookupService;
import io.vibeminer.application.lookup.FileLookupService;
import io.vibeminer.application.selectedfile.DefaultSelectedFileService;
import io.vibeminer.application.selectedfile.SelectedFileService;
import io.vibeminer.infrastructure.acquisition.CdnMetadataTransport;
import io.vibeminer.infrastructure.acquisition.PinnedCdnRangeTransport;
import io.vibeminer.infrastructure.acquisition.PinnedCdnTransport;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.bootstrap.OfficialBootstrapArtifactGateway;
import io.vibeminer.infrastructure.lookup.OfficialFileLookupGateway;
import io.vibeminer.infrastructure.lookup.OfficialSelectedFileGateway;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Concrete content resources, initialized only by an explicit content action on a worker. */
final class ContentRuntime implements LazyBootstrapService.Resources {
  private final FileObjectStore store;
  private final HttpClient client;
  private final ExecutorService orchestration;
  private final ExecutorService acquisition;
  private final BootstrapService service;
  private final FileLookupService lookup;
  private final OfficialFileLookupGateway lookupGateway;
  private final SelectedFileService selectedFiles;
  private final OfficialSelectedFileGateway selectedGateway;
  private final AtomicBoolean closed = new AtomicBoolean();

  private ContentRuntime(
      FileObjectStore store,
      HttpClient client,
      ExecutorService orchestration,
      ExecutorService acquisition,
      BootstrapService service,
      FileLookupService lookup,
      OfficialFileLookupGateway lookupGateway,
      SelectedFileService selectedFiles,
      OfficialSelectedFileGateway selectedGateway) {
    this.store = store;
    this.client = client;
    this.orchestration = orchestration;
    this.acquisition = acquisition;
    this.service = service;
    this.lookup = lookup;
    this.lookupGateway = lookupGateway;
    this.selectedFiles = selectedFiles;
    this.selectedGateway = selectedGateway;
  }

  static ContentRuntime open(Path dataDirectory) {
    return open(
        dataDirectory,
        () ->
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
  }

  /** Composition seam for deterministic tests; this runtime owns the supplied client. */
  static ContentRuntime open(Path dataDirectory, Supplier<HttpClient> clientFactory) {
    var store = new FileObjectStore(dataDirectory.resolve("content"));
    HttpClient client = null;
    var orchestration = workers("vibeminer-content-jobs", 4);
    var acquisition = workers("vibeminer-content-transfers", 4);
    try {
      client = java.util.Objects.requireNonNull(clientFactory.get());
      var transport = new PinnedCdnTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      var sharedCatalog = new SqliteCatalog(dataDirectory.resolve("catalog.sqlite"));
      var catalog = new SqliteBuildObjectCatalog(sharedCatalog, store);
      var retention = new SqliteRetentionRepository(sharedCatalog);
      var gateway = new OfficialBootstrapArtifactGateway(store, transport, Clock.systemUTC());
      var service =
          new DefaultBootstrapService(
              gateway, catalog, store, orchestration, acquisition, retention);
      var rangeTransport =
          new PinnedCdnRangeTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      var lookupGateway =
          new OfficialFileLookupGateway(store, transport, rangeTransport, Clock.systemUTC());
      var lookup =
          new DefaultFileLookupService(
              lookupGateway, catalog, store, orchestration, acquisition, retention);
      var selectedCatalog = new SqliteSelectedFileCatalog(sharedCatalog, store);
      var metadataTransport =
          new CdnMetadataTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      var selectedGateway =
          new OfficialSelectedFileGateway(
              store, transport, rangeTransport, metadataTransport, Clock.systemUTC());
      var selectedFiles =
          new DefaultSelectedFileService(
              selectedGateway, catalog, selectedCatalog, retention, orchestration, acquisition);
      return new ContentRuntime(
          store,
          client,
          orchestration,
          acquisition,
          service,
          lookup,
          lookupGateway,
          selectedFiles,
          selectedGateway);
    } catch (RuntimeException | Error failure) {
      orchestration.shutdownNow();
      acquisition.shutdownNow();
      if (client != null) client.shutdownNow();
      try {
        store.close();
      } catch (RuntimeException suppressed) {
        failure.addSuppressed(suppressed);
      }
      throw failure;
    }
  }

  @Override
  public BootstrapService service() {
    return service;
  }

  @Override
  public FileLookupService lookup() {
    return lookup;
  }

  @Override
  public SelectedFileService selectedFiles() {
    return selectedFiles;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    service.close();
    lookup.close();
    selectedFiles.close();
    orchestration.shutdownNow();
    acquisition.shutdownNow();
    client.shutdownNow();
    var interrupted = new AtomicBoolean(Thread.interrupted());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      var jobsStopped = await(orchestration, deadline, interrupted);
      var transfersStopped = await(acquisition, deadline, interrupted);
      if (!jobsStopped || !transfersStopped)
        throw new IllegalStateException(
            "Content workers have not stopped; active storage remains protected");
      lookupGateway.close();
      selectedGateway.close();
      store.close();
    } finally {
      if (interrupted.get()) Thread.currentThread().interrupt();
    }
  }

  private static boolean await(ExecutorService executor, long deadline, AtomicBoolean interrupted) {
    while (!executor.isTerminated()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;
      try {
        if (executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return true;
      } catch (InterruptedException interruption) {
        interrupted.set(true);
      }
    }
    return true;
  }

  private static ExecutorService workers(String name, int count) {
    return new ThreadPoolExecutor(
        count,
        count,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32),
        Thread.ofPlatform().daemon().name(name + "-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }
}
