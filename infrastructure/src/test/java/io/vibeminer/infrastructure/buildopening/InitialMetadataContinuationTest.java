package io.vibeminer.infrastructure.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitialMetadataContinuationTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  @Test
  void guardAuthorizesOnlyMissingUnreferencedDecodedOnlyOriginalUnderActualNonce()
      throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      var claim = f.canonical.lookup(f.request.slot(), NONE).orElseThrow();
      var permit = f.canonical.claimWorker(claim.token(), NONE);
      try {
        f.canonical.markPublishing(permit, NONE);
        f.canonical.authorizeInitialMissingContinuation(permit, f.request, original, NONE);
        assertEquals(
            original,
            f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow());
      } finally {
        f.canonical.finishWorker(permit, ContentWorkerFinishOutcome.UNCERTAIN, NONE);
      }
    }
  }

  @Test
  void explicitRetryCompletesSameOriginalAfterProspectiveCancellationForAllThreeKinds()
      throws Exception {
    for (var kind :
        java.util.List.of(
            MetadataStructureAttestation.Kind.ENCODING,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            MetadataStructureAttestation.Kind.DOWNLOAD_SIZE)) {
      try (var f =
          new OfficialBuildMetadataSourceWorkerTest.Fixture(
              directory.resolve(kind.name()),
              kind,
              OfficialBuildMetadataSourceWorkerTest.metadata(kind))) {
        var original = interruptAfterProspective(f);
        int before = f.client.requests.get();
        var later =
            java.time.Clock.offset(
                OfficialBuildMetadataSourceWorkerTest.CLOCK, java.time.Duration.ofDays(1));
        var retry =
            new OfficialBuildMetadataSourceWorker(
                f.store,
                f.canonical,
                f.builds,
                new io.vibeminer.infrastructure.acquisition.PinnedCdnRangeTransport(
                    f.client, later, java.time.Duration.ofSeconds(2)),
                later);
        var alias = retry.acquire(f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE);
        assertEquals(original.originalAlias(), alias);
        var claim = f.canonical.lookup(f.request.slot(), NONE).orElseThrow();
        assertEquals(
            io.vibeminer.domain.selectedfile.CanonicalReservation.Status.RETAINED, claim.status());
        var completed = (MetadataContentProof) claim.proof().orElseThrow();
        assertEquals(original.originalAlias(), completed.originalAlias());
        assertEquals(original.decodedAttestation(), completed.decodedAttestation());
        assertTrue(completed.structuralAttestation().isPresent());
        assertTrue(f.client.requests.get() > before);
      }
    }
  }

  @Test
  void anyCommittedPhysicalHistoryBlocksWithoutPayloadRequests() throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      try (var c = f.sql.connect();
          var q = c.prepareStatement("INSERT INTO catalog_objects(digest,length) VALUES(?,?)")) {
        q.setString(1, original.object().digest().value());
        q.setLong(2, original.object().length());
        q.executeUpdate();
      }
      int before = f.client.requests.get();
      var failure =
          assertThrows(
              ContentClaimException.class,
              () ->
                  f.worker.acquire(
                      f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE));
      assertEquals(ContentClaimException.Code.RECOVERY_REQUIRED, failure.code());
      assertEquals(before, f.client.requests.get());
      assertEquals(
          original, f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow());
    }
  }

  @Test
  void boundedGlobalScanRefusesUnknownOrTooManyHeadersBeforeGet() throws Exception {
    for (boolean malformed : java.util.List.of(false, true)) {
      try (var f =
          new OfficialBuildMetadataSourceWorkerTest.Fixture(
              directory.resolve("scan" + malformed),
              MetadataStructureAttestation.Kind.ENCODING,
              OfficialBuildMetadataSourceWorkerTest.metadata(
                  MetadataStructureAttestation.Kind.ENCODING))) {
        interruptAfterProspective(f);
        try (var c = f.sql.connect();
            var q =
                c.prepareStatement(
                    "INSERT INTO"
                        + " canonical_content_slots(namespace,content_key,encoding_key,encoded_bytes,token,status,owner_session,representation)"
                        + " VALUES('wow',?, ?,1,?,'PENDING',?,?)")) {
          int total = malformed ? 1 : 1024;
          for (int n = 0; n < total; n++) {
            q.setString(1, String.format("%032x", n));
            q.setString(2, "a".repeat(32));
            q.setString(3, java.util.UUID.randomUUID().toString());
            q.setString(4, java.util.UUID.randomUUID().toString());
            if (malformed) q.setBytes(5, new byte[] {1, 2, 3});
            else q.setNull(5, java.sql.Types.BLOB);
            q.addBatch();
          }
          q.executeBatch();
        }
        int before = f.client.requests.get();
        var failed =
            assertThrows(
                ContentClaimException.class,
                () ->
                    f.worker.acquire(
                        f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE));
        assertEquals(
            malformed
                ? ContentClaimException.Code.INTEGRITY_FAILURE
                : ContentClaimException.Code.UNSUPPORTED_REUSE,
            failed.code());
        assertEquals(before, f.client.requests.get());
      }
    }
  }

  @Test
  void anotherProspectivePhysicalReferenceBlocksWithoutRewritingEitherProof() throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      try (var c = f.sql.connect();
          var q =
              c.prepareStatement(
                  "INSERT INTO canonical_legacy_conflicts(namespace,content_key,proof)"
                      + " VALUES('wow',?,?)")) {
        q.setString(1, "f".repeat(32));
        q.setBytes(2, io.vibeminer.infrastructure.content.CanonicalContentRecords.encode(original));
        q.executeUpdate();
      }
      int before = f.client.requests.get();
      assertEquals(
          ContentClaimException.Code.RECOVERY_REQUIRED,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      f.worker.acquire(
                          f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE))
              .code());
      assertEquals(before, f.client.requests.get());
    }
  }

  @Test
  void missingPreviouslyRetainedAndCorruptProspectiveBytesNeverTriggerTransfer() throws Exception {
    for (boolean retained : java.util.List.of(false, true)) {
      Path root = directory.resolve("protected" + retained);
      try (var f =
          new OfficialBuildMetadataSourceWorkerTest.Fixture(
              root,
              MetadataStructureAttestation.Kind.ENCODING,
              OfficialBuildMetadataSourceWorkerTest.metadata(
                  MetadataStructureAttestation.Kind.ENCODING))) {
        MetadataContentProof original;
        if (retained) {
          f.worker.acquire(f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE);
          original =
              (MetadataContentProof)
                  f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow();
        } else original = interruptAfterProspective(f);
        String digest = original.object().digest().value();
        Path object =
            root.resolve("store/objects")
                .resolve(digest.substring(0, 2))
                .resolve(digest.substring(2));
        if (retained) java.nio.file.Files.delete(object);
        else {
          java.nio.file.Files.createDirectories(object.getParent());
          java.nio.file.Files.write(object, new byte[] {1, 2, 3});
        }
        int before = f.client.requests.get();
        assertThrows(
            RuntimeException.class,
            () ->
                f.worker.acquire(f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE));
        assertEquals(before, f.client.requests.get());
        assertEquals(
            original,
            f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow());
      }
    }
  }

  @Test
  void healthyProspectiveObjectFinishesLocallyWithoutAnotherGet() throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      f.store.publish(
          new java.io.ByteArrayInputStream(f.encoded),
          new io.vibeminer.domain.ExpectedObjectChecksum(
              io.vibeminer.domain.ChecksumAlgorithm.SHA256,
              original.object().digest().value(),
              original.object().length()),
          original.object().length(),
          NONE);
      int before = f.client.requests.get();
      assertEquals(
          original.originalAlias(),
          f.worker.acquire(f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE));
      assertEquals(before, f.client.requests.get());
    }
  }

  @Test
  void continuationGateRechecksGlobalHistoryCreatedDuringTransfer() throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      var once = new AtomicBoolean();
      f.client.beforeRequest =
          count -> {
            if (!once.compareAndSet(false, true)) return;
            try (var c = f.sql.connect();
                var q =
                    c.prepareStatement("INSERT INTO catalog_objects(digest,length) VALUES(?,?)")) {
              q.setString(1, original.object().digest().value());
              q.setLong(2, original.object().length());
              q.executeUpdate();
            } catch (java.sql.SQLException failed) {
              throw new AssertionError(failed);
            }
          };
      assertThrows(
          MetadataAcquisitionFailure.class,
          () -> f.worker.acquire(f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE));
      assertTrue(f.store.verify(original.object().digest(), NONE).isEmpty());
      assertEquals(
          original, f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow());
    }
  }

  @Test
  void staleWorkerNonceAndUnsupportedHistoricalCapabilityCannotAuthorizeContinuation()
      throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      var claim = f.canonical.lookup(f.request.slot(), NONE).orElseThrow();
      var permit = f.canonical.claimWorker(claim.token(), NONE);
      try {
        f.canonical.markPublishing(permit, NONE);
        var stale = new ContentWorkerPermit(permit.reservationToken(), java.util.UUID.randomUUID());
        assertThrows(
            ContentClaimException.class,
            () ->
                f.canonical.authorizeInitialMissingContinuation(stale, f.request, original, NONE));
        var historical =
            new MetadataContentProof(
                original.originalAlias(),
                original.actualEncodingKey(),
                original.declaredContentKey(),
                original.declaredDecodedBytes(),
                java.util.Optional.of(
                    new DecodedContentAttestation(
                        original.declaredContentKey(),
                        original.decodedAttestation().orElseThrow().actualDecodedBytes(),
                        "unsupported-old-revision")),
                java.util.Optional.empty());
        assertEquals(
            ContentClaimException.Code.UNSUPPORTED_REUSE,
            assertThrows(
                    ContentClaimException.class,
                    () ->
                        f.canonical.authorizeInitialMissingContinuation(
                            permit, f.request, historical, NONE))
                .code());
      } finally {
        f.canonical.finishWorker(permit, ContentWorkerFinishOutcome.UNCERTAIN, NONE);
      }
    }
  }

  @Test
  void aggregateSerializedProofWorkIsBoundedBelowTheHeaderCountCeiling() throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      try (var c = f.sql.connect();
          var q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " canonical_content_slots(namespace,content_key,encoding_key,encoded_bytes,token,status,owner_session,representation)"
                      + " VALUES('wow',?,?,?,?,'PENDING',?,?)")) {
        for (int n = 0; n < 900; n++) {
          String ck = String.format("%032x", n + 10000);
          var alias =
              new io.vibeminer.domain.VerifiedSourceAlias(
                  new io.vibeminer.domain.ResolvedSourceIdentity(
                      "tact.encoding.v1",
                      ck
                          + ";"
                          + original.actualEncodingKey()
                          + ";"
                          + original.decodedAttestation().orElseThrow().actualDecodedBytes()
                          + ";"
                          + original.object().length(),
                      "v".repeat(4096)),
                  new io.vibeminer.domain.StoredObject(
                      new io.vibeminer.domain.ObjectDigest("e".repeat(64)),
                      original.object().length()),
                  new io.vibeminer.domain.SourceVerificationEvidence(
                      "v".repeat(4096),
                      "https://example.com/" + "a".repeat(2000),
                      java.time.Instant.EPOCH));
          var proof =
              new MetadataContentProof(
                  alias,
                  original.actualEncodingKey(),
                  ck,
                  original.declaredDecodedBytes(),
                  java.util.Optional.of(
                      new DecodedContentAttestation(
                          ck,
                          original.decodedAttestation().orElseThrow().actualDecodedBytes(),
                          "other-verifier")),
                  java.util.Optional.empty());
          q.setString(1, ck);
          q.setString(2, proof.actualEncodingKey());
          q.setLong(3, proof.object().length());
          q.setString(4, java.util.UUID.randomUUID().toString());
          q.setString(5, java.util.UUID.randomUUID().toString());
          q.setBytes(6, io.vibeminer.infrastructure.content.CanonicalContentRecords.encode(proof));
          q.addBatch();
        }
        q.executeBatch();
      }
      int before = f.client.requests.get();
      assertEquals(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          assertThrows(
                  ContentClaimException.class,
                  () ->
                      f.worker.acquire(
                          f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE))
              .code());
      assertEquals(before, f.client.requests.get());
    }
  }

  @Test
  void repeatedCancellationNeverResetsOriginalProspectiveEvidence() throws Exception {
    try (var f = fixture()) {
      var first = interruptAfterProspective(f);
      var second = interruptAfterProspective(f);
      assertEquals(first, second);
      assertEquals(
          first.originalAlias(),
          f.worker.acquire(f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE));
    }
  }

  @Test
  void freshTransferMustMatchTheExactOriginalPhysicalShaBeforePromotion() throws Exception {
    try (var f = fixture()) {
      var original = interruptAfterProspective(f);
      var expectedAlias =
          new io.vibeminer.domain.VerifiedSourceAlias(
              original.originalAlias().source(),
              new io.vibeminer.domain.StoredObject(
                  new io.vibeminer.domain.ObjectDigest("f".repeat(64)), original.object().length()),
              original.originalAlias().evidence());
      var expected =
          new MetadataContentProof(
              expectedAlias,
              original.actualEncodingKey(),
              original.declaredContentKey(),
              original.declaredDecodedBytes(),
              original.decodedAttestation(),
              original.structuralAttestation());
      try (var c = f.sql.connect();
          var q =
              c.prepareStatement(
                  "UPDATE canonical_content_slots SET representation=? WHERE content_key=?")) {
        q.setBytes(1, io.vibeminer.infrastructure.content.CanonicalContentRecords.encode(expected));
        q.setString(2, original.contentKey());
        q.executeUpdate();
      }
      int before = f.client.requests.get();
      assertThrows(
          MetadataAcquisitionFailure.class,
          () -> f.worker.acquire(f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), NONE));
      assertTrue(f.client.requests.get() > before);
      assertTrue(f.store.verify(original.object().digest(), NONE).isEmpty());
      assertTrue(f.store.verify(expected.object().digest(), NONE).isEmpty());
      assertEquals(
          expected, f.canonical.lookup(f.request.slot(), NONE).orElseThrow().proof().orElseThrow());
    }
  }

  OfficialBuildMetadataSourceWorkerTest.Fixture fixture() throws Exception {
    var kind = MetadataStructureAttestation.Kind.ENCODING;
    return new OfficialBuildMetadataSourceWorkerTest.Fixture(
        directory, kind, OfficialBuildMetadataSourceWorkerTest.metadata(kind));
  }

  static MetadataContentProof interruptAfterProspective(
      OfficialBuildMetadataSourceWorkerTest.Fixture f) {
    var canceled = new AtomicBoolean();
    var observed =
        (CanonicalContentRepository)
            Proxy.newProxyInstance(
                CanonicalContentRepository.class.getClassLoader(),
                new Class<?>[] {CanonicalContentRepository.class},
                (proxy, method, args) -> {
                  Object result;
                  try {
                    result = method.invoke(f.canonical, args);
                  } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                  }
                  if (method.getName().equals("recordVerifiedCompletion")) canceled.set(true);
                  return result;
                });
    var worker =
        new OfficialBuildMetadataSourceWorker(
            f.store,
            observed,
            f.builds,
            new io.vibeminer.infrastructure.acquisition.PinnedCdnRangeTransport(
                f.client,
                OfficialBuildMetadataSourceWorkerTest.CLOCK,
                java.time.Duration.ofSeconds(2)),
            OfficialBuildMetadataSourceWorkerTest.CLOCK);
    assertThrows(
        MetadataAcquisitionFailure.class,
        () ->
            worker.acquire(
                f.request, OfficialBuildMetadataSourceWorkerTest.budgets(), canceled::get));
    var claim = f.canonical.lookup(f.request.slot(), NONE).orElseThrow();
    assertEquals(
        io.vibeminer.domain.selectedfile.CanonicalReservation.Status.RECOVERY, claim.status());
    var proof = (MetadataContentProof) claim.proof().orElseThrow();
    assertTrue(proof.decodedAttestation().isPresent());
    assertTrue(proof.structuralAttestation().isEmpty());
    assertTrue(f.store.verify(proof.object().digest(), NONE).isEmpty());
    return proof;
  }
}
