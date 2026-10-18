package io.vibeminer.application.content;

import static io.vibeminer.application.content.ContentContractsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataContentAcquisitionTest {
  final FakeRepository repository = new FakeRepository();
  final MetadataContentRequest request =
      new MetadataContentRequest(
          build("wow"),
          configuration(),
          MetadataStructureAttestation.Kind.ROOT,
          new ContentSlotKey("wow", C),
          E,
          80,
          OptionalLong.of(100));
  final MetadataContentProof decoded =
      historical()
          .withAttestations(
              Optional.of(new DecodedContentAttestation(C, 100, "md5.v1")), Optional.empty());
  final MetadataContentProof full =
      decoded.withAttestations(
          decoded.decodedAttestation(),
          Optional.of(
              new MetadataStructureAttestation(MetadataStructureAttestation.Kind.ROOT, "root.v1")));

  @Test
  void prospectiveThenGrammarThenReadyPreservesOriginalReceipt() {
    var result =
        run(
            (observer, token) -> {
              assertEquals(List.of("reserve", "publishing"), repository.events);
              observer.beforePromotion(decoded, token);
              assertEquals("prospective", repository.events.getLast());
              observer.afterStructureValidation(full, token);
              return full.originalAlias();
            });
    assertEquals(full.originalAlias(), result);
    assertEquals(
        List.of("reserve", "publishing", "prospective", "upgrade", "complete"), repository.events);
  }

  @Test
  void unavailableBeforePromotionReleasesButUnknownOrProspectiveFailuresStayExclusive() {
    assertThrows(
        MetadataAcquisitionFailure.class,
        () ->
            run(
                (observer, token) -> {
                  throw new MetadataAcquisitionFailure(
                      MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                      new IllegalStateException("404"));
                }));
    assertEquals("release", repository.events.getLast());
    repository.events.clear();
    repository.proof = null;
    assertThrows(
        IllegalStateException.class,
        () ->
            run(
                (observer, token) -> {
                  throw new IllegalStateException("uncertain");
                }));
    assertEquals("uncertain", repository.events.getLast());
    repository.events.clear();
    repository.proof = null;
    assertThrows(
        MetadataAcquisitionFailure.class,
        () ->
            run(
                (observer, token) -> {
                  observer.beforePromotion(decoded, token);
                  throw new MetadataAcquisitionFailure(
                      MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                      new IllegalStateException("observer boundary"));
                }));
    assertEquals("uncertain", repository.events.getLast());
  }

  @Test
  void missingGrammarOrChangedReturnedReceiptNeverCompletes() {
    assertThrows(
        ContentClaimException.class,
        () ->
            run(
                (observer, token) -> {
                  observer.beforePromotion(decoded, token);
                  return decoded.originalAlias();
                }));
    assertFalse(repository.events.contains("complete"));
    repository.events.clear();
    repository.proof = null;
    assertThrows(
        ContentClaimException.class,
        () ->
            run(
                (observer, token) -> {
                  observer.beforePromotion(decoded, token);
                  observer.afterStructureValidation(full, token);
                  return configuration();
                }));
    assertFalse(repository.events.contains("complete"));
  }

  @Test
  void alternateClaimFailsBeforePayloadOrPublishing() {
    repository.alternate = true;
    assertThrows(
        ContentClaimException.class,
        () ->
            run(
                (observer, token) -> {
                  fail("payload must not start");
                  return null;
                }));
    assertEquals(List.of("reserve"), repository.events);
  }

  @Test
  void retainedProofReplaysLocallyWithoutTransferAndPreservesActualOrigin() {
    repository.proof = full;
    var result =
        new MetadataContentAcquisition(repository)
            .acquire(
                request,
                full.originalAlias().source(),
                () -> Optional.empty(),
                (observer, token) -> {
                  fail("No transfer");
                  return null;
                },
                (alias, observer, token) -> {
                  assertEquals(full.originalAlias(), alias);
                  observer.afterStructureValidation(full, token);
                },
                CancellationToken.none());
    assertEquals(full.originalAlias(), result);
    assertEquals(List.of("reserve", "publishing", "upgrade", "complete"), repository.events);
  }

  @Test
  void freshTransferCannotInventAParserOnlyPublicationAfterPromotion() {
    assertThrows(
        ContentClaimException.class,
        () ->
            run(
                (observer, token) -> {
                  observer.afterStructureValidation(full, token);
                  return full.originalAlias();
                }));
    assertFalse(repository.events.contains("prospective"));
    assertFalse(repository.events.contains("complete"));
    assertEquals("uncertain", repository.events.getLast());
  }

  @Test
  void cancellationDoesNotReleaseUntilActualSourceHasConcludedNonpublication() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var exit = new java.util.concurrent.CountDownLatch(1);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var result =
          workers.submit(
              () ->
                  new MetadataContentAcquisition(repository)
                      .acquire(
                          request,
                          full.originalAlias().source(),
                          Optional::empty,
                          (observer, token) -> {
                            entered.countDown();
                            boolean done = false;
                            while (!done) {
                              try {
                                done = exit.await(5, java.util.concurrent.TimeUnit.SECONDS);
                              } catch (InterruptedException ignored) {
                                /* actual source still running */
                              }
                            }
                            throw new MetadataAcquisitionFailure(
                                MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                                new java.util.concurrent.CancellationException());
                          },
                          (alias, observer, token) -> fail("No replay"),
                          cancelled::get));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      cancelled.set(true);
      assertEquals(List.of("reserve", "publishing"), repository.events);
      exit.countDown();
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> result.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("release", repository.events.getLast());
    } finally {
      exit.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void restartingAfterDecodedProofReplaysAndUpgradesWithoutAnotherTransfer() {
    repository.proof = decoded;
    var result =
        new MetadataContentAcquisition(repository)
            .acquire(
                request,
                full.originalAlias().source(),
                Optional::empty,
                (observer, token) -> {
                  fail("Recovery must use original physical object");
                  return null;
                },
                (alias, observer, token) -> observer.afterStructureValidation(full, token),
                CancellationToken.none());
    assertEquals(full.originalAlias(), result);
    assertEquals(full, repository.proof);
    assertTrue(repository.events.contains("complete"));
  }

  @Test
  void forgedSourceDeclarationIsRejectedBeforeReservation() {
    var forged =
        new ResolvedSourceIdentity(
            "tact.install.v1", full.originalAlias().source().immutableSourceKey(), "root.v1");
    assertThrows(
        ContentClaimException.class,
        () ->
            new MetadataContentAcquisition(repository)
                .acquire(
                    request,
                    forged,
                    Optional::empty,
                    (observer, token) -> {
                      fail("No payload");
                      return null;
                    },
                    (alias, observer, token) -> fail("No replay"),
                    CancellationToken.none()));
    assertTrue(repository.events.isEmpty());
  }

  @Test
  void repeatedVerifierGateAndRestampedOriginAreRejectedBeforeReady() {
    assertThrows(
        ContentClaimException.class,
        () ->
            run(
                (observer, token) -> {
                  observer.beforePromotion(decoded, token);
                  observer.beforePromotion(decoded, token);
                  return full.originalAlias();
                }));
    assertFalse(repository.events.contains("complete"));
    repository.events.clear();
    repository.proof = null;
    var changed =
        new VerifiedSourceAlias(
            full.originalAlias().source(),
            full.object(),
            new SourceVerificationEvidence(
                full.originalAlias().source().verificationSemantics(),
                "https://kr.cdn.blizzard.com/new-route",
                java.time.Instant.EPOCH.plusSeconds(1)));
    var restamped =
        new MetadataContentProof(
            changed,
            E,
            C,
            full.declaredDecodedBytes(),
            full.decodedAttestation(),
            full.structuralAttestation());
    assertThrows(
        ContentClaimException.class,
        () ->
            run(
                (observer, token) -> {
                  observer.beforePromotion(decoded, token);
                  observer.afterStructureValidation(restamped, token);
                  return changed;
                }));
    assertFalse(repository.events.contains("complete"));
  }

  @Test
  void missingCanonicalBytesRemainExclusiveAndNeverTriggerImplicitRepair() {
    repository.proof = full;
    var failure =
        assertThrows(
            ContentClaimException.class,
            () ->
                new MetadataContentAcquisition(repository)
                    .acquire(
                        request,
                        full.originalAlias().source(),
                        Optional::empty,
                        (observer, token) -> {
                          fail("No implicit repair transfer");
                          return null;
                        },
                        (alias, observer, token) -> {
                          throw new ContentClaimException(
                              ContentClaimException.Code.MISSING_LOCAL, "missing");
                        },
                        CancellationToken.none()));
    assertEquals(ContentClaimException.Code.MISSING_LOCAL, failure.code());
    assertEquals("uncertain", repository.events.getLast());
    assertFalse(repository.events.contains("release"));
  }

  @Test
  void independentWorkerCannotEnterOrRetireAnotherWorkersClaimEvenAfterCancellation()
      throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var exit = new java.util.concurrent.CountDownLatch(1);
    var cancellation = new java.util.concurrent.atomic.AtomicBoolean();
    var secondTransfers = new java.util.concurrent.atomic.AtomicInteger();
    var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var first =
          workers.submit(
              () ->
                  new MetadataContentAcquisition(repository)
                      .acquire(
                          request,
                          full.originalAlias().source(),
                          Optional::empty,
                          (observer, token) -> {
                            entered.countDown();
                            boolean finished = false;
                            while (!finished) {
                              try {
                                finished = exit.await(5, java.util.concurrent.TimeUnit.SECONDS);
                              } catch (InterruptedException ignored) {
                                /* source cleanup still active */
                              }
                            }
                            throw new MetadataAcquisitionFailure(
                                MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                                new java.util.concurrent.CancellationException());
                          },
                          (alias, observer, token) -> fail("No replay"),
                          cancellation::get));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var owner = repository.active;
      cancellation.set(true);
      var failure =
          assertThrows(
              ContentClaimException.class,
              () ->
                  run(
                      (observer, token) -> {
                        secondTransfers.incrementAndGet();
                        throw new MetadataAcquisitionFailure(
                            MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                            new IllegalStateException("second worker failure"));
                      }));
      assertEquals(ContentClaimException.Code.BUSY, failure.code());
      assertEquals(0, secondTransfers.get());
      assertEquals(owner, repository.active);
      assertEquals(0, repository.finishes);
      assertFalse(repository.events.contains("release"));
      exit.countDown();
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> first.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertNull(repository.active);
      assertEquals(1, repository.finishes);
      assertEquals("release", repository.events.getLast());
    } finally {
      exit.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void staleWorkerFailureCannotRetireTheReplacementOwnerOrFallBackToTokenCleanup() {
    var replacement = new java.util.concurrent.atomic.AtomicReference<ContentWorkerPermit>();
    var failure =
        assertThrows(
            ContentClaimException.class,
            () ->
                run(
                    (observer, token) -> {
                      var newer = new ContentWorkerPermit(repository.id, UUID.randomUUID());
                      repository.active = newer;
                      replacement.set(newer);
                      observer.beforePromotion(decoded, token);
                      return full.originalAlias();
                    }));
    assertEquals(ContentClaimException.Code.INVALID_STATE, failure.code());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals(replacement.get(), repository.active);
    assertEquals(0, repository.finishes);
    assertFalse(repository.events.contains("release"));
    assertFalse(repository.events.contains("uncertain"));
  }

  @Test
  void unexpectedVerifierErrorStillRetiresOnlyItsOwnPermitConservatively() {
    var fatal = new AssertionError("verifier failed unexpectedly");
    assertSame(
        fatal,
        assertThrows(
            AssertionError.class,
            () ->
                run(
                    (observer, token) -> {
                      throw fatal;
                    })));
    assertEquals("uncertain", repository.events.getLast());
    assertEquals(1, repository.finishes);
    assertNull(repository.active);
  }

  @Test
  void workerAdmissionRefreshesProofCompletedAfterTheInitialReservationRead() {
    repository.publishBeforeWorkerAdmission = true;
    var result =
        new MetadataContentAcquisition(repository)
            .acquire(
                request,
                full.originalAlias().source(),
                Optional::empty,
                (observer, token) -> {
                  fail("Stale empty reservation must not start another payload");
                  return null;
                },
                (alias, observer, token) -> {
                  assertEquals(full.originalAlias(), alias);
                  observer.afterStructureValidation(full, token);
                },
                CancellationToken.none());
    assertEquals(full.originalAlias(), result);
    assertEquals(1, repository.finishes);
    assertNull(repository.active);
  }

  @Test
  void conflictingIndependentDecodedLengthFailsIntegrityBeforeWorkerAdmission() {
    repository.proof = full;
    var conflicting =
        new MetadataContentRequest(
            request.build(),
            request.configuration(),
            request.kind(),
            request.slot(),
            E,
            80,
            OptionalLong.of(200));
    var source =
        new ResolvedSourceIdentity(
            full.originalAlias().source().namespace(),
            C + ";" + E + ";200;80",
            full.originalAlias().source().verificationSemantics());
    var failed =
        assertThrows(
            ContentClaimException.class,
            () ->
                new MetadataContentAcquisition(repository)
                    .acquire(
                        conflicting,
                        source,
                        Optional::empty,
                        (observer, token) -> {
                          fail("No payload");
                          return null;
                        },
                        (alias, observer, token) -> fail("No replay"),
                        CancellationToken.none()));
    assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, failed.code());
    assertNull(repository.active);
    assertEquals(0, repository.finishes);
  }

  @Test
  void completeManifestKindsUseExactSourceNamespaceAndCanonicalWorkerLifecycle() {
    var namespaces =
        Map.of(
            "ENCODING",
            "tact.encoding.v1",
            "DOWNLOAD",
            "tact.download.v1v2v3",
            "DOWNLOAD_SIZE",
            "tact.download-size.v1");
    for (var entry : namespaces.entrySet()) {
      repository.events.clear();
      repository.proof = null;
      var kind = MetadataStructureAttestation.Kind.valueOf(entry.getKey());
      var declaration =
          new MetadataContentRequest(
              request.build(),
              request.configuration(),
              kind,
              request.slot(),
              E,
              80,
              OptionalLong.of(100));
      var source = new ResolvedSourceIdentity(entry.getValue(), C + ";" + E + ";100;80", "full-v1");
      var alias =
          new VerifiedSourceAlias(
              source,
              full.object(),
              new SourceVerificationEvidence(
                  "full-v1", "https://example.test/data", java.time.Instant.EPOCH));
      var prospective =
          new MetadataContentProof(
              alias,
              E,
              C,
              OptionalLong.of(100),
              Optional.of(new DecodedContentAttestation(C, 100, "full-md5-v1")),
              Optional.empty());
      var complete =
          prospective.withAttestations(
              prospective.decodedAttestation(),
              Optional.of(new MetadataStructureAttestation(kind, "parser-v1")));
      var actual =
          new MetadataContentAcquisition(repository)
              .acquire(
                  declaration,
                  source,
                  Optional::empty,
                  (observer, token) -> {
                    observer.beforePromotion(prospective, token);
                    observer.afterStructureValidation(complete, token);
                    return alias;
                  },
                  (retained, observer, token) -> fail("No retained object"),
                  CancellationToken.none());
      assertEquals(alias, actual);
      assertEquals("complete", repository.events.getLast());
      repository.events.clear();
      assertThrows(
          ContentClaimException.class,
          () ->
              new MetadataContentAcquisition(repository)
                  .acquire(
                      declaration,
                      full.originalAlias().source(),
                      Optional::empty,
                      (observer, token) -> fail("Wrong namespace cannot transfer"),
                      (retained, observer, token) -> fail("Wrong namespace cannot replay"),
                      CancellationToken.none()));
      assertTrue(repository.events.isEmpty());
    }
  }

  @Test
  void unknownRequestedDecodedLengthStillRequiresCanonicalPositiveSourceLengthBeforeReserve() {
    for (var kind : MetadataStructureAttestation.Kind.values()) {
      String namespace =
          switch (kind) {
            case ROOT -> "wow.root.tsfm.v1v2";
            case INSTALL -> "tact.install.v1";
            case ENCODING -> "tact.encoding.v1";
            case DOWNLOAD -> "tact.download.v1v2v3";
            case DOWNLOAD_SIZE -> "tact.download-size.v1";
          };
      var declaration =
          new MetadataContentRequest(
              request.build(),
              request.configuration(),
              kind,
              request.slot(),
              E,
              80,
              OptionalLong.empty());
      for (var invalid : List.of("0", "-1", "", "text", "01", "9223372036854775808")) {
        repository.events.clear();
        var source =
            new ResolvedSourceIdentity(namespace, C + ";" + E + ";" + invalid + ";80", "v1");
        var failure =
            assertThrows(
                ContentClaimException.class,
                () ->
                    new MetadataContentAcquisition(repository)
                        .acquire(
                            declaration,
                            source,
                            Optional::empty,
                            (observer, token) -> fail("Invalid decoded source cannot transfer"),
                            (retained, observer, token) ->
                                fail("Invalid decoded source cannot replay"),
                            CancellationToken.none()));
        assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, failure.code());
        assertTrue(repository.events.isEmpty());
      }
    }
  }

  VerifiedSourceAlias run(MetadataContentAcquisition.Transfer transfer) {
    return new MetadataContentAcquisition(repository)
        .acquire(
            request,
            full.originalAlias().source(),
            Optional::empty,
            transfer,
            (alias, observer, token) -> fail("No local replay"),
            CancellationToken.none());
  }

  final class FakeRepository implements CanonicalContentRepository {
    final List<String> events = new ArrayList<>();
    final UUID id = UUID.randomUUID();
    CanonicalContentProof proof;
    boolean alternate;
    volatile ContentWorkerPermit active;
    int finishes;
    boolean publishBeforeWorkerAdmission;

    ContentClaim claim() {
      return new ContentClaim(
          request.slot(),
          E,
          80,
          id,
          CanonicalReservation.Status.PUBLISHING,
          Optional.ofNullable(proof));
    }

    public Optional<ContentClaim> lookup(ContentSlotKey slot, CancellationToken token) {
      return Optional.of(claim());
    }

    public ContentClaim reserve(
        ContentSlotKey slot, String key, long length, CancellationToken token) {
      events.add("reserve");
      if (alternate)
        throw new ContentClaimException(ContentClaimException.Code.REPLAN_REQUIRED, "A owns slot");
      return claim();
    }

    public synchronized ContentWorkerPermit claimWorker(
        UUID token, CancellationToken cancellation) {
      if (active != null)
        throw new ContentClaimException(
            ContentClaimException.Code.BUSY, "Actual source worker active");
      active = new ContentWorkerPermit(token, UUID.randomUUID());
      if (publishBeforeWorkerAdmission) proof = full;
      return active;
    }

    void require(ContentWorkerPermit permit) {
      if (!permit.equals(active))
        throw new ContentClaimException(ContentClaimException.Code.INVALID_STATE, "Stale worker");
    }

    public synchronized void markPublishing(ContentWorkerPermit permit, CancellationToken token) {
      require(permit);
      events.add("publishing");
    }

    public synchronized void recordVerifiedCompletion(
        ContentWorkerPermit permit, CanonicalContentProof value, CancellationToken token) {
      require(permit);
      events.add("prospective");
      proof = value;
    }

    public synchronized ContentClaim upgradeProof(
        ContentWorkerPermit permit, CanonicalContentProof value, CancellationToken token) {
      require(permit);
      events.add("upgrade");
      proof = value;
      return claim();
    }

    public synchronized ContentClaim completePublication(
        ContentWorkerPermit permit, CanonicalContentProof value, CancellationToken token) {
      require(permit);
      events.add("complete");
      proof = value;
      return new ContentClaim(
          request.slot(), E, 80, id, CanonicalReservation.Status.RETAINED, Optional.of(value));
    }

    public synchronized void finishWorker(
        ContentWorkerPermit permit, ContentWorkerFinishOutcome outcome, CancellationToken token) {
      require(permit);
      finishes++;
      active = null;
      switch (outcome) {
        case CONCLUSIVELY_UNPUBLISHED -> events.add("release");
        case UNCERTAIN -> events.add("uncertain");
        case RETAINED -> {}
      }
    }

    public void markPublishing(UUID id, CancellationToken token) {
      throw new AssertionError("Bare UUID mutation");
    }

    public void recordVerifiedCompletion(
        UUID id, CanonicalContentProof value, CancellationToken token) {
      throw new AssertionError("Bare UUID mutation");
    }

    public ContentClaim upgradeProof(
        UUID id, CanonicalContentProof value, CancellationToken token) {
      throw new AssertionError("Bare UUID mutation");
    }

    public ContentClaim completePublication(
        UUID id, CanonicalContentProof value, CancellationToken token) {
      throw new AssertionError("Bare UUID mutation");
    }

    public void releaseUnpublished(UUID id, CancellationToken token) {
      throw new AssertionError("Bare UUID cleanup");
    }

    public void markUncertain(UUID id, CancellationToken token) {
      throw new AssertionError("Bare UUID cleanup");
    }
  }
}
