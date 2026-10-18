package io.vibeminer.bootstrap;

import io.vibeminer.application.ObjectStore;
import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.bootstrap.DefaultBootstrapService;
import io.vibeminer.application.content.DefaultMetadataRepairService;
import io.vibeminer.application.content.DefaultMetadataReuseService;
import io.vibeminer.application.content.MetadataRepairService;
import io.vibeminer.application.content.MetadataReuseGateway;
import io.vibeminer.application.content.MetadataReuseService;
import io.vibeminer.application.item.DefaultItemProjectionService;
import io.vibeminer.application.item.ItemProjectionBackend;
import io.vibeminer.application.item.ItemProjectionService;
import io.vibeminer.application.itemexport.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.application.lookup.DefaultFileLookupService;
import io.vibeminer.application.lookup.FileLookupService;
import io.vibeminer.application.selectedfile.DefaultSelectedFileService;
import io.vibeminer.application.selectedfile.SelectedFileService;
import io.vibeminer.infrastructure.acquisition.CdnMetadataTransport;
import io.vibeminer.infrastructure.acquisition.PinnedCdnRangeTransport;
import io.vibeminer.infrastructure.acquisition.PinnedCdnTransport;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.bootstrap.OfficialBootstrapArtifactGateway;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.content.SqliteMetadataAssociationRepository;
import io.vibeminer.infrastructure.content.SqliteMetadataRepairAuditRepository;
import io.vibeminer.infrastructure.item.RetainedItemProjectionSourceReader;
import io.vibeminer.infrastructure.item.SqliteItemEvidenceSnapshotBackend;
import io.vibeminer.infrastructure.item.SqliteItemNamesBackend;
import io.vibeminer.infrastructure.item.SqliteItemProjectionBackend;
import io.vibeminer.infrastructure.itemexport.AtomicItemEvidenceWriter;
import io.vibeminer.infrastructure.itemnames.RetainedSparseNameProjectionSourceReader;
import io.vibeminer.infrastructure.lookup.OfficialFileLookupGateway;
import io.vibeminer.infrastructure.lookup.OfficialMetadataLocalVerificationGateway;
import io.vibeminer.infrastructure.lookup.OfficialMetadataRepairExecutionGateway;
import io.vibeminer.infrastructure.lookup.OfficialMetadataRepairGateway;
import io.vibeminer.infrastructure.lookup.OfficialMetadataReuseGateway;
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
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Concrete content resources, initialized only by an explicit content action on a worker. */
final class ContentRuntime implements LazyBootstrapService.Resources {
  private final FileObjectStore store;
  private final HttpClient client;
  private final ExecutorService orchestration;
  private final ExecutorService acquisition;
  private final ExecutorService itemWorkers;
  private final ItemProjectionService itemProjection;
  private final ExecutorService metadataWorkers;
  private final MetadataReuseService metadataReuse;
  private final OfficialMetadataReuseGateway metadataGateway;
  private final ExecutorService repairWorkers;
  private final MetadataRepairService metadataRepair;
  private final OfficialMetadataRepairGateway repairIssuer;
  private final OfficialMetadataLocalVerificationGateway repairLocal;
  private final ExecutorService namesWorkers;
  private final ItemNamesService itemNames;
  private final ItemEvidenceService itemEvidence;
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
      OfficialSelectedFileGateway selectedGateway,
      ExecutorService itemWorkers,
      ItemProjectionService itemProjection,
      ExecutorService metadataWorkers,
      MetadataReuseService metadataReuse,
      OfficialMetadataReuseGateway metadataGateway,
      ExecutorService repairWorkers,
      MetadataRepairService metadataRepair,
      OfficialMetadataRepairGateway repairIssuer,
      OfficialMetadataLocalVerificationGateway repairLocal,
      ExecutorService namesWorkers,
      ItemNamesService itemNames,
      ItemEvidenceService itemEvidence) {
    this.itemEvidence = itemEvidence;
    this.namesWorkers = namesWorkers;
    this.itemNames = itemNames;
    this.repairWorkers = repairWorkers;
    this.metadataRepair = metadataRepair;
    this.repairIssuer = repairIssuer;
    this.repairLocal = repairLocal;
    this.store = store;
    this.client = client;
    this.orchestration = orchestration;
    this.acquisition = acquisition;
    this.service = service;
    this.lookup = lookup;
    this.lookupGateway = lookupGateway;
    this.selectedFiles = selectedFiles;
    this.selectedGateway = selectedGateway;
    this.itemWorkers = itemWorkers;
    this.itemProjection = itemProjection;
    this.metadataWorkers = metadataWorkers;
    this.metadataReuse = metadataReuse;
    this.metadataGateway = metadataGateway;
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
    return open(dataDirectory, clientFactory, (store, backend) -> backend);
  }

  /** Test composition seam; production retains the real bounded offline backend unchanged. */
  static ContentRuntime open(
      Path dataDirectory,
      Supplier<HttpClient> clientFactory,
      BiFunction<ObjectStore, ItemProjectionBackend, ItemProjectionBackend> itemDecorator) {
    return open(dataDirectory, clientFactory, itemDecorator, (store, gateway) -> gateway);
  }

  /** Local replay decorator for actual worker/lease lifecycle tests; production is identity. */
  static ContentRuntime open(
      Path dataDirectory,
      Supplier<HttpClient> clientFactory,
      BiFunction<ObjectStore, ItemProjectionBackend, ItemProjectionBackend> itemDecorator,
      BiFunction<ObjectStore, MetadataReuseGateway, MetadataReuseGateway> metadataDecorator) {
    return open(
        dataDirectory,
        clientFactory,
        itemDecorator,
        metadataDecorator,
        (store, backend) -> backend);
  }

  /** Names backend decorator only for actual-worker/lease lifecycle tests. */
  static ContentRuntime open(
      Path dataDirectory,
      Supplier<HttpClient> clientFactory,
      BiFunction<ObjectStore, ItemProjectionBackend, ItemProjectionBackend> itemDecorator,
      BiFunction<ObjectStore, MetadataReuseGateway, MetadataReuseGateway> metadataDecorator,
      BiFunction<ObjectStore, ItemNamesBackend, ItemNamesBackend> namesDecorator) {
    return open(
        dataDirectory,
        clientFactory,
        itemDecorator,
        metadataDecorator,
        namesDecorator,
        (store, backend) -> backend,
        writer -> writer);
  }

  /** Export lifecycle seams: borrowed snapshot dependency and stateless output writer only. */
  static ContentRuntime open(
      Path dataDirectory,
      Supplier<HttpClient> clientFactory,
      BiFunction<ObjectStore, ItemProjectionBackend, ItemProjectionBackend> itemDecorator,
      BiFunction<ObjectStore, MetadataReuseGateway, MetadataReuseGateway> metadataDecorator,
      BiFunction<ObjectStore, ItemNamesBackend, ItemNamesBackend> namesDecorator,
      BiFunction<ObjectStore, ItemEvidenceSnapshotBackend, ItemEvidenceSnapshotBackend>
          snapshotDecorator,
      UnaryOperator<ItemEvidenceWriter> writerDecorator) {
    var store = new FileObjectStore(dataDirectory.resolve("content"));
    var storeSession = store.contentSession();
    HttpClient client = null;
    OfficialMetadataReuseGateway metadataGateway = null;
    OfficialMetadataRepairGateway repairIssuer = null;
    OfficialMetadataLocalVerificationGateway repairLocal = null;
    var orchestration = workers("vibeminer-content-jobs", 4);
    var acquisition = workers("vibeminer-content-transfers", 4);
    var itemWorkers = workers("vibeminer-item-projection", 2);
    var metadataWorkers = workers("vibeminer-metadata-reuse", 2);
    var repairWorkers = workers("vibeminer-metadata-repair", 2);
    var namesWorkers = workers("vibeminer-item-names", 2);
    try {
      var sharedCatalog = new SqliteCatalog(dataDirectory.resolve("catalog.sqlite"));
      SqliteCanonicalContentRepository.recoverAtStartup(sharedCatalog, storeSession);
      var contentClaims = new SqliteCanonicalContentRepository(sharedCatalog, store, storeSession);
      client = java.util.Objects.requireNonNull(clientFactory.get());
      var transport = new PinnedCdnTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      var catalog = new SqliteBuildObjectCatalog(sharedCatalog, store);
      var retention = new SqliteRetentionRepository(sharedCatalog);
      var gateway = new OfficialBootstrapArtifactGateway(store, transport, Clock.systemUTC());
      var metadataAssociations =
          new SqliteMetadataAssociationRepository(sharedCatalog, store, storeSession);
      metadataGateway = new OfficialMetadataReuseGateway(store);
      var localMetadata =
          java.util.Objects.requireNonNull(metadataDecorator.apply(store, metadataGateway));
      var metadataReuse =
          new DefaultMetadataReuseService(
              localMetadata,
              catalog,
              contentClaims,
              metadataAssociations,
              metadataWorkers,
              Clock.systemUTC());
      var service =
          new DefaultBootstrapService(
              gateway,
              catalog,
              store,
              orchestration,
              acquisition,
              retention,
              contentClaims,
              metadataAssociations,
              localMetadata);
      var rangeTransport =
          new PinnedCdnRangeTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      var lookupGateway =
          new OfficialFileLookupGateway(store, transport, rangeTransport, Clock.systemUTC());
      var lookup =
          new DefaultFileLookupService(
              lookupGateway, catalog, store, orchestration, acquisition, retention, contentClaims);
      var selectedCatalog = new SqliteSelectedFileCatalog(sharedCatalog, store, storeSession);
      var metadataTransport =
          new CdnMetadataTransport(client, Clock.systemUTC(), Duration.ofSeconds(15));
      repairIssuer =
          new OfficialMetadataRepairGateway(
              gateway, lookupGateway, metadataTransport, Clock.systemUTC());
      repairLocal = new OfficialMetadataLocalVerificationGateway(store);
      var repairExecution =
          new OfficialMetadataRepairExecutionGateway(
              repairIssuer, store, transport, rangeTransport, Clock.systemUTC());
      var repairAudits =
          new SqliteMetadataRepairAuditRepository(sharedCatalog, store, storeSession);
      var metadataRepair =
          new DefaultMetadataRepairService(
              repairIssuer,
              repairExecution,
              repairLocal,
              localMetadata,
              catalog,
              contentClaims,
              repairAudits,
              metadataAssociations,
              store,
              repairWorkers,
              Clock.systemUTC());
      var selectedGateway =
          new OfficialSelectedFileGateway(
              store, transport, rangeTransport, metadataTransport, Clock.systemUTC());
      var selectedFiles =
          new DefaultSelectedFileService(
              selectedGateway, catalog, selectedCatalog, retention, orchestration, acquisition);
      var itemReader =
          new RetainedItemProjectionSourceReader(
              store, selectedCatalog, selectedGateway, Clock.systemUTC());
      var itemBackend = new SqliteItemProjectionBackend(sharedCatalog, storeSession, itemReader);
      var itemProjection =
          new DefaultItemProjectionService(
              java.util.Objects.requireNonNull(itemDecorator.apply(store, itemBackend)),
              itemWorkers);
      var sparseReader =
          new RetainedSparseNameProjectionSourceReader(
              store, selectedCatalog, selectedGateway, Clock.systemUTC());
      var namesBackend =
          new SqliteItemNamesBackend(sharedCatalog, storeSession, itemReader, sparseReader);
      var itemNames =
          new DefaultItemNamesService(
              java.util.Objects.requireNonNull(namesDecorator.apply(store, namesBackend)),
              namesWorkers);
      var snapshotBackend =
          new SqliteItemEvidenceSnapshotBackend(
              sharedCatalog, storeSession, itemReader, sparseReader, Clock.systemUTC());
      var evidenceWriter = new AtomicItemEvidenceWriter(Clock.systemUTC());
      var itemEvidence =
          new DefaultItemEvidenceService(
              java.util.Objects.requireNonNull(snapshotDecorator.apply(store, snapshotBackend)),
              java.util.Objects.requireNonNull(writerDecorator.apply(evidenceWriter)),
              namesWorkers);
      return new ContentRuntime(
          store,
          client,
          orchestration,
          acquisition,
          service,
          lookup,
          lookupGateway,
          selectedFiles,
          selectedGateway,
          itemWorkers,
          itemProjection,
          metadataWorkers,
          metadataReuse,
          metadataGateway,
          repairWorkers,
          metadataRepair,
          repairIssuer,
          repairLocal,
          namesWorkers,
          itemNames,
          itemEvidence);
    } catch (RuntimeException | Error failure) {
      orchestration.shutdownNow();
      acquisition.shutdownNow();
      itemWorkers.shutdownNow();
      metadataWorkers.shutdownNow();
      repairWorkers.shutdownNow();
      namesWorkers.shutdownNow();
      if (repairIssuer != null) repairIssuer.close();
      if (repairLocal != null) repairLocal.close();
      if (client != null) client.shutdownNow();
      if (metadataGateway != null) metadataGateway.close();
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
  public ItemProjectionService itemProjection() {
    return itemProjection;
  }

  @Override
  public MetadataReuseService metadataReuse() {
    return metadataReuse;
  }

  @Override
  public MetadataRepairService metadataRepair() {
    return metadataRepair;
  }

  @Override
  public ItemNamesService itemNames() {
    return itemNames;
  }

  @Override
  public ItemEvidenceService itemEvidence() {
    return itemEvidence;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    itemEvidence.close();
    itemNames.close();
    metadataRepair.close();
    metadataReuse.close();
    itemProjection.close();
    service.close();
    lookup.close();
    selectedFiles.close();
    orchestration.shutdownNow();
    acquisition.shutdownNow();
    itemWorkers.shutdownNow();
    metadataWorkers.shutdownNow();
    repairWorkers.shutdownNow();
    namesWorkers.shutdownNow();
    client.shutdownNow();
    var interrupted = new AtomicBoolean(Thread.interrupted());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try {
      var jobsStopped = await(orchestration, deadline, interrupted);
      var transfersStopped = await(acquisition, deadline, interrupted);
      var itemsStopped = await(itemWorkers, deadline, interrupted);
      var metadataStopped = await(metadataWorkers, deadline, interrupted);
      var repairsStopped = await(repairWorkers, deadline, interrupted);
      var namesStopped = await(namesWorkers, deadline, interrupted);
      if (!jobsStopped
          || !transfersStopped
          || !itemsStopped
          || !metadataStopped
          || !repairsStopped
          || !namesStopped)
        throw new IllegalStateException(
            "Content workers have not stopped; active storage remains protected");
      repairIssuer.close();
      repairLocal.close();
      metadataGateway.close();
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
