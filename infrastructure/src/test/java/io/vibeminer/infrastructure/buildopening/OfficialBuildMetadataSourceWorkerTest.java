package io.vibeminer.infrastructure.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.config.TactConfigParser;
import io.vibeminer.protocol.tact.download.DownloadInventoryReader;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryReader;
import io.vibeminer.protocol.tact.encoding.FullEncodingInventory;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.DeflaterOutputStream;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialBuildMetadataSourceWorkerTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC);

  static MetadataStreamBudgets budgets() {
    return new MetadataStreamBudgets(
        17,
        1_000_000,
        100_000,
        Duration.ofSeconds(30),
        2_000_000,
        2_000_000,
        new StreamingDecodedBlteInputStream.Limits(
            4096, 100, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000, 31),
        new TactConfigParser.Limits(65536, 100, 4096, 100, 1_000_000, 1_000_000),
        new FullEncodingInventory.Limits(
            1_000_000, 10000, 10000, 100, 65536, 4096, 1000, 255, 100, 65536, 1000),
        new DownloadInventoryReader.Limits(1_000_000, 10000, 100, 4096, 4096, 100000, 255),
        new DownloadSizeInventoryReader.Limits(1_000_000, 10000, 100, 4096, 4096, 100000));
  }

  static final class Fixture implements AutoCloseable {
    final FileObjectStore store;
    final SqliteCatalog sql;
    final SqliteCanonicalContentRepository canonical;
    final SqliteBuildObjectCatalog builds;
    final Client client = new Client();
    final OfficialBuildMetadataSourceWorker worker;
    final MetadataContentRequest request;
    final byte[] encoded;

    Fixture(Path path, MetadataStructureAttestation.Kind kind, byte[] decoded) throws Exception {
      this(path, kind, decoded, null);
    }

    Fixture(
        Path path,
        MetadataStructureAttestation.Kind kind,
        byte[] decoded,
        String contentKeyOverride)
        throws Exception {
      store = new FileObjectStore(path.resolve("store"));
      sql = new SqliteCatalog(path.resolve("catalog.sqlite"));
      var session = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, session);
      canonical = new SqliteCanonicalContentRepository(sql, store, session);
      builds = new SqliteBuildObjectCatalog(sql, store);
      encoded = blte(decoded);
      String ck = contentKeyOverride == null ? md5(decoded) : contentKeyOverride;
      String ek = md5(Arrays.copyOf(encoded, 36));
      String field =
          kind == MetadataStructureAttestation.Kind.ENCODING
              ? "encoding"
              : kind == MetadataStructureAttestation.Kind.DOWNLOAD ? "download" : "size";
      byte[] raw =
          (field
                  + " = "
                  + ck
                  + " "
                  + ek
                  + "\n"
                  + field
                  + "-size = "
                  + decoded.length
                  + " "
                  + encoded.length
                  + "\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var object =
          store.publish(
              new ByteArrayInputStream(raw),
              new ExpectedObjectChecksum(ChecksumAlgorithm.MD5, md5(raw), raw.length),
              raw.length,
              NONE);
      var alias =
          new VerifiedSourceAlias(
              new ResolvedSourceIdentity("tact.config.raw-md5", md5(raw), "tact-config-raw-md5-v1"),
              object,
              new SourceVerificationEvidence(
                  "tact-config-raw-md5-v1",
                  "https://us.cdn.blizzard.com/tpr/wow/config/"
                      + md5(raw).substring(0, 2)
                      + "/"
                      + md5(raw).substring(2, 4)
                      + "/"
                      + md5(raw),
                  CLOCK.instant()));
      var build = build(md5(raw), "us");
      builds.registerSnapshot(build);
      builds.attachVerifiedObject(
          build.snapshotId(), "BUILD_CONFIG", alias.source(), object, alias.evidence(), NONE);
      request =
          new MetadataContentRequest(
              build,
              alias,
              kind,
              new ContentSlotKey("wow", ck),
              ek,
              encoded.length,
              OptionalLong.of(decoded.length));
      client.bytes = encoded;
      worker =
          new OfficialBuildMetadataSourceWorker(
              store,
              canonical,
              builds,
              new PinnedCdnRangeTransport(client, CLOCK, Duration.ofSeconds(2)),
              CLOCK);
    }

    public void close() {
      store.close();
    }
  }

  @Test
  void fullRealCasAndSqlPublicationAllThreeGrammarsAndLocalReplay() throws Exception {
    for (var kind :
        List.of(
            MetadataStructureAttestation.Kind.ENCODING,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            MetadataStructureAttestation.Kind.DOWNLOAD_SIZE)) {
      try (var f = new Fixture(directory.resolve(kind.name()), kind, metadata(kind))) {
        var alias = f.worker.acquire(f.request, budgets(), NONE);
        try (var lease = f.store.openRead(alias.object().digest(), NONE)) {
          assertArrayEquals(f.encoded, lease.stream().readAllBytes());
        }
        var claim = f.canonical.lookup(f.request.slot(), NONE).orElseThrow();
        var proof = (MetadataContentProof) claim.proof().orElseThrow();
        assertEquals(alias, proof.originalAlias());
        assertEquals(kind, proof.structuralAttestation().orElseThrow().kind());
        assertEquals(
            f.request.declaredDecodedBytes().orElseThrow(),
            proof.decodedAttestation().orElseThrow().actualDecodedBytes());
        int requests = f.client.requests.get();
        assertTrue(requests > 1);
        assertEquals(alias, f.worker.acquire(f.request, budgets(), NONE));
        assertEquals(requests, f.client.requests.get());
        assertEquals(1, f.builds.listReferences(f.request.build().snapshotId()).size());
      }
    }
  }

  @Test
  void crossRegionUsesSamePhysicalOriginalReceiptWithoutRestampOrMembership() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var original = f.worker.acquire(f.request, budgets(), NONE);
      var kr = build(f.request.build().buildConfig(), "kr");
      f.builds.registerSnapshot(kr);
      var request =
          new MetadataContentRequest(
              kr,
              f.request.configuration(),
              f.request.kind(),
              f.request.slot(),
              f.request.requestedEncodingKey(),
              f.request.requestedEncodedBytes(),
              f.request.declaredDecodedBytes());
      int count = f.client.requests.get();
      assertEquals(original, f.worker.acquire(request, budgets(), NONE));
      assertEquals(count, f.client.requests.get());
      assertTrue(original.evidence().sourceUrl().contains("us.cdn.blizzard.com"));
      assertTrue(f.builds.listReferences(kr.snapshotId()).isEmpty());
    }
  }

  @Test
  void configMismatchRefusesBeforeCanonicalAdmissionOrHttp() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var wrong =
          new MetadataContentRequest(
              f.request.build(),
              f.request.configuration(),
              f.request.kind(),
              new ContentSlotKey("wow", "f".repeat(32)),
              f.request.requestedEncodingKey(),
              f.request.requestedEncodedBytes(),
              f.request.declaredDecodedBytes());
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(ContentClaimException.class, () -> f.worker.acquire(wrong, budgets(), NONE))
              .code());
      assertEquals(0, f.client.requests.get());
      assertTrue(f.canonical.lookup(wrong.slot(), NONE).isEmpty());
    }
  }

  @Test
  void decodedAndGrammarBudgetsRefuseBeforePayload() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var b = budgets();
      var small =
          new MetadataStreamBudgets(
              b.rangeSliceBytes(),
              b.maximumWireBytes(),
              b.maximumRequests(),
              b.timeout(),
              1,
              b.maximumGrammarWorkBytes(),
              b.blte(),
              b.configuration(),
              b.encoding(),
              b.download(),
              b.downloadSize());
      assertEquals(
          MetadataSourceException.Reason.LIMIT,
          assertThrows(
                  MetadataSourceException.class, () -> f.worker.acquire(f.request, small, NONE))
              .reason());
      assertEquals(0, f.client.requests.get());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
  }

  @Test
  void authenticatedBadGrammarNeverBecomesCompleteButRetainsProspectiveProof() throws Exception {
    byte[] malformed = metadata(MetadataStructureAttestation.Kind.DOWNLOAD);
    malformed[2] = 99;
    try (var f = new Fixture(directory, MetadataStructureAttestation.Kind.DOWNLOAD, malformed)) {
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> f.worker.acquire(f.request, budgets(), NONE));
      assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
      var proof =
          (MetadataContentProof)
              f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow();
      assertTrue(proof.decodedAttestation().isPresent());
      assertTrue(proof.structuralAttestation().isEmpty());
      assertTrue(f.store.verify(proof.object().digest(), NONE).isPresent());
      assertEquals(1, f.builds.listReferences(f.request.build().snapshotId()).size());
    }
  }

  @Test
  void encodedCorruptionBeforeGatePublishesNothing() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      f.client.bytes = f.encoded.clone();
      f.client.bytes[f.encoded.length - 1] ^= 1;
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> f.worker.acquire(f.request, budgets(), NONE));
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
  }

  @Test
  void prospectiveGatePrecedesCasAndGrammarGateFollowsLeasedLocalRead() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var stages = new ArrayList<String>();
      var worker =
          worker(
              f,
              observedRepository(
                  f,
                  (method, args) -> {
                    if (method.equals("recordVerifiedCompletion")) {
                      var proof = (MetadataContentProof) args[1];
                      assertTrue(proof.decodedAttestation().isPresent());
                      assertTrue(proof.structuralAttestation().isEmpty());
                      assertTrue(f.store.verify(proof.object().digest(), NONE).isEmpty());
                      stages.add("prospective");
                    } else if (method.equals("upgradeProof")) {
                      var proof = (MetadataContentProof) args[1];
                      assertTrue(proof.structuralAttestation().isPresent());
                      assertTrue(f.store.verify(proof.object().digest(), NONE).isPresent());
                      stages.add("grammar");
                    } else if (method.equals("completePublication")) stages.add("complete");
                  },
                  false),
              CLOCK);
      worker.acquire(f.request, budgets(), NONE);
      assertEquals(List.of("prospective", "grammar", "complete"), stages);
    }
  }

  @Test
  void persistedProspectiveThenThrowStaysUncertainAndNeverPromotes() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var authored = new IllegalStateException("authored durable callback failure");
      var worker =
          worker(
              f,
              observedRepository(
                  f,
                  (method, args) -> {
                    if (method.equals("recordVerifiedCompletion")) throw authored;
                  },
                  true),
              CLOCK);
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> worker.acquire(f.request, budgets(), NONE));
      assertSame(authored, failure.failure());
      assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
      var proof = f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow();
      assertTrue(f.store.verify(proof.object().digest(), NONE).isEmpty());
      assertTrue(((MetadataContentProof) proof).structuralAttestation().isEmpty());
    }
  }

  @Test
  void cancelledActualWorkerCannotRetireUntilItsHeldGateReturns() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var cancelled = new AtomicBoolean();
      var blockedClock =
          new Clock() {
            public ZoneId getZone() {
              return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
              return this;
            }

            public Instant instant() {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("gate release timeout");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
              return CLOCK.instant();
            }
          };
      var worker = worker(f, f.canonical, blockedClock);
      var pool = Executors.newSingleThreadExecutor();
      try {
        var running = pool.submit(() -> worker.acquire(f.request, budgets(), cancelled::get));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        cancelled.set(true);
        var claim = f.canonical.lookup(f.request.slot(), NONE).orElseThrow();
        assertEquals(
            ContentClaimException.Code.BUSY,
            assertThrows(
                    ContentClaimException.class, () -> f.canonical.claimWorker(claim.token(), NONE))
                .code());
        assertFalse(running.isDone());
        release.countDown();
        var failure =
            assertThrows(ExecutionException.class, () -> running.get(3, TimeUnit.SECONDS));
        assertInstanceOf(MetadataAcquisitionFailure.class, failure.getCause());
        assertTrue(
            f.builds.listReferences(f.request.build().snapshotId()).stream()
                .noneMatch(ref -> !ref.role().equals("BUILD_CONFIG")));
      } finally {
        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void missingRetainedCanonicalBytesFailLocallyWithoutHiddenGet() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var alias = f.worker.acquire(f.request, budgets(), NONE);
      String digest = alias.object().digest().value();
      Files.delete(
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2)));
      int requests = f.client.requests.get();
      assertThrows(ObjectStoreException.class, () -> f.worker.acquire(f.request, budgets(), NONE));
      assertEquals(requests, f.client.requests.get());
      assertEquals(
          alias,
          ((MetadataContentProof)
                  f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow())
              .originalAlias());
    }
  }

  @Test
  void completeCkeyMismatchFailsBeforeProspectiveOrCas() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD),
            "f".repeat(32))) {
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> f.worker.acquire(f.request, budgets(), NONE));
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
  }

  @Test
  void authenticatedEnPageMismatchNeverGetsGrammarCapability() throws Exception {
    byte[] data = metadata(MetadataStructureAttestation.Kind.ENCODING);
    data[data.length - 1] ^= 1;
    try (var f = new Fixture(directory, MetadataStructureAttestation.Kind.ENCODING, data)) {
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> f.worker.acquire(f.request, budgets(), NONE));
      assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
      var proof =
          (MetadataContentProof)
              f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow();
      assertTrue(proof.structuralAttestation().isEmpty());
    }
  }

  @Test
  void deadlineAfterGrammarCallbackCannotCompletePublication() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var nanos = new AtomicLong();
      var repository =
          observedRepository(
              f,
              (method, args) -> {
                if (method.equals("upgradeProof")) nanos.set(Duration.ofSeconds(31).toNanos());
              },
              true);
      var worker =
          new OfficialBuildMetadataSourceWorker(
              f.store,
              repository,
              f.builds,
              new PinnedCdnRangeTransport(f.client, CLOCK, Duration.ofSeconds(2)),
              CLOCK,
              nanos::get);
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> worker.acquire(f.request, budgets(), NONE));
      assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
      assertEquals(
          MetadataSourceException.Reason.DEADLINE,
          assertInstanceOf(MetadataSourceException.class, failure.failure()).reason());
      assertNotEquals(
          io.vibeminer.domain.selectedfile.CanonicalReservation.Status.RETAINED,
          f.canonical.lookup(f.request.slot(), NONE).orElseThrow().status());
    }
  }

  @Test
  void rootInstallAndUnsupportedCapturedContextFailWithoutAuthorityOrGet() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      for (var kind :
          List.of(
              MetadataStructureAttestation.Kind.ROOT, MetadataStructureAttestation.Kind.INSTALL)) {
        var wrong =
            new MetadataContentRequest(
                f.request.build(),
                f.request.configuration(),
                kind,
                f.request.slot(),
                f.request.requestedEncodingKey(),
                f.request.requestedEncodedBytes(),
                f.request.declaredDecodedBytes());
        assertEquals(
            ContentClaimException.Code.UNSUPPORTED_REUSE,
            assertThrows(
                    ContentClaimException.class, () -> f.worker.acquire(wrong, budgets(), NONE))
                .code());
      }
      var current = f.request.build();
      var noSource =
          new BuildSnapshot(
              current.productId(),
              current.editionLabel(),
              current.channel(),
              current.region(),
              current.versionLabel(),
              current.buildConfig(),
              current.cdnConfig(),
              current.observedAt(),
              false);
      var wrong =
          new MetadataContentRequest(
              noSource,
              f.request.configuration(),
              f.request.kind(),
              f.request.slot(),
              f.request.requestedEncodingKey(),
              f.request.requestedEncodedBytes(),
              f.request.declaredDecodedBytes());
      assertThrows(SourceContextException.class, () -> f.worker.acquire(wrong, budgets(), NONE));
      assertEquals(0, f.client.requests.get());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
  }

  @Test
  void corruptedConfigNeverAuthorizesAdmissionAnd404KeepsTypedRemoteFailure() throws Exception {
    try (var f =
        new Fixture(
            directory.resolve("config"),
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      String digest = f.request.configuration().object().digest().value();
      Path file =
          directory
              .resolve("config/store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2));
      byte[] raw = Files.readAllBytes(file);
      raw[0] ^= 1;
      Files.write(file, raw);
      assertEquals(
          ObjectStoreException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  ObjectStoreException.class, () -> f.worker.acquire(f.request, budgets(), NONE))
              .code());
      assertEquals(0, f.client.requests.get());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
    try (var f =
        new Fixture(
            directory.resolve("missing"),
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      f.client.status = 404;
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> f.worker.acquire(f.request, budgets(), NONE));
      var remote = assertInstanceOf(AcquisitionException.class, failure.failure());
      assertEquals(AcquisitionException.Reason.UNAVAILABLE, remote.reason());
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertEquals(1, f.client.requests.get());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
  }

  @Test
  void independentWireRequestGrammarAndConfigSizeCeilingsRefuseBeforeHttp() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var b = budgets();
      var caps =
          List.of(
              new MetadataStreamBudgets(
                  b.rangeSliceBytes(),
                  1,
                  b.maximumRequests(),
                  b.timeout(),
                  b.maximumDecodedWorkBytes(),
                  b.maximumGrammarWorkBytes(),
                  b.blte(),
                  b.configuration(),
                  b.encoding(),
                  b.download(),
                  b.downloadSize()),
              new MetadataStreamBudgets(
                  b.rangeSliceBytes(),
                  b.maximumWireBytes(),
                  1,
                  b.timeout(),
                  b.maximumDecodedWorkBytes(),
                  b.maximumGrammarWorkBytes(),
                  b.blte(),
                  b.configuration(),
                  b.encoding(),
                  b.download(),
                  b.downloadSize()),
              new MetadataStreamBudgets(
                  b.rangeSliceBytes(),
                  b.maximumWireBytes(),
                  b.maximumRequests(),
                  b.timeout(),
                  b.maximumDecodedWorkBytes(),
                  1,
                  b.blte(),
                  b.configuration(),
                  b.encoding(),
                  b.download(),
                  b.downloadSize()),
              new MetadataStreamBudgets(
                  b.rangeSliceBytes(),
                  b.maximumWireBytes(),
                  b.maximumRequests(),
                  b.timeout(),
                  b.maximumDecodedWorkBytes(),
                  b.maximumGrammarWorkBytes(),
                  b.blte(),
                  new TactConfigParser.Limits(65536, 100, 4096, 100, 1, 1),
                  b.encoding(),
                  b.download(),
                  b.downloadSize()));
      for (var small : caps) {
        assertEquals(
            MetadataSourceException.Reason.LIMIT,
            assertThrows(
                    MetadataSourceException.class, () -> f.worker.acquire(f.request, small, NONE))
                .reason());
      }
      assertEquals(0, f.client.requests.get());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
  }

  @Test
  void lateRange404PreservesRemoteClassificationThroughCasIoWrapper() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      f.client.beforeRequest =
          count -> {
            if (count == 4) f.client.status = 404;
          };
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> f.worker.acquire(f.request, budgets(), NONE));
      assertEquals(
          AcquisitionException.Reason.UNAVAILABLE,
          assertInstanceOf(AcquisitionException.class, failure.failure()).reason());
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertEquals(4, f.client.requests.get());
      assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
    }
  }

  @Test
  void lateRangeCancellationAndDeadlineSurviveCasIoWrapper() throws Exception {
    for (boolean deadline : new boolean[] {false, true}) {
      try (var f =
          new Fixture(
              directory.resolve(Boolean.toString(deadline)),
              MetadataStructureAttestation.Kind.DOWNLOAD,
              metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
        var nanos = new AtomicLong();
        var cancelled = new AtomicBoolean();
        f.client.beforeRequest =
            count -> {
              if (count == 4) {
                if (deadline) nanos.set(Duration.ofSeconds(31).toNanos());
                else cancelled.set(true);
              }
            };
        var worker =
            new OfficialBuildMetadataSourceWorker(
                f.store,
                f.canonical,
                f.builds,
                new PinnedCdnRangeTransport(f.client, CLOCK, Duration.ofSeconds(2)),
                CLOCK,
                nanos::get);
        var failure =
            assertThrows(
                MetadataAcquisitionFailure.class,
                () -> worker.acquire(f.request, budgets(), cancelled::get));
        if (deadline)
          assertEquals(
              MetadataSourceException.Reason.DEADLINE,
              assertInstanceOf(MetadataSourceException.class, failure.failure()).reason());
        else assertInstanceOf(CancellationException.class, failure.failure());
        assertEquals(
            MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
        assertEquals(4, f.client.requests.get());
        assertTrue(f.canonical.lookup(f.request.slot(), NONE).isEmpty());
      }
    }
  }

  @Test
  void genuineLocalFilesystemIoFailureKeepsOriginalTypedIdentity() throws Exception {
    try (var f =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      var local =
          new ObjectStoreException(
              ObjectStoreException.Code.IO_FAILURE,
              "authored local filesystem failure",
              new IOException("authored disk IO"));
      var store =
          (ObjectStore)
              java.lang.reflect.Proxy.newProxyInstance(
                  ObjectStore.class.getClassLoader(),
                  new Class<?>[] {ObjectStore.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("publishVerified")) throw local;
                    try {
                      return method.invoke(f.store, args);
                    } catch (java.lang.reflect.InvocationTargetException wrapped) {
                      throw wrapped.getCause();
                    }
                  });
      var worker =
          new OfficialBuildMetadataSourceWorker(
              store,
              f.canonical,
              f.builds,
              new PinnedCdnRangeTransport(f.client, CLOCK, Duration.ofSeconds(2)),
              CLOCK);
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class, () -> worker.acquire(f.request, budgets(), NONE));
      assertSame(local, failure.failure());
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
    }
  }

  private static OfficialBuildMetadataSourceWorker worker(
      Fixture f, CanonicalContentRepository canonical, Clock clock) {
    return new OfficialBuildMetadataSourceWorker(
        f.store,
        canonical,
        f.builds,
        new PinnedCdnRangeTransport(f.client, CLOCK, Duration.ofSeconds(2)),
        clock);
  }

  private static CanonicalContentRepository observedRepository(
      Fixture f, java.util.function.BiConsumer<String, Object[]> observer, boolean after) {
    return (CanonicalContentRepository)
        java.lang.reflect.Proxy.newProxyInstance(
            CanonicalContentRepository.class.getClassLoader(),
            new Class<?>[] {CanonicalContentRepository.class},
            (proxy, method, args) -> {
              if (!after) observer.accept(method.getName(), args);
              Object value;
              try {
                value = method.invoke(f.canonical, args);
              } catch (java.lang.reflect.InvocationTargetException wrapped) {
                throw wrapped.getCause();
              }
              if (after) observer.accept(method.getName(), args);
              return value;
            });
  }

  static BuildSnapshot build(String config, String region) {
    String cdn = "b".repeat(32);
    byte[] versions =
        ("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                + region
                + "|"
                + config
                + "|"
                + cdn
                + "|synthetic\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] cdns =
        ("Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
                + region
                + "|tpr/wow|"
                + region
                + ".cdn.blizzard.com|https://"
                + region
                + ".cdn.blizzard.com|tpr/wow\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return new BuildSnapshot(
        "wow",
        "Retail",
        "retail",
        region,
        "synthetic",
        config,
        cdn,
        CLOCK.instant(),
        false,
        new BuildEvidence(
            "",
            "",
            List.of(
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/versions",
                    CLOCK.instant(),
                    versions),
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/cdns", CLOCK.instant(), cdns))));
  }

  static byte[] metadata(MetadataStructureAttestation.Kind kind) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    if (kind == MetadataStructureAttestation.Kind.ENCODING) {
      byte[] ck = new byte[16], ek = new byte[16];
      ck[15] = 1;
      ek[15] = 2;
      var cpBytes = new ByteArrayOutputStream();
      var cp = new DataOutputStream(cpBytes);
      cp.writeByte(1);
      cp.write(new byte[] {0, 0, 0, 0, 3});
      cp.write(ck);
      cp.write(ek);
      byte[] cpage = Arrays.copyOf(cpBytes.toByteArray(), 1024);
      var epBytes = new ByteArrayOutputStream();
      var ep = new DataOutputStream(epBytes);
      ep.write(ek);
      ep.writeInt(0);
      ep.write(new byte[] {0, 0, 0, 0, 3});
      ep.write(new byte[16]);
      ep.writeInt(-1);
      ep.write(new byte[5]);
      byte[] epage = Arrays.copyOf(epBytes.toByteArray(), 1024);
      out.writeBytes("EN");
      out.writeByte(1);
      out.writeByte(16);
      out.writeByte(16);
      out.writeShort(1);
      out.writeShort(1);
      out.writeInt(1);
      out.writeInt(1);
      out.writeByte(0);
      out.writeInt(2);
      out.write(new byte[] {'z', 0});
      out.write(ck);
      out.write(MessageDigest.getInstance("MD5").digest(cpage));
      out.write(cpage);
      out.write(ek);
      out.write(MessageDigest.getInstance("MD5").digest(epage));
      out.write(epage);
    } else if (kind == MetadataStructureAttestation.Kind.DOWNLOAD) {
      out.writeBytes("DL");
      out.writeByte(3);
      out.writeByte(16);
      out.writeByte(0);
      out.writeInt(1);
      out.writeShort(0);
      out.writeByte(1);
      out.writeByte(254);
      out.write(new byte[3]);
      out.write(new byte[16]);
      out.write(new byte[] {0, 0, 0, 0, 3});
      out.writeByte(255);
      out.writeByte(7);
    } else {
      out.writeBytes("DS");
      out.writeByte(1);
      out.writeByte(9);
      out.writeInt(1);
      out.writeShort(0);
      out.write(new byte[] {0, 0, 0, 0, 3});
      out.write(new byte[9]);
      out.writeInt(3);
    }
    return bytes.toByteArray();
  }

  static byte[] blte(byte[] decoded) throws Exception {
    var chunk = new ByteArrayOutputStream();
    chunk.write('Z');
    try (var z = new DeflaterOutputStream(chunk)) {
      z.write(decoded);
    }
    byte[] payload = chunk.toByteArray();
    var result = new ByteArrayOutputStream();
    var out = new DataOutputStream(result);
    out.writeBytes("BLTE");
    out.writeInt(36);
    out.writeByte(15);
    out.writeByte(0);
    out.writeShort(1);
    out.writeInt(payload.length);
    out.writeInt(decoded.length);
    out.write(MessageDigest.getInstance("MD5").digest(payload));
    out.write(payload);
    return result.toByteArray();
  }

  static String md5(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
  }

  static class Client extends HttpClient {
    byte[] bytes;
    int status = 206;
    java.util.function.IntConsumer beforeRequest = count -> {};
    final AtomicInteger requests = new AtomicInteger();

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      beforeRequest.accept(requests.incrementAndGet());
      var bounds = request.headers().firstValue("Range").orElseThrow().substring(6).split("-");
      int start = Integer.parseInt(bounds[0]), end = Integer.parseInt(bounds[1]);
      byte[] part = Arrays.copyOfRange(bytes, start, end + 1);
      var headers =
          HttpHeaders.of(
              Map.of("Content-Range", List.of("bytes " + start + "-" + end + "/" + bytes.length)),
              (a, b) -> true);
      var body =
          handler.apply(
              new HttpResponse.ResponseInfo() {
                public int statusCode() {
                  return status;
                }

                public HttpHeaders headers() {
                  return headers;
                }

                public Version version() {
                  return Version.HTTP_1_1;
                }
              });
      body.onSubscribe(
          new Flow.Subscription() {
            public void request(long n) {}

            public void cancel() {}
          });
      body.onNext(List.of(ByteBuffer.wrap(part)));
      body.onComplete();
      return body.getBody()
          .toCompletableFuture()
          .thenApply(
              value ->
                  new HttpResponse<T>() {
                    public int statusCode() {
                      return status;
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
                      return value;
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
        throw new IllegalStateException(e);
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

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      throw new UnsupportedOperationException();
    }
  }
}
