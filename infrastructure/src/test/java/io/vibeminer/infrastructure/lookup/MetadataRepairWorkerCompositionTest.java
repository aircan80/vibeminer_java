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

    Fixture(Path path) throws Exception {
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
      original = captured.get();
      assertNotNull(original.structuralAttestation().orElseThrow());
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
      claims.completePublication(permit, original, NONE);
      claims.finishWorker(permit, ContentWorkerFinishOutcome.RETAINED, NONE);
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
      var original = acquired.get();
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
