package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteCanonicalContentRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  static ContentSlotKey slot() {
    return new ContentSlotKey("wow", CanonicalContentRecordsTest.C);
  }

  @Test
  void metadataProspectiveProofSurvivesRestartAndCannotPublishWithoutGrammar() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var first = new SqliteCanonicalContentRepository(catalog, store, UUID.randomUUID());
      var claim = first.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE);
      first.markPublishing(claim.token(), NONE);
      var proof =
          CanonicalContentRecordsTest.historical()
              .withAttestations(
                  Optional.of(
                      new DecodedContentAttestation(
                          CanonicalContentRecordsTest.C, 64, "decoded-v1")),
                  Optional.empty());
      first.recordVerifiedCompletion(claim.token(), proof, NONE);
      assertEquals(
          ContentClaimException.Code.INVALID_STATE,
          assertThrows(
                  ContentClaimException.class,
                  () -> first.completePublication(claim.token(), proof, NONE))
              .code());
      var second = new SqliteCanonicalContentRepository(catalog, store, UUID.randomUUID());
      assertEquals(
          CanonicalReservation.Status.RECOVERY, second.lookup(slot(), NONE).orElseThrow().status());
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertThrows(
                  ContentClaimException.class,
                  () -> second.reserve(slot(), "b".repeat(32), 80, NONE))
              .code());
      assertEquals(
          ContentClaimException.Code.RECOVERY_REQUIRED,
          assertThrows(
                  ContentClaimException.class, () -> second.releaseUnpublished(claim.token(), NONE))
              .code());
      assertEquals(
          proof,
          second.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE).proof().orElseThrow());
    }
  }

  @Test
  void unprovenCurrentAttemptCanReleaseButProspectiveProofCannot() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repository = new SqliteCanonicalContentRepository(catalog, store);
      var first = repository.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE);
      repository.markPublishing(first.token(), NONE);
      repository.releaseUnpublished(first.token(), NONE);
      assertTrue(repository.lookup(slot(), NONE).isEmpty());
      var second = repository.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE);
      repository.markPublishing(second.token(), NONE);
      repository.recordVerifiedCompletion(
          second.token(), CanonicalContentRecordsTest.historical(), NONE);
      assertThrows(
          ContentClaimException.class, () -> repository.releaseUnpublished(second.token(), NONE));
    }
  }

  @Test
  void fullMetadataPublicationKeepsOneObjectAndOriginalEvidenceAcrossFacadeAndRestart()
      throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    byte[] bytes = new byte[80];
    var sha =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var historical = CanonicalContentRecordsTest.historical();
    var original =
        new io.vibeminer.domain.VerifiedSourceAlias(
            historical.originalAlias().source(),
            new io.vibeminer.domain.StoredObject(new io.vibeminer.domain.ObjectDigest(sha), 80),
            historical.originalAlias().evidence());
    var decoded =
        new MetadataContentProof(
            original,
            historical.actualEncodingKey(),
            historical.contentKey(),
            historical.declaredDecodedBytes(),
            Optional.of(new DecodedContentAttestation(historical.contentKey(), 64, "decoded-v1")),
            Optional.empty());
    var full =
        decoded.withAttestations(
            decoded.decodedAttestation(),
            Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "root-v1")));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repository = new SqliteCanonicalContentRepository(catalog, store);
      var claim = repository.reserve(slot(), decoded.actualEncodingKey(), 80, NONE);
      repository.markPublishing(claim.token(), NONE);
      store.publishVerified(
          new ByteArrayInputStream(bytes),
          80,
          80,
          token -> repository.recordVerifiedCompletion(claim.token(), decoded, token),
          NONE);
      repository.upgradeProof(claim.token(), full, NONE);
      assertEquals(
          CanonicalReservation.Status.RETAINED,
          repository.completePublication(claim.token(), full, NONE).status());
      var restarted = new SqliteCanonicalContentRepository(catalog, store);
      assertEquals(full, restarted.lookup(slot(), NONE).orElseThrow().proof().orElseThrow());
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertThrows(
                  ContentClaimException.class,
                  () -> restarted.reserve(slot(), "b".repeat(32), 90, NONE))
              .code());
      var selected =
          new io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog(catalog, store);
      assertEquals(
          io.vibeminer.application.selectedfile.SelectedFileException.Code.UNSUPPORTED,
          assertThrows(
                  io.vibeminer.application.selectedfile.SelectedFileException.class,
                  () -> selected.reserve(slot(), decoded.actualEncodingKey(), 80, NONE))
              .code());
      assertEquals(
          original,
          new io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog(
                  catalog, store)
              .findVerifiedSourceAlias(original.source(), NONE)
              .orElseThrow());
      assertEquals(
          ContentClaimException.Code.INVALID_STATE,
          assertThrows(
                  ContentClaimException.class,
                  () -> restarted.upgradeProof(claim.token(), decoded, NONE))
              .code());
      var physical =
          directory.resolve("store/objects").resolve(sha.substring(0, 2)).resolve(sha.substring(2));
      java.nio.file.Files.write(physical, new byte[] {1});
      assertEquals(full, restarted.lookup(slot(), NONE).orElseThrow().proof().orElseThrow());
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  ContentClaimException.class,
                  () -> restarted.completePublication(claim.token(), full, NONE))
              .code());
      java.nio.file.Files.delete(physical);
      assertEquals(
          ContentClaimException.Code.MISSING_LOCAL,
          assertThrows(
                  ContentClaimException.class,
                  () -> restarted.completePublication(claim.token(), full, NONE))
              .code());
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertThrows(
                  ContentClaimException.class,
                  () -> restarted.reserve(slot(), "b".repeat(32), 90, NONE))
              .code());
    }
  }

  @Test
  void selectedAndMetadataAlternateCandidatesHaveExactlyOneDurableWinner() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var metadata = new SqliteCanonicalContentRepository(catalog, store);
      var selected =
          new io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog(catalog, store);
      try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        for (int index = 1; index <= 30; index++) {
          var candidateSlot = new ContentSlotKey("wow", String.format("%032x", index));
          var start = new java.util.concurrent.CountDownLatch(1);
          var one =
              executor.submit(
                  () -> {
                    start.await();
                    try {
                      metadata.reserve(candidateSlot, "a".repeat(32), 80, NONE);
                      return true;
                    } catch (ContentClaimException failure) {
                      assertEquals(ContentClaimException.Code.BUSY, failure.code());
                      return false;
                    }
                  });
          var two =
              executor.submit(
                  () -> {
                    start.await();
                    try {
                      selected.reserve(candidateSlot, "b".repeat(32), 90, NONE);
                      return true;
                    } catch (io.vibeminer.application.selectedfile.SelectedFileException failure) {
                      assertEquals(
                          io.vibeminer.application.selectedfile.SelectedFileException.Code.BUSY,
                          failure.code());
                      return false;
                    }
                  });
          start.countDown();
          assertNotEquals(one.get(), two.get());
          var winner = metadata.lookup(candidateSlot, NONE).orElseThrow();
          assertTrue(
              winner.actualEncodingKey().equals("a".repeat(32))
                  || winner.actualEncodingKey().equals("b".repeat(32)));
        }
      }
    }
  }

  @Test
  void preCancelledReserveHasNoLedgerSideEffect() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var repository = new SqliteCanonicalContentRepository(catalog, store);
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> repository.reserve(slot(), CanonicalContentRecordsTest.E, 80, () -> true));
      assertTrue(repository.lookup(slot(), NONE).isEmpty());
    }
  }

  @Test
  void sharedProcessSessionJoinsWhileDifferentOwnerRequiresRecovery() throws Exception {
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var session = UUID.randomUUID();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var first = new SqliteCanonicalContentRepository(catalog, store, session);
      var same = new SqliteCanonicalContentRepository(catalog, store, session);
      var selected =
          new io.vibeminer.infrastructure.selectedfile.SqliteSelectedFileCatalog(
              catalog, store, session);
      var claim = first.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE);
      first.markPublishing(claim.token(), NONE);
      assertEquals(
          CanonicalReservation.Status.PUBLISHING,
          same.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE).status());
      assertEquals(
          claim.token(), selected.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE).token());
      var another = new SqliteCanonicalContentRepository(catalog, store, UUID.randomUUID());
      assertEquals(
          CanonicalReservation.Status.RECOVERY,
          another.lookup(slot(), NONE).orElseThrow().status());
      assertEquals(
          claim.token(), another.reserve(slot(), CanonicalContentRecordsTest.E, 80, NONE).token());
      assertEquals(
          ContentClaimException.Code.RECOVERY_REQUIRED,
          assertThrows(ContentClaimException.class, () -> first.markPublishing(claim.token(), NONE))
              .code());
    }
  }
}
