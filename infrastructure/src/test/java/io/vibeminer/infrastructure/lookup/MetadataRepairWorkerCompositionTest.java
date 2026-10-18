package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.bootstrap.OfficialBootstrapArtifactGateway;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual repair mechanics/audit composition only: no canonical fillMissing policy or runtime. */
class MetadataRepairWorkerCompositionTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Clock CLOCK = Clock.fixed(MetadataRepairPlanningTest.Fixture.NOW, ZoneOffset.UTC);

  static class Fixture implements AutoCloseable {
    final Path path;
    final FileObjectStore store;
    final SqliteCatalog sql;
    final SqliteCanonicalContentRepository claims;
    final SqliteMetadataRepairAuditRepository audits;
    final RootLookupFixture wire = new RootLookupFixture();
    final MetadataRepairPlanningTest.HeadClient head = new MetadataRepairPlanningTest.HeadClient();
    final OfficialFileLookupGateway roots;
    final OfficialMetadataRepairGateway issuer;
    final RootSelectionPlan selection;
    final MetadataContentProof original;
    final ContentClaim claim;
    final AtomicReference<MetadataRepairAudit> completedAudit = new AtomicReference<>();

    Fixture(Path path) throws Exception {
      this(path, 0);
    }

    Fixture(Path path, int history) throws Exception {
      this.path = path;
      store = new FileObjectStore(path.resolve("store"));
      sql = new SqliteCatalog(path.resolve("catalog.sqlite"));
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, cap);
      claims = new SqliteCanonicalContentRepository(sql, store, cap);
      audits = new SqliteMetadataRepairAuditRepository(sql, store, cap, CLOCK);
      roots = new OfficialFileLookupGateway(store, wire, CLOCK);
      var plan =
          roots.prepareRoot(
              wire.build("us.cdn.blizzard.com", "repair-authored"),
              Optional.empty(),
              LookupBudgets.defaults(),
              NONE);
      selection = new RootSelectionPlan(plan, plan.candidates().getFirst());
      var captured = new AtomicReference<MetadataContentProof>();
      roots.acquireRoot(
          selection,
          new MetadataPublicationObserver() {
            public void beforePromotion(MetadataContentProof value, CancellationToken token) {
              captured.set(value);
            }

            public void afterStructureValidation(
                MetadataContentProof value, CancellationToken token) {
              captured.set(value);
            }
          },
          NONE);
      var authenticated = captured.get();
      assertNotNull(authenticated.structuralAttestation().orElseThrow());
      // Model older durable capability inventory without inventing physical identity or a new
      // acquisition.
      // 1 retains only a prospective receipt; 2 preserves an explicitly historical attestation
      // revision.
      original =
          history == 1
              ? new MetadataContentProof(
                  authenticated.originalAlias(),
                  authenticated.actualEncodingKey(),
                  authenticated.declaredContentKey(),
                  authenticated.declaredDecodedBytes(),
                  Optional.empty(),
                  Optional.empty())
              : history == 2
                  ? new MetadataContentProof(
                      authenticated.originalAlias(),
                      authenticated.actualEncodingKey(),
                      authenticated.declaredContentKey(),
                      authenticated.declaredDecodedBytes(),
                      Optional.of(
                          new DecodedContentAttestation(
                              authenticated.contentKey(),
                              authenticated.decodedAttestation().orElseThrow().actualDecodedBytes(),
                              "historical-decoded-v0")),
                      Optional.of(
                          new MetadataStructureAttestation(
                              MetadataStructureAttestation.Kind.ROOT, "historical-root-v0")))
                  : authenticated;
      var builds = new SqliteBuildObjectCatalog(sql, store);
      builds.registerSnapshot(plan.build());
      var cfg = plan.configuration();
      builds.attachVerifiedObject(
          plan.build().snapshotId(),
          "BUILD_CONFIG",
          cfg.source(),
          cfg.object(),
          cfg.evidence(),
          NONE);
      var slot = new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", original.contentKey());
      var pending =
          claims.reserve(slot, original.actualEncodingKey(), original.encodedBytes(), NONE);
      var permit = claims.claimWorker(pending.token(), NONE);
      claims.markPublishing(permit, NONE);
      claims.recordVerifiedCompletion(permit, original, NONE);
      if (history != 1) claims.completePublication(permit, original, NONE);
      claims.finishWorker(
          permit,
          history == 1 ? ContentWorkerFinishOutcome.UNCERTAIN : ContentWorkerFinishOutcome.RETAINED,
          NONE);
      claim = claims.lookup(slot, NONE).orElseThrow();
      head.length = original.encodedBytes();
      var installs =
          new OfficialBootstrapArtifactGateway(
              store, new PinnedCdnTransport(head, CLOCK, Duration.ofSeconds(15)), CLOCK);
      issuer =
          new OfficialMetadataRepairGateway(
              installs,
              roots,
              new CdnMetadataTransport(head, CLOCK, Duration.ofSeconds(15)),
              CLOCK,
              System::nanoTime);
    }

    Path objectPath() {
      var h = original.object().digest().value();
      return path.resolve("store/objects").resolve(h.substring(0, 2)).resolve(h.substring(2));
    }

    MetadataRepairPlan issueMissing() throws Exception {
      Files.delete(objectPath());
      return issuer.prepareRoot(selection, claim, NONE);
    }

    OfficialMetadataRepairExecutionGateway executor(LookupTransport transport) {
      return new OfficialMetadataRepairExecutionGateway(issuer, store, transport, CLOCK);
    }

    public void close() {
      issuer.close();
      roots.close();
      store.close();
    }
  }

  static long count(SqliteCatalog sql, String table) throws Exception {
    try (var c = sql.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(r.next());
      return r.getLong(1);
    }
  }

  static MetadataRepairObserver observer(Fixture f, ContentWorkerPermit permit, UUID attempt) {
    return new MetadataRepairObserver() {
      public void beforePromotion(MetadataRepairObservation value, CancellationToken token) {
        assertTrue(value.structuralAttestation().isEmpty());
        assertFalse(Files.exists(f.objectPath()));
        f.audits.recordProspective(permit, attempt, value, token);
        assertEquals(
            List.of(MetadataRepairAuditEvent.Kind.PROSPECTIVE),
            f.audits.find(attempt, NONE).orElseThrow().events().stream()
                .map(MetadataRepairAuditEvent::kind)
                .toList());
      }

      public void afterStructureValidation(
          MetadataRepairObservation value, CancellationToken token) {
        assertTrue(Files.exists(f.objectPath()));
        f.audits.recordStructure(permit, attempt, value, token);
      }
    };
  }

  @Test
  void missingRootRepairsThroughDurableGatesWithoutChangingCanonicalHistory() throws Exception {
    try (var f = new Fixture(directory)) {
      long aliases = count(f.sql, "source_aliases"),
          members = count(f.sql, "build_object_memberships"),
          pins = count(f.sql, "retention_scopes");
      var plan = f.issueMissing();
      assertEquals(1, f.head.requests.size());
      var permit = f.claims.claimWorker(f.claim.token(), NONE);
      var attempt = f.audits.begin(permit, plan, NONE);
      var value =
          f.executor(f.wire)
              .repairRoot(f.selection, plan, attempt, observer(f, permit, attempt), NONE);
      var complete = f.audits.complete(permit, attempt, value, NONE);
      assertEquals(
          List.of(
              MetadataRepairAuditEvent.Kind.PROSPECTIVE,
              MetadataRepairAuditEvent.Kind.STRUCTURE,
              MetadataRepairAuditEvent.Kind.COMPLETED),
          complete.events().stream().map(MetadataRepairAuditEvent::kind).toList());
      f.claims.finishWorker(permit, ContentWorkerFinishOutcome.RETAINED, NONE);
      assertEquals(f.claim, f.claims.lookup(f.claim.slot(), NONE).orElseThrow());
      assertEquals(f.original, value.originalProof());
      assertEquals(aliases, count(f.sql, "source_aliases"));
      assertEquals(members, count(f.sql, "build_object_memberships"));
      assertEquals(pins, count(f.sql, "retention_scopes"));
      f.wire.rejectNetwork = true;
      assertEquals(
          2,
          f.roots
              .lookupRetained(
                  f.selection.plan().build(),
                  f.selection.plan().configuration(),
                  f.original.originalAlias(),
                  Set.of(42L),
                  LookupBudgets.defaults(),
                  NONE)
              .variants()
              .size());
    }
  }

  @Test
  void persistedProspectiveThenThrowForbidsPromotionAndSurvivesRestartWithoutNewOwnerFinalization()
      throws Exception {
    UUID attempt;
    ContentWorkerPermit oldPermit;
    MetadataRepairPlan plan;
    MetadataRepairObservation prospective;
    try (var f = new Fixture(directory)) {
      plan = f.issueMissing();
      oldPermit = f.claims.claimWorker(f.claim.token(), NONE);
      attempt = f.audits.begin(oldPermit, plan, NONE);
      var captured = new AtomicReference<MetadataRepairObservation>();
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  f.executor(f.wire)
                      .repairRoot(
                          f.selection,
                          plan,
                          attempt,
                          new MetadataRepairObserver() {
                            public void beforePromotion(
                                MetadataRepairObservation value, CancellationToken token) {
                              f.audits.recordProspective(oldPermit, attempt, value, token);
                              captured.set(value);
                              throw new IllegalStateException("Simulated durable gate exit");
                            }

                            public void afterStructureValidation(
                                MetadataRepairObservation value, CancellationToken token) {
                              fail("No promotion allowed");
                            }
                          },
                          NONE));
      assertFalse(Files.exists(f.objectPath()));
      assertNotNull(captured.get());
      prospective = captured.get();
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      // Crash gap: IO has retired, but no audit-fail or nonce-finalize call runs before store
      // closes.
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, cap);
      var claims = new SqliteCanonicalContentRepository(sql, store, cap);
      var audits = new SqliteMetadataRepairAuditRepository(sql, store, cap, CLOCK);
      assertEquals(
          List.of(MetadataRepairAuditEvent.Kind.PROSPECTIVE),
          audits.find(attempt, NONE).orElseThrow().events().stream()
              .map(MetadataRepairAuditEvent::kind)
              .toList());
      var completeObservation =
          new MetadataRepairObservation(
              prospective.repairAttemptId(),
              prospective.originalProof(),
              prospective.actualTransfer(),
              prospective.decodedAttestation(),
              plan.originalProof().structuralAttestation(),
              prospective.payloadRequestCount(),
              prospective.payloadResponseBytes(),
              prospective.observationDigest());
      assertTrue(completeObservation.structuralAttestation().isPresent());
      assertThrows(
          ContentClaimException.class,
          () -> audits.recordStructure(oldPermit, attempt, completeObservation, NONE));
      var replacement = claims.claimWorker(plan.reservationToken(), NONE);
      assertNotEquals(oldPermit.workerNonce(), replacement.workerNonce());
      assertThrows(
          ContentClaimException.class,
          () -> audits.recordStructure(replacement, attempt, completeObservation, NONE));
      claims.finishWorker(replacement, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
      assertEquals(
          plan.originalProof(),
          claims.lookup(plan.request().slot(), NONE).orElseThrow().proof().orElseThrow());
      assertEquals(0, count(sql, "retention_scopes"));
    }
  }

  @Test
  void
      cancelledCallerInterestCannotRetireWorkerOrAdmitAnotherProducerWhileSourceIgnoresCancellation()
          throws Exception {
    try (var f = new Fixture(directory)) {
      var plan = f.issueMissing();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var retired = new CountDownLatch(1);
      var cancellation = new AtomicBoolean();
      var failure = new AtomicReference<Throwable>();
      var permit = f.claims.claimWorker(f.claim.token(), NONE);
      var attempt = f.audits.begin(permit, plan, NONE);
      var held =
          new LookupTransport() {
            public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
              throw new AssertionError("No config repair transfer");
            }

            public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
              entered.countDown();
              boolean interrupted = false;
              while (true)
                try {
                  release.await();
                  break;
                } catch (InterruptedException e) {
                  interrupted = true;
                }
              if (interrupted) Thread.currentThread().interrupt();
              return f.wire.range(request);
            }
          };
      var worker =
          new Thread(
              () -> {
                try {
                  f.executor(held)
                      .repairRoot(
                          f.selection,
                          plan,
                          attempt,
                          observer(f, permit, attempt),
                          cancellation::get);
                  fail("Cancellation cannot complete repair");
                } catch (MetadataAcquisitionFailure e) {
                  failure.set(e);
                  f.audits.fail(permit, attempt, e.publication(), NONE);
                } finally {
                  f.claims.finishWorker(
                      permit, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
                  retired.countDown();
                }
              });
      worker.start();
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        cancellation.set(true);
        worker.interrupt();
        assertEquals(
            ContentClaimException.Code.BUSY,
            assertThrows(
                    ContentClaimException.class, () -> f.claims.claimWorker(f.claim.token(), NONE))
                .code());
        assertEquals(1, retired.getCount());
        assertTrue(f.audits.find(attempt, NONE).orElseThrow().events().isEmpty());
      } finally {
        release.countDown();
        worker.join(3000);
      }
      assertFalse(worker.isAlive());
      assertNotNull(failure.get());
      assertEquals(0, retired.getCount());
      assertEquals(f.claim, f.claims.lookup(f.claim.slot(), NONE).orElseThrow());
      var next = f.claims.claimWorker(f.claim.token(), NONE);
      f.claims.finishWorker(next, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
    }
  }

  @Test
  void corruptInstallUsesActualAcquisitionProofAndAuditWithoutCanonicalUpgrade() throws Exception {
    try (var f = new Fixture(directory)) {
      var seed = seedInstall(f, false);
      var encoded = seed.encoded();
      var ordinary = seed.ordinary();
      var original = seed.original();
      var old = seed.claim();
      var cfg = seed.config();
      var installs = seed.gateway();
      var client = seed.client();
      var slot = old.slot();
      String sha = original.object().digest().value();
      Path physical =
          directory.resolve("store/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
      byte[] corrupt = encoded.clone();
      corrupt[corrupt.length - 1] ^= 1;
      Files.write(physical, corrupt);
      long aliases = count(f.sql, "source_aliases"),
          members = count(f.sql, "build_object_memberships");
      f.head.length = encoded.length;
      try (var issuer =
          new OfficialMetadataRepairGateway(
              installs,
              f.roots,
              new CdnMetadataTransport(f.head, CLOCK, Duration.ofSeconds(15)),
              CLOCK,
              System::nanoTime)) {
        var issued = issuer.prepareInstall(ordinary, cfg, old, NONE);
        var permit = f.claims.claimWorker(old.token(), NONE);
        var attempt = f.audits.begin(permit, issued, NONE);
        var prospective = new AtomicReference<MetadataRepairObservation>();
        var wire =
            new LookupTransport() {
              public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
                throw new AssertionError("Install repair is one whole object");
              }

              public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
                return CompletableFuture.completedFuture(
                    new AcquiredBytes(
                        request,
                        CLOCK.instant(),
                        encoded,
                        java.util.HexFormat.of().formatHex(sha(encoded)),
                        false));
              }
            };
        var executor = new OfficialMetadataRepairExecutionGateway(issuer, f.store, wire, CLOCK);
        var result =
            executor.repairInstall(
                ordinary,
                issued,
                attempt,
                new MetadataRepairObserver() {
                  public void beforePromotion(
                      MetadataRepairObservation value, CancellationToken token) {
                    assertArrayEquals(corrupt, read(physical));
                    assertTrue(value.structuralAttestation().isPresent());
                    f.audits.recordProspective(permit, attempt, value, token);
                    prospective.set(value);
                  }

                  public void afterStructureValidation(
                      MetadataRepairObservation value, CancellationToken token) {
                    assertEquals(prospective.get(), value);
                    assertArrayEquals(encoded, read(physical));
                  }
                },
                NONE);
        var audit = f.audits.complete(permit, attempt, result, NONE);
        assertEquals(
            List.of(
                MetadataRepairAuditEvent.Kind.PROSPECTIVE, MetadataRepairAuditEvent.Kind.COMPLETED),
            audit.events().stream().map(MetadataRepairAuditEvent::kind).toList());
        f.claims.finishWorker(permit, ContentWorkerFinishOutcome.RETAINED, NONE);
        assertEquals(old, f.claims.lookup(slot, NONE).orElseThrow());
        assertEquals(original, result.originalProof());
        assertEquals(aliases, count(f.sql, "source_aliases"));
        assertEquals(members, count(f.sql, "build_object_memberships"));
        assertEquals(0, count(f.sql, "retention_scopes"));
        assertEquals(2, client.requests.size());
      }
    }
  }

  record InstallSeed(
      byte[] encoded,
      io.vibeminer.application.bootstrap.InstallPlan ordinary,
      VerifiedSourceAlias config,
      MetadataContentProof original,
      ContentClaim claim,
      OfficialBootstrapArtifactGateway gateway,
      PayloadClient client) {}

  static InstallSeed seedInstall(Fixture f, boolean historical) throws Exception {
    byte[] decoded = new byte[] {'I', 'N', 1, 16, 0, 0, 0, 0, 0, 0};
    var bytes = new java.io.ByteArrayOutputStream();
    var data = new java.io.DataOutputStream(bytes);
    data.writeBytes("BLTE");
    data.writeInt(0);
    data.writeByte('N');
    data.write(decoded);
    byte[] encoded = bytes.toByteArray();
    var hash = java.security.MessageDigest.getInstance("MD5");
    String ckey = java.util.HexFormat.of().formatHex(hash.digest(decoded)),
        ekey = java.util.HexFormat.of().formatHex(hash.digest(encoded));
    byte[] config =
        ("install = "
                + ckey
                + " "
                + ekey
                + "\ninstall-size = "
                + decoded.length
                + " "
                + encoded.length
                + "\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var build = MetadataRepairPlanningTest.build(config, "us.cdn.blizzard.com");
    var client = new PayloadClient();
    client.payloads.put(build.buildConfig(), config);
    client.payloads.put(ekey, encoded);
    var installs =
        new OfficialBootstrapArtifactGateway(
            f.store, new PinnedCdnTransport(client, CLOCK, Duration.ofSeconds(15)), CLOCK);
    var ordinary = installs.inspectConfiguration(build, NONE);
    var acquired = new AtomicReference<MetadataContentProof>();
    installs.acquireInstall(
        ordinary,
        new MetadataPublicationObserver() {
          public void beforePromotion(MetadataContentProof proof, CancellationToken token) {
            acquired.set(proof);
          }

          public void afterStructureValidation(
              MetadataContentProof proof, CancellationToken token) {
            fail("Install grammar is already complete at first gate");
          }
        },
        NONE);
    assertEquals(2, client.requests.size());
    var acquiredProof = acquired.get();
    var original =
        historical
            ? new MetadataContentProof(
                acquiredProof.originalAlias(),
                acquiredProof.actualEncodingKey(),
                acquiredProof.declaredContentKey(),
                acquiredProof.declaredDecodedBytes(),
                Optional.of(
                    new DecodedContentAttestation(
                        acquiredProof.contentKey(),
                        acquiredProof.decodedAttestation().orElseThrow().actualDecodedBytes(),
                        "historical-install-decoded-v0")),
                Optional.of(
                    new MetadataStructureAttestation(
                        MetadataStructureAttestation.Kind.INSTALL, "historical-install-v0")))
            : acquiredProof;
    assertNotNull(original.structuralAttestation().orElseThrow());
    var builds = new SqliteBuildObjectCatalog(f.sql, f.store);
    builds.registerSnapshot(build);
    var cfgObject =
        f.store.publish(
            new java.io.ByteArrayInputStream(config),
            new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, build.buildConfig(), config.length),
            config.length,
            NONE);
    var cfgSource = installs.configurationIdentity(build);
    var cfg =
        new VerifiedSourceAlias(
            cfgSource,
            cfgObject,
            new SourceVerificationEvidence(
                cfgSource.verificationSemantics(),
                CdnRequest.configuration(CapturedBuildSource.resolve(build), build.buildConfig())
                    .uri()
                    .toString(),
                CLOCK.instant()));
    builds.attachVerifiedObject(
        build.snapshotId(), "BUILD_CONFIG", cfg.source(), cfg.object(), cfg.evidence(), NONE);
    var slot = new io.vibeminer.domain.selectedfile.ContentSlotKey("wow", ckey);
    var reserved = f.claims.reserve(slot, ekey, encoded.length, NONE);
    var seedPermit = f.claims.claimWorker(reserved.token(), NONE);
    f.claims.markPublishing(seedPermit, NONE);
    f.claims.recordVerifiedCompletion(seedPermit, original, NONE);
    f.claims.completePublication(seedPermit, original, NONE);
    f.claims.finishWorker(seedPermit, ContentWorkerFinishOutcome.RETAINED, NONE);
    var old = f.claims.lookup(slot, NONE).orElseThrow();
    return new InstallSeed(encoded, ordinary, cfg, original, old, installs, client);
  }

  static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (java.io.IOException e) {
      throw new AssertionError(e);
    }
  }

  static byte[] sha(byte[] bytes) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  record Policy(
      DefaultMetadataRepairService service,
      OfficialMetadataLocalVerificationGateway local,
      OfficialMetadataReuseGateway declarations)
      implements AutoCloseable {
    public void close() {
      service.close();
      local.close();
      declarations.close();
    }
  }

  static Policy policy(Fixture f, LookupTransport transport, Executor executor, AtomicLong nanos) {
    return policy(f, f.issuer, transport, executor, nanos);
  }

  static Policy policy(
      Fixture f,
      OfficialMetadataRepairGateway issuer,
      LookupTransport transport,
      Executor executor,
      AtomicLong nanos) {
    // The actual SQL write must occur after publisher retirement, not merely after a facade result.
    try (var connection = f.sql.connect();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TRIGGER IF NOT EXISTS repair_attachment_requires_retirement BEFORE INSERT ON"
              + " metadata_build_associations WHEN EXISTS(SELECT 1 FROM canonical_content_slots"
              + " WHERE token=NEW.canonical_token AND worker_nonce IS NOT NULL) BEGIN SELECT"
              + " RAISE(ABORT,'repair publisher not retired'); END");
    } catch (java.sql.SQLException failure) {
      throw new AssertionError(failure);
    }
    var local = new OfficialMetadataLocalVerificationGateway(f.store);
    var declarations = new OfficialMetadataReuseGateway(f.store);
    // Observe the exact real SQL completion object without substituting any verification or write.
    var observedAudits =
        (MetadataRepairAuditRepository)
            java.lang.reflect.Proxy.newProxyInstance(
                MetadataRepairAuditRepository.class.getClassLoader(),
                new Class<?>[] {MetadataRepairAuditRepository.class},
                (proxy, method, args) -> {
                  try {
                    var value = method.invoke(f.audits, args);
                    if (method.getName().equals("complete"))
                      f.completedAudit.set((MetadataRepairAudit) value);
                    return value;
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    return new Policy(
        new DefaultMetadataRepairService(
            issuer,
            new OfficialMetadataRepairExecutionGateway(issuer, f.store, transport, CLOCK),
            local,
            declarations,
            new SqliteBuildObjectCatalog(f.sql, f.store),
            f.claims,
            observedAudits,
            new SqliteMetadataAssociationRepository(f.sql, f.store, f.store.contentSession()),
            f.store,
            executor,
            CLOCK,
            nanos::get),
        local,
        declarations);
  }

  @Test
  void serviceRepairsMissingRootFillsOnlyMissingCapabilitiesAndFreshReaderReopens()
      throws Exception {
    for (int history : new int[] {1, 2}) {
      Path path = directory.resolve("history-" + history);
      MetadataBuildAssociation attached;
      try (var f = new Fixture(path, history);
          var policy = policy(f, f.wire, Runnable::run, new AtomicLong())) {
        Files.delete(f.objectPath());
        int requests = f.wire.requests.size();
        var issued = policy.service.prepareRoot(f.selection).toCompletableFuture().join();
        assertTrue(issued.cost().encodedTransferBytes() > 0);
        assertEquals(1, f.head.requests.size());
        var execution = policy.service.execute(issued).toCompletableFuture().join();
        attached = execution.association();
        assertCompletedResult(f, issued, execution);
        assertEquals(f.original.originalAlias(), attached.actualRepresentation().originalAlias());
        if (history == 2) assertEquals(f.original, attached.actualRepresentation());
        else {
          assertTrue(attached.actualRepresentation().decodedAttestation().isPresent());
          assertTrue(attached.actualRepresentation().structuralAttestation().isPresent());
        }
        assertEquals(
            attached.actualRepresentation(),
            f.claims.lookup(f.claim.slot(), NONE).orElseThrow().proof().orElseThrow());
        assertEquals(1, count(f.sql, "retention_scopes"));
        assertEquals(2, count(f.sql, "build_object_memberships"));
        assertTrue(f.wire.requests.size() > requests);
        var next = f.claims.claimWorker(f.claim.token(), NONE);
        f.claims.finishWorker(next, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
      }
      try (var store = new FileObjectStore(path.resolve("store"))) {
        var sql = new SqliteCatalog(path.resolve("catalog.sqlite"));
        var cap = store.contentSession();
        SqliteCanonicalContentRepository.recoverAtStartup(sql, cap);
        var repo = new SqliteMetadataAssociationRepository(sql, store, cap);
        assertEquals(
            attached,
            repo.find(
                    attached.declaration().build().snapshotId(),
                    MetadataStructureAttestation.Kind.ROOT,
                    NONE)
                .orElseThrow());
        var noHttp = new RootLookupFixture();
        noHttp.rejectNetwork = true;
        try (var reader = new OfficialFileLookupGateway(store, noHttp, CLOCK)) {
          assertEquals(
              2,
              reader
                  .lookupRetained(
                      attached.declaration().build(),
                      attached.declaration().configuration(),
                      attached.actualRepresentation().originalAlias(),
                      Set.of(42L),
                      LookupBudgets.defaults(),
                      NONE)
                  .variants()
                  .size());
          assertTrue(noHttp.requests.isEmpty());
        }
      }
    }
  }

  @Test
  void restoredHealthyOriginalAfterConsentUsesLocalReplayWithoutPayloadOrAudit() throws Exception {
    try (var f = new Fixture(directory, 1);
        var policy = policy(f, f.wire, Runnable::run, new AtomicLong())) {
      byte[] bytes = Files.readAllBytes(f.objectPath());
      Files.delete(f.objectPath());
      var issued = policy.service.prepareRoot(f.selection).toCompletableFuture().join();
      int requests = f.wire.requests.size();
      Files.write(f.objectPath(), bytes);
      var result = policy.service.execute(issued).toCompletableFuture().join();
      assertEquals(
          f.original.originalAlias(), result.association().actualRepresentation().originalAlias());
      assertTrue(result.completedAudit().isEmpty());
      assertNull(f.completedAudit.get());
      assertEquals(requests, f.wire.requests.size());
      assertEquals(0, count(f.sql, "metadata_repair_attempts"));
      assertEquals(1, count(f.sql, "retention_scopes"));
    }
  }

  @Test
  void serviceRejectsForgedOrExpiredConsentBeforePayloadAuditAndWorkerAdmission() throws Exception {
    try (var f = new Fixture(directory);
        var policy = policy(f, f.wire, Runnable::run, new AtomicLong())) {
      Files.delete(f.objectPath());
      var issued = policy.service.prepareRoot(f.selection).toCompletableFuture().join();
      int requests = f.wire.requests.size();
      var forged =
          new MetadataRepairPlan(
              UUID.randomUUID(),
              issued.reservationToken(),
              issued.request(),
              issued.originalProof(),
              issued.sourceObservation(),
              issued.cost(),
              issued.issuedAt(),
              issued.expiresAt());
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertInstanceOf(
                  ContentClaimException.class,
                  assertThrows(
                          CompletionException.class,
                          () -> policy.service.execute(forged).toCompletableFuture().join())
                      .getCause())
              .code());
      assertEquals(requests, f.wire.requests.size());
      assertEquals(0, count(f.sql, "metadata_repair_attempts"));
      assertEquals(0, count(f.sql, "retention_scopes"));
    }
    try (var f = new Fixture(directory.resolve("expired"))) {
      var nanos = new AtomicLong();
      try (var policy = policy(f, f.wire, Runnable::run, nanos)) {
        Files.delete(f.objectPath());
        var issued = policy.service.prepareRoot(f.selection).toCompletableFuture().join();
        int requests = f.wire.requests.size();
        nanos.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertEquals(
            ContentClaimException.Code.REPLAN_REQUIRED,
            assertInstanceOf(
                    ContentClaimException.class,
                    assertThrows(
                            CompletionException.class,
                            () -> policy.service.execute(issued).toCompletableFuture().join())
                        .getCause())
                .code());
        assertEquals(requests, f.wire.requests.size());
        assertEquals(0, count(f.sql, "metadata_repair_attempts"));
      }
    }
  }

  @Test
  void actualServiceCancellationKeepsWorkerExclusiveUntilIoRetiresAndNeverAttachesConsumer()
      throws Exception {
    try (var f = new Fixture(directory)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var retired = new CountDownLatch(1);
      var jobs = Executors.newSingleThreadExecutor();
      var held =
          new LookupTransport() {
            public CompletableFuture<AcquiredBytes> configuration(CdnRequest r) {
              throw new AssertionError();
            }

            public CompletableFuture<AcquiredRange> range(CdnRangeRequest r) {
              entered.countDown();
              boolean interrupted = false;
              while (true)
                try {
                  release.await();
                  break;
                } catch (InterruptedException e) {
                  interrupted = true;
                }
              if (interrupted) Thread.currentThread().interrupt();
              return f.wire.range(r);
            }
          };
      try (var policy = policy(f, held, jobs, new AtomicLong())) {
        Files.delete(f.objectPath());
        var issued =
            policy.service.prepareRoot(f.selection).toCompletableFuture().get(3, TimeUnit.SECONDS);
        var result = policy.service.execute(issued).toCompletableFuture();
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          assertTrue(result.cancel(true));
          assertEquals(
              ContentClaimException.Code.BUSY,
              assertThrows(
                      ContentClaimException.class,
                      () -> f.claims.claimWorker(f.claim.token(), NONE))
                  .code());
          assertEquals(0, count(f.sql, "retention_scopes"));
        } finally {
          release.countDown();
          jobs.execute(retired::countDown);
          assertTrue(retired.await(3, TimeUnit.SECONDS));
        }
        assertEquals(0, count(f.sql, "retention_scopes"));
        assertEquals(1, count(f.sql, "build_object_memberships"));
        try (var connection = f.sql.connect();
            var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT kind FROM metadata_repair_events")) {
          assertTrue(rows.next());
          assertEquals("CONCLUSIVELY_UNPUBLISHED", rows.getString(1));
          assertFalse(rows.next());
        }
        var next = f.claims.claimWorker(f.claim.token(), NONE);
        f.claims.finishWorker(next, ContentWorkerFinishOutcome.UNCERTAIN, NONE);
      } finally {
        release.countDown();
        jobs.shutdownNow();
        assertTrue(jobs.awaitTermination(3, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void serviceCorruptInstallPreservesHistoricalCapabilitiesAndFreshAssociatedReopen()
      throws Exception {
    MetadataBuildAssociation attached;
    var storePath = directory.resolve("store");
    try (var f = new Fixture(directory)) {
      var seed = seedInstall(f, true);
      String h = seed.original().object().digest().value();
      Path physical =
          storePath.resolve("objects").resolve(h.substring(0, 2)).resolve(h.substring(2));
      byte[] bad = seed.encoded().clone();
      bad[bad.length - 1] ^= 1;
      Files.write(physical, bad);
      f.head.length = seed.encoded().length;
      var calls = new AtomicInteger();
      var transport =
          new LookupTransport() {
            public CompletableFuture<AcquiredRange> range(CdnRangeRequest r) {
              throw new AssertionError();
            }

            public CompletableFuture<AcquiredBytes> configuration(CdnRequest r) {
              calls.incrementAndGet();
              return CompletableFuture.completedFuture(
                  new AcquiredBytes(
                      r,
                      CLOCK.instant(),
                      seed.encoded(),
                      java.util.HexFormat.of().formatHex(sha(seed.encoded())),
                      false));
            }
          };
      try (var issuer =
              new OfficialMetadataRepairGateway(
                  seed.gateway(),
                  f.roots,
                  new CdnMetadataTransport(f.head, CLOCK, Duration.ofSeconds(15)),
                  CLOCK,
                  System::nanoTime);
          var policy = policy(f, issuer, transport, Runnable::run, new AtomicLong())) {
        var issued =
            policy
                .service
                .prepareInstall(seed.ordinary(), LookupBudgets.defaults())
                .toCompletableFuture()
                .join();
        var execution = policy.service.execute(issued).toCompletableFuture().join();
        attached = execution.association();
        assertCompletedResult(f, issued, execution);
        assertEquals(1, calls.get());
        assertEquals(seed.original(), attached.actualRepresentation());
        assertEquals(seed.claim(), f.claims.lookup(seed.claim().slot(), NONE).orElseThrow());
        assertEquals(1, count(f.sql, "retention_scopes"));
      }
    }
    try (var store = new FileObjectStore(storePath)) {
      var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, cap);
      var claims = new SqliteCanonicalContentRepository(sql, store, cap);
      var associations = new SqliteMetadataAssociationRepository(sql, store, cap);
      var builds = new SqliteBuildObjectCatalog(sql, store);
      var calls = new AtomicInteger();
      var noWire = new MetadataRepairPlanningTest.HeadClient();
      var ordinary =
          new OfficialBootstrapArtifactGateway(
              store, new PinnedCdnTransport(noWire, CLOCK, Duration.ofSeconds(15)), CLOCK);
      try (var local = new OfficialMetadataReuseGateway(store);
          var service =
              new io.vibeminer.application.bootstrap.DefaultBootstrapService(
                  ordinary,
                  builds,
                  store,
                  Runnable::run,
                  Runnable::run,
                  null,
                  claims,
                  associations,
                  local)) {
        var result = service.reopen(attached.declaration().build()).toCompletableFuture().join();
        assertTrue(result.association().isPresent());
        assertEquals(attached, result.association().orElseThrow());
        assertTrue(noWire.requests.isEmpty());
      }
    }
  }

  static void assertCompletedResult(
      Fixture f, MetadataRepairPlan issued, MetadataRepairResult result) {
    var audit = result.completedAudit().orElseThrow();
    assertSame(f.completedAudit.get(), audit);
    assertEquals(issued, audit.issued());
    assertEquals(MetadataRepairAuditEvent.Kind.COMPLETED, audit.events().getLast().kind());
    var fresh = audit.events().getLast().observation().orElseThrow();
    assertEquals(issued.originalProof(), fresh.originalProof());
    assertEquals(
        issued.sourceObservation().resourceUrl(), fresh.actualTransfer().evidence().sourceUrl());
    assertEquals(issued.originalProof().object(), fresh.actualTransfer().object());
    assertTrue(fresh.payloadRequestCount() > 0);
    assertTrue(fresh.payloadResponseBytes() > 0);
    assertEquals(
        issued.originalProof().originalAlias(),
        result.association().actualRepresentation().originalAlias());
  }

  static final class PayloadClient extends java.net.http.HttpClient {
    final Map<String, byte[]> payloads = new HashMap<>();
    final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      assertEquals("GET", request.method());
      byte[] responseBytes =
          payloads.get(
              request.uri().getPath().substring(request.uri().getPath().lastIndexOf('/') + 1));
      assertNotNull(responseBytes, "Unexpected fixture URL");
      requests.add(request);
      var headers =
          HttpHeaders.of(
              Map.of("Content-Length", List.of(Integer.toString(responseBytes.length))),
              (a, b) -> true);
      var subscriber =
          handler.apply(
              new HttpResponse.ResponseInfo() {
                public int statusCode() {
                  return 200;
                }

                public HttpHeaders headers() {
                  return headers;
                }

                public Version version() {
                  return Version.HTTP_1_1;
                }
              });
      subscriber.onSubscribe(
          new Flow.Subscription() {
            public void request(long n) {}

            public void cancel() {}
          });
      subscriber.onNext(List.of(ByteBuffer.wrap(responseBytes)));
      subscriber.onComplete();
      return subscriber
          .getBody()
          .toCompletableFuture()
          .thenApply(
              body ->
                  new HttpResponse<T>() {
                    public int statusCode() {
                      return 200;
                    }

                    public HttpRequest request() {
                      return request;
                    }

                    public Optional<HttpResponse<T>> previousResponse() {
                      return Optional.empty();
                    }

                    public HttpHeaders headers() {
                      return headers;
                    }

                    public T body() {
                      return body;
                    }

                    public Optional<SSLSession> sslSession() {
                      return Optional.empty();
                    }

                    public URI uri() {
                      return request.uri();
                    }

                    public Version version() {
                      return Version.HTTP_1_1;
                    }
                  });
    }

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception e) {
        throw new AssertionError(e);
      }
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

    public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) {
      throw new AssertionError();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
      throw new AssertionError();
    }
  }
}
