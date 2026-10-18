package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.selectedfile.SelectedFileException;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalWorkerAuthorityTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  static ContentSlotKey slot() {
    return new ContentSlotKey("wow", "c".repeat(32));
  }

  @Test
  void passiveSameSessionJoinerCannotReleaseOrAcquireHeldActualWorker() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var session = UUID.randomUUID();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var a = new SqliteCanonicalContentRepository(catalog, store, session);
      var b = new SqliteCanonicalContentRepository(catalog, store, session);
      var selected = new SqliteSelectedFileCatalog(catalog, store, session);
      var claim = a.reserve(slot(), "e".repeat(32), 80, NONE);
      var worker = a.claimWorker(claim.token(), NONE);
      a.markPublishing(worker, NONE);
      assertEquals(claim.token(), b.reserve(slot(), "e".repeat(32), 80, NONE).token());
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertThrows(ContentClaimException.class, () -> b.claimWorker(claim.token(), NONE))
              .code());
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertThrows(ContentClaimException.class, () -> b.releaseUnpublished(claim.token(), NONE))
              .code());
      assertEquals(
          SelectedFileException.Code.BUSY,
          assertThrows(
                  SelectedFileException.class,
                  () -> selected.releaseUnpublished(claim.token(), NONE))
              .code());
      assertEquals(
          SelectedFileException.Code.BUSY,
          assertThrows(SelectedFileException.class, () -> selected.claimWorker(claim.token(), NONE))
              .code());
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertThrows(
                  ContentClaimException.class, () -> b.reserve(slot(), "b".repeat(32), 90, NONE))
              .code());
      var historical = CanonicalContentRecordsTest.historical();
      var full =
          historical.withAttestations(
              java.util.Optional.of(
                  new DecodedContentAttestation(historical.contentKey(), 64, "decoded-v1")),
              java.util.Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.ROOT, "root-v1")));
      for (Runnable mutation :
          java.util.List.<Runnable>of(
              () -> b.markPublishing(claim.token(), NONE),
              () -> b.recordVerifiedCompletion(claim.token(), full, NONE),
              () -> b.completePublication(claim.token(), full, NONE),
              () -> b.upgradeProof(claim.token(), full, NONE),
              () -> b.markUncertain(claim.token(), NONE))) {
        assertEquals(
            ContentClaimException.Code.BUSY,
            assertThrows(ContentClaimException.class, mutation::run).code());
      }
      var encoded = encoded(slot(), new byte[80]).receipt();
      for (Runnable mutation :
          java.util.List.<Runnable>of(
              () -> selected.markPublishing(claim.token(), NONE),
              () -> selected.recordVerifiedCompletion(claim.token(), encoded, NONE),
              () -> selected.completePublication(claim.token(), encoded, NONE),
              () -> selected.markUncertain(claim.token(), NONE))) {
        assertEquals(
            SelectedFileException.Code.BUSY,
            assertThrows(SelectedFileException.class, mutation::run).code());
      }
      assertTrue(a.lookup(slot(), NONE).orElseThrow().proof().isEmpty());
      a.finishWorker(worker, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
      assertTrue(a.lookup(slot(), NONE).isEmpty());
      assertEquals("b".repeat(32), b.reserve(slot(), "b".repeat(32), 90, NONE).actualEncodingKey());
    }
  }

  @Test
  void wrongNonceAndForeignSessionCannotMutateOrStealActualWorker() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var session = UUID.randomUUID();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var a = new SqliteCanonicalContentRepository(catalog, store, session);
      var foreign = new SqliteCanonicalContentRepository(catalog, store, UUID.randomUUID());
      var claim = a.reserve(slot(), "e".repeat(32), 80, NONE);
      var worker = a.claimWorker(claim.token(), NONE);
      var wrong = new ContentWorkerPermit(claim.token(), UUID.randomUUID());
      assertThrows(
          ContentClaimException.class,
          () -> a.finishWorker(wrong, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE));
      assertThrows(
          ContentClaimException.class, () -> foreign.reserve(slot(), "e".repeat(32), 80, NONE));
      a.markPublishing(worker, NONE);
      a.finishWorker(worker, ContentWorkerFinishOutcome.UNCERTAIN, NONE);
      assertEquals(
          CanonicalReservation.Status.RECOVERY, a.lookup(slot(), NONE).orElseThrow().status());
      var next = a.claimWorker(claim.token(), NONE);
      assertThrows(
          ContentClaimException.class,
          () -> a.finishWorker(worker, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE));
      a.finishWorker(next, ContentWorkerFinishOutcome.UNCERTAIN, NONE);
    }
  }

  @Test
  void exclusiveStoreRestartFencesOldWorkerAndPreservesProspectiveProof() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    ContentWorkerPermit old;
    var proof = CanonicalContentRecordsTest.historical();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap);
      var repository = new SqliteCanonicalContentRepository(catalog, store, cap);
      var claim = repository.reserve(slot(), "e".repeat(32), 80, NONE);
      old = repository.claimWorker(claim.token(), NONE);
      repository.markPublishing(old, NONE);
      repository.recordVerifiedCompletion(old, proof, NONE);
      assertThrows(
          IllegalStateException.class,
          () -> SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap));
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap);
      var repository = new SqliteCanonicalContentRepository(catalog, store, cap);
      var claim = repository.lookup(slot(), NONE).orElseThrow();
      assertEquals(CanonicalReservation.Status.RECOVERY, claim.status());
      assertEquals(proof, claim.proof().orElseThrow());
      assertThrows(
          ContentClaimException.class,
          () ->
              repository.finishWorker(
                  old, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE));
      var fresh = repository.claimWorker(claim.token(), NONE);
      assertNotEquals(old.workerNonce(), fresh.workerNonce());
      assertThrows(ContentClaimException.class, () -> repository.markPublishing(old, NONE));
      repository.markPublishing(fresh, NONE);
      repository.finishWorker(fresh, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
      assertEquals(proof, repository.lookup(slot(), NONE).orElseThrow().proof().orElseThrow());
      assertEquals(
          CanonicalReservation.Status.RECOVERY,
          repository.lookup(slot(), NONE).orElseThrow().status());
    }
  }

  @Test
  void legacyProducerAdmissionMakesLaterStartupRecoveryImpossible() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var legacy = new SqliteCanonicalContentRepository(catalog, store, UUID.randomUUID());
      var claim = legacy.reserve(slot(), "e".repeat(32), 80, NONE);
      var worker = legacy.claimWorker(claim.token(), NONE);
      assertThrows(
          IllegalStateException.class,
          () -> SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession()));
      legacy.finishWorker(worker, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
    }
  }

  @Test
  void blockedActualMetadataHelperSurvivesIndependentConclusiveFailureAndSelectedContender()
      throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap);
      var repository = new SqliteCanonicalContentRepository(catalog, store, cap);
      var selected = new SqliteSelectedFileCatalog(catalog, store, cap);
      var helperA = new MetadataContentAcquisition(repository);
      var helperB = new MetadataContentAcquisition(repository);
      var request = request();
      var source = CanonicalContentRecordsTest.historical().originalAlias().source();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var exit = new java.util.concurrent.CountDownLatch(1);
      var secondTransfers = new java.util.concurrent.atomic.AtomicInteger();
      try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        var future =
            executor.submit(
                () ->
                    helperA.acquire(
                        request,
                        source,
                        java.util.Optional::empty,
                        (observer, token) -> {
                          entered.countDown();
                          try {
                            exit.await();
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException();
                          }
                          throw new MetadataAcquisitionFailure(
                              MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                              new IllegalStateException("A unavailable"));
                        },
                        (alias, observer, token) -> fail("No retained replay"),
                        NONE));
        try {
          assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
          var held = repository.lookup(slot(), NONE).orElseThrow();
          assertEquals(
              ContentClaimException.Code.BUSY,
              assertThrows(
                      ContentClaimException.class,
                      () ->
                          helperB.acquire(
                              request,
                              source,
                              java.util.Optional::empty,
                              (observer, token) -> {
                                secondTransfers.incrementAndGet();
                                throw new MetadataAcquisitionFailure(
                                    MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                                    new IllegalStateException("B unavailable"));
                              },
                              (alias, observer, token) -> fail("No replay"),
                              NONE))
                  .code());
          assertFalse(future.isDone());
          assertEquals(0, secondTransfers.get());
          assertEquals(held.token(), repository.lookup(slot(), NONE).orElseThrow().token());
          assertEquals(
              SelectedFileException.Code.BUSY,
              assertThrows(
                      SelectedFileException.class, () -> selected.claimWorker(held.token(), NONE))
                  .code());
          assertEquals(
              ContentClaimException.Code.BUSY,
              assertThrows(
                      ContentClaimException.class,
                      () -> repository.reserve(slot(), "b".repeat(32), 90, NONE))
                  .code());
        } finally {
          exit.countDown();
        }
        assertInstanceOf(
            MetadataAcquisitionFailure.class,
            assertThrows(java.util.concurrent.ExecutionException.class, future::get).getCause());
        assertTrue(repository.lookup(slot(), NONE).isEmpty());
      }
      var pending = selected.reserve(slot(), "e".repeat(32), 80, NONE);
      var worker = selected.claimWorker(pending.token(), NONE);
      selected.markPublishing(worker, NONE);
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      helperB.acquire(
                          request,
                          source,
                          java.util.Optional::empty,
                          (observer, token) -> {
                            secondTransfers.incrementAndGet();
                            throw new AssertionError("Must not fetch");
                          },
                          (alias, observer, token) -> fail("No replay"),
                          NONE))
              .code());
      assertEquals(0, secondTransfers.get());
      selected.finishWorker(worker, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
    }
  }

  @Test
  void allFanoutProspectiveProofsPrecedeOnePromotionAndPassiveJoinCannotAdoptUnderWorker()
      throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    byte[] bytes = new byte[80];
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap);
      var repository = new SqliteCanonicalContentRepository(catalog, store, cap);
      var secondSlot = new ContentSlotKey("wow", "d".repeat(32));
      var one = repository.reserve(slot(), "e".repeat(32), 80, NONE);
      var two = repository.reserve(secondSlot, "e".repeat(32), 80, NONE);
      var workerOne = repository.claimWorker(one.token(), NONE);
      var workerTwo = repository.claimWorker(two.token(), NONE);
      repository.markPublishing(workerOne, NONE);
      repository.markPublishing(workerTwo, NONE);
      var proofOne = encoded(slot(), bytes);
      var proofTwo = encoded(secondSlot, bytes);
      store.publishVerified(
          new java.io.ByteArrayInputStream(bytes),
          80,
          80,
          token -> {
            repository.recordVerifiedCompletion(workerOne, proofOne, token);
            repository.recordVerifiedCompletion(workerTwo, proofTwo, token);
            try (var connection = catalog.connect();
                var rows =
                    connection
                        .createStatement()
                        .executeQuery(
                            "SELECT count(*) FROM canonical_content_slots WHERE representation IS"
                                + " NOT NULL AND worker_nonce IS NOT NULL")) {
              assertEquals(2, rows.getInt(1));
            } catch (java.sql.SQLException failure) {
              throw new AssertionError(failure);
            }
          },
          NONE);
      repository.completePublication(workerOne, proofOne, NONE);
      var observed = repository.reserve(secondSlot, "e".repeat(32), 80, NONE);
      assertEquals(CanonicalReservation.Status.PUBLISHING, observed.status());
      assertEquals(proofTwo, observed.proof().orElseThrow());
      repository.completePublication(workerTwo, proofTwo, NONE);
      repository.finishWorker(workerOne, ContentWorkerFinishOutcome.UNCERTAIN, NONE);
      repository.finishWorker(workerTwo, ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED, NONE);
      assertEquals(
          CanonicalReservation.Status.RETAINED,
          repository.lookup(slot(), NONE).orElseThrow().status());
      assertEquals(
          CanonicalReservation.Status.RETAINED,
          repository.lookup(secondSlot, NONE).orElseThrow().status());
      assertThrows(ContentClaimException.class, () -> repository.markPublishing(workerOne, NONE));
      try (var files = java.nio.file.Files.walk(directory.resolve("store/objects"))) {
        assertEquals(1, files.filter(java.nio.file.Files::isRegularFile).count());
      }
      try (var connection = catalog.connect();
          var rows =
              connection
                  .createStatement()
                  .executeQuery("SELECT count(*) FROM encoded_file_receipts")) {
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  private static EncodedContentProof encoded(ContentSlotKey slot, byte[] bytes) throws Exception {
    var source = ContentRepresentation.sourceIdentity("e".repeat(32));
    var url = "https://us.cdn.blizzard.com/fixture/" + source.immutableSourceKey();
    var sha =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var alias =
        new io.vibeminer.domain.VerifiedSourceAlias(
            source,
            new io.vibeminer.domain.StoredObject(
                new io.vibeminer.domain.ObjectDigest(sha), bytes.length),
            new io.vibeminer.domain.SourceVerificationEvidence(
                source.verificationSemantics(), url, java.time.Instant.EPOCH));
    var receipt =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            url,
            source.immutableSourceKey(),
            bytes.length,
            0,
            bytes.length,
            java.time.Instant.EPOCH,
            1,
            bytes.length,
            "a".repeat(64),
            java.util.Optional.empty());
    return new EncodedContentProof(
        new ContentRepresentation(
            slot, alias, EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS, receipt));
  }

  private static MetadataContentRequest request() {
    var build =
        new io.vibeminer.domain.BuildSnapshot(
            "wow",
            "Fixture",
            "current",
            "us",
            "1",
            "a".repeat(32),
            "b".repeat(32),
            java.time.Instant.EPOCH,
            true);
    var configIdentity =
        new io.vibeminer.domain.ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1");
    var config =
        new io.vibeminer.domain.VerifiedSourceAlias(
            configIdentity,
            new io.vibeminer.domain.StoredObject(
                new io.vibeminer.domain.ObjectDigest("a".repeat(64)), 10),
            new io.vibeminer.domain.SourceVerificationEvidence(
                configIdentity.verificationSemantics(),
                "https://us.cdn.blizzard.com/config",
                java.time.Instant.EPOCH));
    return new MetadataContentRequest(
        build,
        config,
        MetadataStructureAttestation.Kind.ROOT,
        slot(),
        "e".repeat(32),
        80,
        java.util.OptionalLong.of(64));
  }
}
