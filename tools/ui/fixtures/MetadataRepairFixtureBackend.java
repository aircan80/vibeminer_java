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

/** Authored original-A metadata through actual repair policy, proof replay, CAS and SQL audit. */
public final class MetadataRepairFixtureBackend implements AutoCloseable {
  public enum PayloadMode {
    SUCCESS,
    UNAVAILABLE,
    WRONG_BYTES,
    HOLD
  }

  public final MetadataRepairHeadClient head = new MetadataRepairHeadClient();
  public final AtomicInteger seedResponses = new AtomicInteger(),
      ordinaryResponses = new AtomicInteger(),
      payloadRequests = new AtomicInteger(),
      rootReads = new AtomicInteger(),
      installReads = new AtomicInteger();
  public final AtomicLong payloadBytes = new AtomicLong(), monotonic = new AtomicLong();
  public volatile PayloadMode payloadMode = PayloadMode.SUCCESS;
  public volatile CompletableFuture<?> heldPayload;
  private final Path contentRoot;
  private final FileObjectStore store;
  private final SqliteCatalog sql;
  private final SqliteBuildObjectCatalog builds;
  private final SqliteCanonicalContentRepository claims;
  private final SqliteMetadataAssociationRepository associations;
  private final SqliteMetadataRepairAuditRepository audits;
  private final OfficialFileLookupGateway roots;
  private final OfficialBootstrapArtifactGateway installs;
  private final OfficialMetadataRepairGateway issuer;
  private final OfficialMetadataRepairExecutionGateway execution;
  private final OfficialMetadataLocalVerificationGateway local;
  private final OfficialMetadataReuseGateway declarations;
  private final DefaultMetadataRepairService repairs;
  private final DefaultBootstrapService installService;
  private final DefaultFileLookupService rootService;
  private final ExecutorService jobs = Executors.newFixedThreadPool(4),
      transfers = Executors.newFixedThreadPool(2);
  private final MutableClock clock = new MutableClock();
  private final SyntheticLookupFixture fixture = new SyntheticLookupFixture();
  private final byte[] config, encodedInstall;
  private final BuildSnapshot build;
  private final WorkspaceService workspaces;
  private final boolean restart;
  private volatile boolean seeding = true;

  public MetadataRepairFixtureBackend(Path directory, boolean seed) throws Exception {
    restart = !seed;
    contentRoot = directory.resolve("content");
    store = new FileObjectStore(contentRoot);
    sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var session = store.contentSession();
    SqliteCanonicalContentRepository.recoverAtStartup(sql, session);
    builds = new SqliteBuildObjectCatalog(sql, store);
    claims = new SqliteCanonicalContentRepository(sql, store, session);
    associations = new SqliteMetadataAssociationRepository(sql, store, session);
    audits = new SqliteMetadataRepairAuditRepository(sql, store, session, clock);
    MetadataContentProof seededInstall = null;
    if (seed) seededInstall = MetadataReuseInstallSeed.acquire(store, seedResponses).proof();
    // Deterministic original encoding is read from the real verified acquisition object on seed,
    // or generated deterministically after restart and checked against the durable original claim.
    // No new acquisition receipt is fabricated.
    if (seed) {
      encodedInstall = readObject(seededInstall.object());
    } else {
      encodedInstall = authoredInstallEncoding();
    }
    String installContent = MetadataReuseInstallSeed.hash("MD5", decodedInstall());
    config =
        MetadataReuseInstallSeed.text(
            new String(fixture.config, java.nio.charset.StandardCharsets.UTF_8)
                + "install = "
                + installContent
                + " "
                + MetadataReuseInstallSeed.hash("MD5", encodedInstall)
                + "\ninstall-size = "
                + decodedInstall().length
                + " "
                + encodedInstall.length
                + "\n# authored ordinary A repair context\n");
    build = MetadataReuseInstallSeed.build(config, "DEMO-REPAIR-A");
    if (seed) registerConfiguration();
    var rootTransport =
        new LookupTransport() {
          public CompletableFuture<AcquiredBytes> configuration(CdnRequest r) {
            if (restart) throw new AssertionError("Offline restart fetched config");
            countOrdinary();
            return CompletableFuture.completedFuture(
                new AcquiredBytes(
                    r,
                    MetadataReuseInstallSeed.NOW,
                    config,
                    MetadataReuseInstallSeed.hash("SHA-256", config),
                    true));
          }

          public CompletableFuture<AcquiredRange> range(CdnRangeRequest r) {
            if (restart) throw new AssertionError("Offline restart requested EN/root ranges");
            countOrdinary();
            return fixture.range(r);
          }
        };
    roots = new OfficialFileLookupGateway(store, rootTransport, clock);
    installs = MetadataReuseInstallSeed.offlineGateway(store, new AtomicInteger());
    if (seed) {
      var plan =
          roots.prepareRoot(
              build,
              Optional.of(configuration()),
              LookupBudgets.defaults(),
              CancellationToken.none());
      var capture = new AtomicReference<MetadataContentProof>();
      roots.acquireRoot(
          new RootSelectionPlan(plan, plan.candidates().getFirst()),
          new MetadataPublicationObserver() {
            public void beforePromotion(MetadataContentProof p, CancellationToken t) {
              capture.set(p);
            }

            public void afterStructureValidation(MetadataContentProof p, CancellationToken t) {
              capture.set(p);
            }
          },
          CancellationToken.none());
      if (capture.get() == null || capture.get().structuralAttestation().isEmpty())
        throw new AssertionError("Root acquisition proof absent");
      registerClaim(capture.get());
      registerClaim(seededInstall);
    }
    for (var kind : MetadataStructureAttestation.Kind.values()) {
      var proof = original(kind);
      byte[] bytes =
          kind == MetadataStructureAttestation.Kind.ROOT ? fixture.encodedRoot : encodedInstall;
      if (proof.object().length() != bytes.length
          || !proof
              .object()
              .digest()
              .value()
              .equals(MetadataReuseInstallSeed.hash("SHA-256", bytes)))
        throw new AssertionError("Authored bytes differ from durable original physical identity");
    }
    seeding = false;
    clock.current = clock.current.plusSeconds(600);
    head.lengths.put(fixture.rootEKey, (long) fixture.encodedRoot.length);
    head.lengths.put(
        MetadataReuseInstallSeed.hash("MD5", encodedInstall), (long) encodedInstall.length);
    head.reject = restart;
    issuer =
        new OfficialMetadataRepairGateway(
            installs,
            roots,
            new CdnMetadataTransport(head, clock, Duration.ofSeconds(15)),
            clock,
            monotonic::get);
    execution =
        new OfficialMetadataRepairExecutionGateway(
            issuer,
            store,
            new LookupTransport() {
              public CompletableFuture<AcquiredBytes> configuration(CdnRequest r) {
                if (restart) throw new AssertionError("Offline restart attempted payload GET");
                if (r.kind() != CdnRequest.Kind.LOOSE_DATA
                    || !r.key().equals(MetadataReuseInstallSeed.hash("MD5", encodedInstall)))
                  throw new AssertionError("Repair requested wrong install object");
                payloadRequests.incrementAndGet();
                if (payloadMode == PayloadMode.UNAVAILABLE)
                  return CompletableFuture.failedFuture(
                      new AcquisitionException(
                          AcquisitionException.Reason.UNAVAILABLE,
                          "Authored exact object unavailable",
                          404));
                if (payloadMode == PayloadMode.HOLD) {
                  var held = new CompletableFuture<AcquiredBytes>();
                  heldPayload = held;
                  return held;
                }
                byte[] bytes = encodedInstall.clone();
                if (payloadMode == PayloadMode.WRONG_BYTES) bytes[bytes.length - 1] ^= 1;
                payloadBytes.addAndGet(bytes.length);
                return CompletableFuture.completedFuture(
                    new AcquiredBytes(
                        r,
                        clock.instant(),
                        bytes,
                        MetadataReuseInstallSeed.hash("SHA-256", bytes),
                        false));
              }

              public CompletableFuture<AcquiredRange> range(CdnRangeRequest r) {
                if (restart) throw new AssertionError("Offline restart attempted payload ranges");
                if (!r.encodedKey().equals(fixture.rootEKey)
                    || r.wholeEncodedLength() != fixture.encodedRoot.length)
                  throw new AssertionError("Repair requested wrong root object");
                payloadRequests.incrementAndGet();
                if (payloadMode == PayloadMode.UNAVAILABLE)
                  return CompletableFuture.failedFuture(
                      new AcquisitionException(
                          AcquisitionException.Reason.UNAVAILABLE,
                          "Authored exact range unavailable",
                          404));
                if (payloadMode == PayloadMode.HOLD) {
                  var held = new CompletableFuture<AcquiredRange>();
                  heldPayload = held;
                  return held;
                }
                byte[] bytes =
                    Arrays.copyOfRange(
                        fixture.encodedRoot,
                        Math.toIntExact(r.start()),
                        Math.toIntExact(r.start() + r.count()));
                if (payloadMode == PayloadMode.WRONG_BYTES) bytes[bytes.length - 1] ^= 1;
                payloadBytes.addAndGet(bytes.length);
                return CompletableFuture.completedFuture(
                    new AcquiredRange(
                        r,
                        r.uri(),
                        clock.instant(),
                        bytes,
                        MetadataReuseInstallSeed.hash("SHA-256", bytes)));
              }
            },
            clock,
            monotonic::get);
    local = new OfficialMetadataLocalVerificationGateway(store);
    declarations = new OfficialMetadataReuseGateway(store);
    repairs =
        new DefaultMetadataRepairService(
            issuer,
            execution,
            local,
            declarations,
            builds,
            claims,
            audits,
            associations,
            store,
            jobs,
            clock,
            monotonic::get);
    installService =
        new DefaultBootstrapService(
            installs, builds, store, jobs, transfers, null, claims, associations, declarations);
    rootService = new DefaultFileLookupService(roots, builds, store, jobs, transfers, null, claims);
    var repository = new SqliteWorkspaceRepository(sql);
    if (seed) {
      repository.save(
          Workspace.create(build, "enUS", "Synthetic original A repair", clock.instant()));
      repository.save(
          Workspace.create(build, "deDE", "Synthetic original A German view", clock.instant()));
    }
    workspaces = new DefaultWorkspaceService(repository, jobs, clock);
  }

  private void countOrdinary() {
    if (seeding) seedResponses.incrementAndGet();
    else ordinaryResponses.incrementAndGet();
  }

  public BuildSnapshot build() {
    return build;
  }

  public WorkspaceService workspaces() {
    return workspaces;
  }

  public MetadataRepairService repairs() {
    return repairs;
  }

  public BootstrapService installs() {
    return new BootstrapService() {
      public CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot b) {
        return installService.inspectConfiguration(b);
      }

      public CompletionStage<InstallInspection> download(InstallPlan p) {
        throw new AssertionError("Ordinary download is outside repair fixture");
      }

      public CompletionStage<InstallInspection> reopen(BuildSnapshot b) {
        installReads.incrementAndGet();
        return installService.reopen(b);
      }

      public void close() {}
    };
  }

  public FileLookupService roots() {
    return new FileLookupService() {
      public CompletionStage<RootPlan> planRoot(BuildSnapshot b, LookupBudgets l) {
        return rootService.planRoot(b, l);
      }

      public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan p) {
        throw new AssertionError("Ordinary retention is outside repair fixture");
      }

      public CompletionStage<RootLookupResult> lookupOffline(
          BuildSnapshot b, Set<Long> ids, LookupBudgets l) {
        rootReads.incrementAndGet();
        return rootService.lookupOffline(b, ids, l);
      }

      public void close() {}
    };
  }

  public InstallPlan ordinaryInstall() {
    return installService.inspectConfiguration(build).toCompletableFuture().join();
  }

  public RootSelectionPlan ordinaryRoot() {
    var plan = rootService.planRoot(build, LookupBudgets.defaults()).toCompletableFuture().join();
    return new RootSelectionPlan(plan, plan.candidates().getFirst());
  }

  public MetadataContentProof original(MetadataStructureAttestation.Kind kind) {
    String key =
        kind == MetadataStructureAttestation.Kind.ROOT
            ? fixture.rootCKey
            : MetadataReuseInstallSeed.hash("MD5", decodedInstall());
    return (MetadataContentProof)
        claims
            .lookup(
                new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", key),
                CancellationToken.none())
            .orElseThrow()
            .proof()
            .orElseThrow();
  }

  public Path path(MetadataStructureAttestation.Kind kind) {
    String hash = original(kind).object().digest().value();
    return contentRoot.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
  }

  public byte[] validBytes(MetadataStructureAttestation.Kind kind) {
    return kind == MetadataStructureAttestation.Kind.ROOT
        ? fixture.encodedRoot.clone()
        : encodedInstall.clone();
  }

  public void corrupt(MetadataStructureAttestation.Kind kind) throws Exception {
    byte[] bytes = validBytes(kind);
    bytes[bytes.length - 1] ^= 1;
    Files.write(path(kind), bytes);
  }

  public void remove(MetadataStructureAttestation.Kind kind) throws Exception {
    Files.delete(path(kind));
  }

  public void restore(MetadataStructureAttestation.Kind kind) throws Exception {
    Files.write(path(kind), validBytes(kind));
  }

  public void expire() {
    clock.current = clock.current.plus(Duration.ofHours(1));
    monotonic.addAndGet(Duration.ofHours(1).toNanos());
  }

  public Optional<MetadataRepairAudit> audit(UUID id) {
    return audits.find(id, CancellationToken.none());
  }

  public long count(String table) throws Exception {
    if (!Set.of(
            "metadata_repair_attempts",
            "metadata_repair_events",
            "metadata_build_associations",
            "source_aliases")
        .contains(table)) throw new IllegalArgumentException();
    try (var c = sql.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  private VerifiedSourceAlias configuration() {
    var r =
        builds
            .findVerifiedObject(build.snapshotId(), "BUILD_CONFIG", CancellationToken.none())
            .orElseThrow();
    return new VerifiedSourceAlias(r.source(), r.object(), r.evidence());
  }

  private void registerConfiguration() throws Exception {
    var o =
        store.publish(
            new ByteArrayInputStream(config),
            new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, build.buildConfig(), config.length),
            config.length,
            CancellationToken.none());
    builds.registerSnapshot(build);
    builds.attachVerifiedObject(
        build.snapshotId(),
        "BUILD_CONFIG",
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1"),
        o,
        new SourceVerificationEvidence(
            "tact-config-raw-md5-v1",
            CdnRequest.configuration(CapturedBuildSource.resolve(build), build.buildConfig())
                .uri()
                .toString(),
            MetadataReuseInstallSeed.NOW),
        CancellationToken.none());
  }

  private void registerClaim(MetadataContentProof p) {
    var t = CancellationToken.none();
    var c =
        claims.reserve(
            new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", p.contentKey()),
            p.actualEncodingKey(),
            p.object().length(),
            t);
    var w = claims.claimWorker(c.token(), t);
    claims.markPublishing(w, t);
    claims.recordVerifiedCompletion(w, p, t);
    claims.completePublication(w, p, t);
    claims.finishWorker(w, ContentWorkerFinishOutcome.RETAINED, t);
  }

  private byte[] readObject(StoredObject object) throws Exception {
    String h = object.digest().value();
    return Files.readAllBytes(
        contentRoot.resolve("objects").resolve(h.substring(0, 2)).resolve(h.substring(2)));
  }

  private static byte[] decodedInstall() {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeBytes("IN");
      out.writeByte(1);
      out.writeByte(16);
      out.writeShort(1);
      out.writeInt(2);
      out.write(MetadataReuseInstallSeed.text("enUS"));
      out.writeByte(0);
      out.writeShort(2);
      out.writeByte(0xc0);
      for (int i = 0; i < 2; i++) {
        out.write(
            MetadataReuseInstallSeed.text(
                i == 0 ? "Research/Authored-notes.txt" : "Research/Authored-chart.bin"));
        out.writeByte(0);
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) (i == 0 ? 0x11 : 0x22));
        out.write(key);
        out.writeInt(i == 0 ? 42 : 128);
      }
      return bytes.toByteArray();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static byte[] authoredInstallEncoding() {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeBytes("BLTE");
      out.writeInt(0);
      out.writeByte('N');
      out.write(decodedInstall());
      return bytes.toByteArray();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  public void close() throws Exception {
    repairs.close();
    rootService.close();
    installService.close();
    jobs.shutdownNow();
    transfers.shutdownNow();
    if (!jobs.awaitTermination(5, TimeUnit.SECONDS)
        || !transfers.awaitTermination(5, TimeUnit.SECONDS))
      throw new AssertionError("Repair workers not retired");
    issuer.close();
    roots.close();
    local.close();
    declarations.close();
    store.close();
  }

  private static final class MutableClock extends Clock {
    volatile Instant current = MetadataReuseInstallSeed.NOW.plusSeconds(600);

    public Instant instant() {
      return current;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }
  }
}
