package io.vibeminer.infrastructure.lookup;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.bootstrap.*;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real acquisition proofs and reuse stack. Only initial authored transport is synthetic. */
public final class MetadataReuseFixtureBackend implements AutoCloseable {
  public final AtomicInteger forbiddenTransfers = new AtomicInteger(),
      seedTransfers = new AtomicInteger(),
      checks = new AtomicInteger(),
      applies = new AtomicInteger(),
      rootReads = new AtomicInteger(),
      installReads = new AtomicInteger();
  public final AtomicBoolean holdCheck = new AtomicBoolean(), checkEntered = new AtomicBoolean();
  public final AtomicLong monotonic = new AtomicLong();
  private final ExecutorService jobs = Executors.newFixedThreadPool(4),
      acquisitions = Executors.newFixedThreadPool(2);
  private final FileObjectStore store;
  private final SqliteCatalog sql;
  private final SqliteBuildObjectCatalog builds;
  private final SqliteCanonicalContentRepository claims;
  private final SqliteMetadataAssociationRepository associations;
  private final OfficialMetadataReuseGateway local;
  private final OfficialFileLookupGateway rootGateway;
  private final OfficialBootstrapArtifactGateway installGateway;
  private final DefaultMetadataReuseService reuse;
  private final DefaultFileLookupService root;
  private final DefaultBootstrapService install;
  private final WorkspaceService workspaces;
  private final List<BuildSnapshot> snapshots;
  private final Path contentRoot;
  private final SyntheticLookupFixture rootFixture;
  private final Clock clock =
      Clock.fixed(MetadataReuseInstallSeed.NOW.plusSeconds(100), ZoneOffset.UTC);

  public MetadataReuseFixtureBackend(Path directory, boolean seed) throws Exception {
    contentRoot = directory.resolve("content");
    store = new FileObjectStore(contentRoot);
    sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var session = store.contentSession();
    SqliteCanonicalContentRepository.recoverAtStartup(sql, session);
    builds = new SqliteBuildObjectCatalog(sql, store);
    claims = new SqliteCanonicalContentRepository(sql, store, session);
    associations = new SqliteMetadataAssociationRepository(sql, store, session);
    rootFixture = new SyntheticLookupFixture();
    // Both generators are deterministic; on restart no acquisition is permitted.
    byte[] declaredInstall = MetadataReuseInstallSeed.alternateDeclaration();
    byte[] config =
        MetadataReuseInstallSeed.text(
            "root = "
                + rootFixture.rootCKey
                + "\n"
                + new String(declaredInstall, java.nio.charset.StandardCharsets.UTF_8)
                + "# authored reuse B\n");
    byte[] second =
        MetadataReuseInstallSeed.text(
            new String(config, java.nio.charset.StandardCharsets.UTF_8)
                + "# another exact snapshot\n");
    snapshots =
        List.of(
            MetadataReuseInstallSeed.build(config, "DEMO-REUSE-B"),
            MetadataReuseInstallSeed.build(second, "DEMO-REUSE-C"));
    MetadataReuseInstallSeed.Seed acquired = null;
    if (seed) {
      acquired = MetadataReuseInstallSeed.acquire(store, seedTransfers);
      try (var gateway =
          new OfficialFileLookupGateway(
              store, rootFixture, Clock.fixed(SyntheticLookupFixture.NOW, ZoneOffset.UTC))) {
        var plan =
            gateway.prepareRoot(
                rootFixture.build("us.cdn.blizzard.com", "DEMO-ORIGINAL-ROOT-A"),
                Optional.empty(),
                LookupBudgets.defaults(),
                CancellationToken.none());
        var captured = new AtomicReference<MetadataContentProof>();
        gateway.acquireRoot(
            new RootSelectionPlan(plan, plan.candidates().getFirst()),
            new MetadataPublicationObserver() {
              public void beforePromotion(MetadataContentProof p, CancellationToken t) {
                captured.set(p);
              }

              public void afterStructureValidation(MetadataContentProof p, CancellationToken t) {
                captured.set(p);
              }
            },
            CancellationToken.none());
        if (captured.get() == null || captured.get().structuralAttestation().isEmpty())
          throw new AssertionError("Root acquisition omitted actual grammar proof");
        registerClaim(captured.get());
      }
      registerClaim(acquired.proof());
      registerConfiguration(snapshots.get(0), config);
      registerConfiguration(snapshots.get(1), second);
    }
    seedTransfers.addAndGet(rootFixture.transportCalls.get());
    rootFixture.rejectNetwork = true;
    var repo = new SqliteWorkspaceRepository(sql);
    if (seed) {
      repo.save(Workspace.create(snapshots.get(0), "enUS", "Synthetic reuse B", clock.instant()));
      repo.save(Workspace.create(snapshots.get(1), "enUS", "Synthetic reuse C", clock.instant()));
      repo.save(
          Workspace.create(
              snapshots.get(0), "deDE", "Synthetic reuse B German view", clock.instant()));
    }
    workspaces = new DefaultWorkspaceService(repo, jobs, clock);
    local = new OfficialMetadataReuseGateway(store);
    reuse =
        new DefaultMetadataReuseService(
            local, builds, claims, associations, jobs, clock, monotonic::get);
    LookupTransport noWire =
        new LookupTransport() {
          public CompletableFuture<AcquiredBytes> configuration(CdnRequest r) {
            forbiddenTransfers.incrementAndGet();
            throw new AssertionError("Forbidden configuration transfer");
          }

          public CompletableFuture<AcquiredRange> range(CdnRangeRequest r) {
            forbiddenTransfers.incrementAndGet();
            throw new AssertionError("Forbidden metadata transfer");
          }
        };
    rootGateway = new OfficialFileLookupGateway(store, noWire, clock);
    root =
        new DefaultFileLookupService(rootGateway, builds, store, jobs, acquisitions, null, claims);
    installGateway = MetadataReuseInstallSeed.offlineGateway(store, forbiddenTransfers);
    install =
        new DefaultBootstrapService(
            installGateway, builds, store, jobs, acquisitions, null, claims, associations, local);
  }

  private void registerConfiguration(BuildSnapshot build, byte[] bytes) throws Exception {
    var object =
        store.publish(
            new ByteArrayInputStream(bytes),
            new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, build.buildConfig(), bytes.length),
            bytes.length,
            CancellationToken.none());
    var source =
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1");
    var evidence =
        new SourceVerificationEvidence(
            "tact-config-raw-md5-v1",
            CdnRequest.configuration(CapturedBuildSource.resolve(build), build.buildConfig())
                .uri()
                .toString(),
            MetadataReuseInstallSeed.NOW);
    builds.registerSnapshot(build);
    builds.attachVerifiedObject(
        build.snapshotId(), "BUILD_CONFIG", source, object, evidence, CancellationToken.none());
  }

  private void registerClaim(MetadataContentProof proof) {
    var token = CancellationToken.none();
    var claim =
        claims.reserve(
            new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", proof.contentKey()),
            proof.actualEncodingKey(),
            proof.object().length(),
            token);
    var worker = claims.claimWorker(claim.token(), token);
    claims.markPublishing(worker, token);
    claims.recordVerifiedCompletion(worker, proof, token);
    claims.completePublication(worker, proof, token);
    claims.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, token);
  }

  public List<BuildSnapshot> builds() {
    return snapshots;
  }

  public WorkspaceService workspaces() {
    return workspaces;
  }

  public MetadataReuseService reuse() {
    return new MetadataReuseService() {
      public CompletionStage<MetadataReusePlan> planRetained(
          BuildSnapshot b, MetadataStructureAttestation.Kind k, LookupBudgets budgets) {
        checks.incrementAndGet();
        return CompletableFuture.runAsync(
                () -> {
                  checkEntered.set(true);
                  while (holdCheck.get()) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    java.util.concurrent.locks.LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(10));
                  }
                },
                jobs)
            .thenCompose(v -> reuse.planRetained(b, k, budgets));
      }

      public CompletionStage<MetadataBuildAssociation> apply(MetadataReusePlan p) {
        applies.incrementAndGet();
        return reuse.apply(p);
      }

      public void close() {}
    };
  }

  public FileLookupService root() {
    return new FileLookupService() {
      public CompletionStage<RootPlan> planRoot(BuildSnapshot b, LookupBudgets l) {
        throw new AssertionError("No ordinary root plan in local-only fixture");
      }

      public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan p) {
        throw new AssertionError("No root acquisition in reuse workflow");
      }

      public CompletionStage<RootLookupResult> lookupOffline(
          BuildSnapshot b, Set<Long> ids, LookupBudgets l) {
        rootReads.incrementAndGet();
        return root.lookupOffline(b, ids, l);
      }

      public void close() {}
    };
  }

  public BootstrapService install() {
    return new BootstrapService() {
      public CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot b) {
        return install.inspectConfiguration(b);
      }

      public CompletionStage<InstallInspection> download(InstallPlan p) {
        throw new AssertionError("No install acquisition in reuse workflow");
      }

      public CompletionStage<InstallInspection> reopen(BuildSnapshot b) {
        installReads.incrementAndGet();
        return install.reopen(b);
      }

      public void close() {}
    };
  }

  public MetadataBuildAssociation association(MetadataStructureAttestation.Kind k) {
    return associations
        .find(snapshots.getFirst().snapshotId(), k, CancellationToken.none())
        .orElseThrow();
  }

  public Path actualPath(MetadataStructureAttestation.Kind k) {
    String hash = association(k).actualRepresentation().object().digest().value();
    return contentRoot.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
  }

  public boolean hasPreferredInstallAlias() throws Exception {
    String key =
        association(MetadataStructureAttestation.Kind.INSTALL)
            .declaration()
            .preferredEncodingKey()
            .orElseThrow();
    try (var connection = sql.connect();
        var query =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM source_aliases WHERE source_key LIKE ?")) {
      query.setString(1, "%;" + key + ";%");
      try (var rows = query.executeQuery()) {
        rows.next();
        return rows.getLong(1) != 0;
      }
    }
  }

  public long pinnedScopes() throws Exception {
    try (var connection = sql.connect();
        var query = connection.createStatement();
        var rows =
            query.executeQuery(
                "SELECT COUNT(*) FROM retention_scopes WHERE pinned=1 AND kind IN"
                    + " ('ROOT_METADATA','INSTALL_METADATA')")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  public long count(String table) throws Exception {
    if (!Set.of(
            "source_aliases",
            "retention_scopes",
            "metadata_build_associations",
            "build_object_memberships")
        .contains(table)) throw new IllegalArgumentException();
    try (var c = sql.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  public long objects() throws Exception {
    try (var paths = Files.walk(contentRoot.resolve("objects"))) {
      return paths.filter(Files::isRegularFile).count();
    }
  }

  public void close() throws Exception {
    holdCheck.set(false);
    reuse.close();
    root.close();
    install.close();
    jobs.shutdownNow();
    acquisitions.shutdownNow();
    if (!jobs.awaitTermination(5, TimeUnit.SECONDS)
        || !acquisitions.awaitTermination(5, TimeUnit.SECONDS))
      throw new AssertionError("Fixture workers not retired");
    rootGateway.close();
    local.close();
    store.close();
  }
}
