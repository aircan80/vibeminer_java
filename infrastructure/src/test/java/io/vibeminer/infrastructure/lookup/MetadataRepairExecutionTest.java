package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataRepairExecutionTest {
  @TempDir Path directory;

  @Test
  void rootRepairsOriginalObjectAndSeparatesProspectiveFromGrammar() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      f.rootFixture.rejectNetwork = false;
      int initialRequests = f.rootFixture.requests.size();
      var executor =
          new OfficialMetadataRepairExecutionGateway(f.gateway, f.store, f.rootFixture, f.clock);
      var attempt = UUID.randomUUID();
      var observed = new ArrayList<MetadataRepairObservation>();
      var result =
          executor.repairRoot(
              f.selection,
              issued,
              attempt,
              new MetadataRepairObserver() {
                public void beforePromotion(
                    MetadataRepairObservation observation, CancellationToken token) {
                  assertTrue(observation.structuralAttestation().isEmpty());
                  assertThrows(
                      ObjectStoreException.class,
                      () -> f.store.openRead(observation.originalProof().object().digest(), token));
                  observed.add(observation);
                }

                public void afterStructureValidation(
                    MetadataRepairObservation observation, CancellationToken token) {
                  assertEquals(
                      MetadataStructureAttestation.Kind.ROOT,
                      observation.structuralAttestation().orElseThrow().kind());
                  observed.add(observation);
                }
              },
              CancellationToken.none());
      assertEquals(2, observed.size());
      assertEquals(result, observed.getLast());
      assertEquals(attempt, result.repairAttemptId());
      assertSame(issued.originalProof(), result.originalProof());
      assertEquals(issued.originalProof().object().length(), result.payloadResponseBytes());
      assertEquals(f.rootFixture.requests.size() - initialRequests, result.payloadRequestCount());
      assertEquals(
          issued.originalProof().object(),
          f.store
              .verify(issued.originalProof().object().digest(), CancellationToken.none())
              .orElseThrow());
    }
  }

  @Test
  void installRestoresCorruptionAndRepeatsIdenticalCompleteObservation() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      f.client.length = f.install.encodedBytes();
      var issued =
          f.gateway.prepareInstall(
              f.install, f.installConfiguration, f.installClaim, CancellationToken.none());
      byte[] encoded = installBytes();
      var object = issued.originalProof().object();
      var path =
          directory
              .resolve("objects")
              .resolve(object.digest().value().substring(0, 2))
              .resolve(object.digest().value().substring(2));
      java.nio.file.Files.createDirectories(path.getParent());
      byte[] corrupt = encoded.clone();
      corrupt[corrupt.length - 1] ^= 1;
      java.nio.file.Files.write(path, corrupt);
      var observations = new ArrayList<MetadataRepairObservation>();
      var executor =
          new OfficialMetadataRepairExecutionGateway(
              f.gateway, f.store, installTransport(encoded), f.clock);
      var result =
          executor.repairInstall(
              f.install,
              issued,
              UUID.randomUUID(),
              collecting(observations),
              CancellationToken.none());
      assertEquals(2, observations.size());
      assertSame(observations.getFirst(), observations.getLast());
      assertEquals(
          MetadataStructureAttestation.Kind.INSTALL,
          result.structuralAttestation().orElseThrow().kind());
      assertSame(issued.originalProof(), result.originalProof());
      assertEquals(
          java.time.Instant.EPOCH, result.originalProof().originalAlias().evidence().verifiedAt());
      assertEquals(f.clock.instant(), result.actualTransfer().evidence().verifiedAt());
      assertArrayEquals(encoded, java.nio.file.Files.readAllBytes(path));
      assertEquals(1, result.payloadRequestCount());
    }
  }

  @Test
  void prospectiveThrowCannotPromoteAndPostgrammarThrowIsUncertain() throws Exception {
    for (boolean after : new boolean[] {false, true}) {
      try (var f =
          new MetadataRepairPlanningTest.Fixture(directory.resolve(Boolean.toString(after)))) {
        var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
        f.rootFixture.rejectNetwork = false;
        var executor =
            new OfficialMetadataRepairExecutionGateway(f.gateway, f.store, f.rootFixture, f.clock);
        var failure =
            assertThrows(
                MetadataAcquisitionFailure.class,
                () ->
                    executor.repairRoot(
                        f.selection,
                        issued,
                        UUID.randomUUID(),
                        new MetadataRepairObserver() {
                          public void beforePromotion(
                              MetadataRepairObservation observation, CancellationToken token) {
                            if (!after)
                              throw new IllegalStateException("audit persisted then failed");
                          }

                          public void afterStructureValidation(
                              MetadataRepairObservation observation, CancellationToken token) {
                            throw new IllegalStateException("grammar audit failed");
                          }
                        },
                        CancellationToken.none()));
        assertEquals(
            after
                ? MetadataAcquisitionFailure.Publication.UNCERTAIN
                : MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
            failure.publication());
        if (after)
          assertTrue(
              f.store
                  .verify(issued.originalProof().object().digest(), CancellationToken.none())
                  .isPresent());
        else
          assertTrue(
              f.store
                  .verify(issued.originalProof().object().digest(), CancellationToken.none())
                  .isEmpty());
      }
    }
  }

  @Test
  void cancellationImmediatelyAfterProspectiveStopsPromotion() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      f.rootFixture.rejectNetwork = false;
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      var executor =
          new OfficialMetadataRepairExecutionGateway(f.gateway, f.store, f.rootFixture, f.clock);
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  executor.repairRoot(
                      f.selection,
                      issued,
                      UUID.randomUUID(),
                      new MetadataRepairObserver() {
                        public void beforePromotion(
                            MetadataRepairObservation o, CancellationToken t) {
                          cancelled.set(true);
                        }

                        public void afterStructureValidation(
                            MetadataRepairObservation o, CancellationToken t) {
                          fail("no grammar after cancellation");
                        }
                      },
                      cancelled::get));
      assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
      assertInstanceOf(java.util.concurrent.CancellationException.class, failure.failure());
      assertTrue(
          f.store
              .verify(issued.originalProof().object().digest(), CancellationToken.none())
              .isEmpty());
    }
  }

  @Test
  void wrongOriginalPhysicalHashCannotReachProspectiveGate() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      byte[] wrong = f.rootFixture.encodedRoot.clone();
      wrong[wrong.length - 1] ^= 1;
      var claim =
          MetadataRepairPlanningTest.claim(
              ((MetadataContentProof) f.rootClaim.proof().orElseThrow()).originalAlias().source(),
              f.rootFixture.rootCKey,
              f.rootFixture.rootEKey,
              f.rootFixture.root.length,
              wrong);
      var issued = f.gateway.prepareRoot(f.selection, claim, CancellationToken.none());
      f.rootFixture.rejectNetwork = false;
      var calls = new ArrayList<MetadataRepairObservation>();
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  new OfficialMetadataRepairExecutionGateway(
                          f.gateway, f.store, f.rootFixture, f.clock)
                      .repairRoot(
                          f.selection,
                          issued,
                          UUID.randomUUID(),
                          collecting(calls),
                          CancellationToken.none()));
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertTrue(calls.isEmpty());
      assertTrue(
          f.store
              .verify(issued.originalProof().object().digest(), CancellationToken.none())
              .isEmpty());
    }
  }

  @Test
  void forgedConsentAndExpiredRootSessionCauseZeroPayload() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
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
      int initial = f.rootFixture.requests.size();
      var executor =
          new OfficialMetadataRepairExecutionGateway(f.gateway, f.store, f.rootFixture, f.clock);
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  executor.repairRoot(
                      f.selection,
                      forged,
                      UUID.randomUUID(),
                      collecting(new ArrayList<>()),
                      CancellationToken.none()));
      assertInstanceOf(MetadataRepairException.class, failure.failure());
      f.nanos.addAndGet(java.time.Duration.ofMinutes(6).toNanos());
      assertThrows(
          MetadataAcquisitionFailure.class,
          () ->
              executor.repairRoot(
                  f.selection,
                  issued,
                  UUID.randomUUID(),
                  collecting(new ArrayList<>()),
                  CancellationToken.none()));
      assertEquals(initial, f.rootFixture.requests.size());
    }
  }

  @Test
  void rootCkeyMismatchPreventsPromotionAndMalformedGrammarIsUncertain() throws Exception {
    for (boolean wrongCkey : new boolean[] {true, false}) {
      try (var f = new MetadataRepairPlanningTest.Fixture(directory.resolve("root-" + wrongCkey))) {
        var source = new RootLookupFixture(wrongCkey, !wrongCkey);
        try (var roots = new OfficialFileLookupGateway(f.store, source, f.clock);
            var issuer =
                new OfficialMetadataRepairGateway(f.installs, roots, f.metadata, f.clock)) {
          var plan =
              roots.prepareRoot(
                  source.build("us.cdn.blizzard.com", "2.0"),
                  Optional.empty(),
                  io.vibeminer.application.lookup.LookupBudgets.defaults(),
                  CancellationToken.none());
          var selection =
              new io.vibeminer.application.lookup.RootSelectionPlan(
                  plan, plan.candidates().getFirst());
          var claim =
              MetadataRepairPlanningTest.claim(
                  roots.rootIdentity(selection),
                  source.rootCKey,
                  source.rootEKey,
                  source.root.length,
                  source.encodedRoot);
          f.client.length = source.encodedRoot.length;
          var issued = issuer.prepareRoot(selection, claim, CancellationToken.none());
          var observations = new ArrayList<MetadataRepairObservation>();
          var failure =
              assertThrows(
                  MetadataAcquisitionFailure.class,
                  () ->
                      new OfficialMetadataRepairExecutionGateway(issuer, f.store, source, f.clock)
                          .repairRoot(
                              selection,
                              issued,
                              UUID.randomUUID(),
                              collecting(observations),
                              CancellationToken.none()));
          assertEquals(
              wrongCkey
                  ? MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED
                  : MetadataAcquisitionFailure.Publication.UNCERTAIN,
              failure.publication());
          assertEquals(wrongCkey ? 0 : 1, observations.size());
          assertEquals(
              !wrongCkey,
              f.store
                  .verify(issued.originalProof().object().digest(), CancellationToken.none())
                  .isPresent());
        }
      }
    }
  }

  @Test
  void lateCompletedPayloadCannotBypassConsentDeadline() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      f.client.length = f.install.encodedBytes();
      var issued =
          f.gateway.prepareInstall(
              f.install, f.installConfiguration, f.installClaim, CancellationToken.none());
      var nanos = new java.util.concurrent.atomic.AtomicLong();
      var normal = installTransport(installBytes());
      LookupTransport late =
          new LookupTransport() {
            public java.util.concurrent.CompletableFuture<
                    io.vibeminer.infrastructure.acquisition.AcquiredBytes>
                configuration(io.vibeminer.infrastructure.acquisition.CdnRequest request) {
              var response = normal.configuration(request);
              nanos.set(java.time.Duration.ofMinutes(11).toNanos());
              return response;
            }

            public java.util.concurrent.CompletableFuture<
                    io.vibeminer.infrastructure.acquisition.AcquiredRange>
                range(io.vibeminer.infrastructure.acquisition.CdnRangeRequest request) {
              throw new AssertionError();
            }
          };
      var observations = new ArrayList<MetadataRepairObservation>();
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () ->
                  new OfficialMetadataRepairExecutionGateway(
                          f.gateway, f.store, late, f.clock, nanos::get)
                      .repairInstall(
                          f.install,
                          issued,
                          UUID.randomUUID(),
                          collecting(observations),
                          CancellationToken.none()));
      assertInstanceOf(MetadataRepairException.class, failure.failure());
      assertEquals(
          MetadataRepairException.Code.LIMIT, ((MetadataRepairException) failure.failure()).code());
      assertTrue(observations.isEmpty());
    }
  }

  @Test
  void unavailableOrCorruptInstallNeverPromotesOrFallsBack() throws Exception {
    for (boolean unavailable : new boolean[] {true, false}) {
      try (var f =
          new MetadataRepairPlanningTest.Fixture(directory.resolve("install-" + unavailable))) {
        f.client.length = f.install.encodedBytes();
        var issued =
            f.gateway.prepareInstall(
                f.install, f.installConfiguration, f.installClaim, CancellationToken.none());
        byte[] corrupted = installBytes();
        corrupted[corrupted.length - 1] ^= 1;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var corrupt = installTransport(corrupted);
        LookupTransport transport =
            new LookupTransport() {
              public java.util.concurrent.CompletableFuture<
                      io.vibeminer.infrastructure.acquisition.AcquiredBytes>
                  configuration(io.vibeminer.infrastructure.acquisition.CdnRequest request) {
                calls.incrementAndGet();
                return unavailable
                    ? java.util.concurrent.CompletableFuture.failedFuture(
                        new io.vibeminer.infrastructure.acquisition.AcquisitionException(
                            io.vibeminer.infrastructure.acquisition.AcquisitionException.Reason
                                .UNAVAILABLE,
                            "404"))
                    : corrupt.configuration(request);
              }

              public java.util.concurrent.CompletableFuture<
                      io.vibeminer.infrastructure.acquisition.AcquiredRange>
                  range(io.vibeminer.infrastructure.acquisition.CdnRangeRequest request) {
                throw new AssertionError("no archive/mirror fallback");
              }
            };
        var observations = new ArrayList<MetadataRepairObservation>();
        var failure =
            assertThrows(
                MetadataAcquisitionFailure.class,
                () ->
                    new OfficialMetadataRepairExecutionGateway(
                            f.gateway, f.store, transport, f.clock)
                        .repairInstall(
                            f.install,
                            issued,
                            UUID.randomUUID(),
                            collecting(observations),
                            CancellationToken.none()));
        assertEquals(
            MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
        assertEquals(1, calls.get());
        assertTrue(observations.isEmpty());
        assertTrue(
            f.store
                .verify(issued.originalProof().object().digest(), CancellationToken.none())
                .isEmpty());
      }
    }
  }

  @Test
  void heldReadLeaseRefusesPromotionAfterDurableProspectiveGate() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      f.client.length = f.install.encodedBytes();
      var issued =
          f.gateway.prepareInstall(
              f.install, f.installConfiguration, f.installClaim, CancellationToken.none());
      var observations = new ArrayList<MetadataRepairObservation>();
      try (var lease =
          f.store.openRead(f.installConfiguration.object().digest(), CancellationToken.none())) {
        var transport = installTransport(installBytes());
        var failure =
            assertThrows(
                MetadataAcquisitionFailure.class,
                () ->
                    new OfficialMetadataRepairExecutionGateway(
                            f.gateway, f.store, transport, f.clock)
                        .repairInstall(
                            f.install,
                            issued,
                            UUID.randomUUID(),
                            collecting(observations),
                            CancellationToken.none()));
        assertEquals(MetadataAcquisitionFailure.Publication.UNCERTAIN, failure.publication());
        assertEquals(1, observations.size());
        assertInstanceOf(ObjectStoreException.class, failure.failure());
      }
      assertTrue(
          f.store
              .verify(issued.originalProof().object().digest(), CancellationToken.none())
              .isEmpty());
    }
  }

  @Test
  void repairLedgerRejectsWireRequestsDecodedWorkAndLateFuture() throws Exception {
    try (var f = new MetadataRepairPlanningTest.Fixture(directory)) {
      var issued = f.gateway.prepareRoot(f.selection, f.rootClaim, CancellationToken.none());
      var cost =
          new MetadataRepairCost(
              issued.cost().encodedTransferBytes(),
              issued.cost().maximumAdditionalPhysicalBytes(),
              1,
              issued.cost().maximumDecodedWorkBytes(),
              issued.cost().maximumPayloadDuration());
      var bounded =
          new MetadataRepairPlan(
              issued.issuanceId(),
              issued.reservationToken(),
              issued.request(),
              issued.originalProof(),
              issued.sourceObservation(),
              cost,
              issued.issuedAt(),
              issued.expiresAt());
      var nanos = new java.util.concurrent.atomic.AtomicLong();
      var work = new RepairWork(bounded, CancellationToken.none(), nanos::get);
      work.reserve(1);
      assertThrows(MetadataRepairException.class, () -> work.reserve(1));
      assertThrows(
          MetadataRepairException.class,
          () -> work.decodedWork(cost.maximumDecodedWorkBytes() + 1));
      var wire = new RepairWork(bounded, CancellationToken.none(), nanos::get);
      assertThrows(
          MetadataRepairException.class, () -> wire.reserve(cost.encodedTransferBytes() + 1));
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      var late =
          new RepairWork(
              bounded,
              CancellationToken.none(),
              () -> reads.incrementAndGet() <= 2 ? 0 : java.time.Duration.ofMinutes(11).toNanos());
      assertThrows(
          MetadataRepairException.class,
          () -> late.await(java.util.concurrent.CompletableFuture.completedFuture("late")));
    }
  }

  static MetadataRepairObserver collecting(List<MetadataRepairObservation> values) {
    return new MetadataRepairObserver() {
      public void beforePromotion(MetadataRepairObservation o, CancellationToken t) {
        values.add(o);
      }

      public void afterStructureValidation(MetadataRepairObservation o, CancellationToken t) {
        values.add(o);
      }
    };
  }

  static byte[] installBytes() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = new java.io.DataOutputStream(bytes);
    out.writeBytes("BLTE");
    out.writeInt(0);
    out.writeByte('N');
    out.write(new byte[] {'I', 'N', 1, 16, 0, 0, 0, 0, 0, 0});
    return bytes.toByteArray();
  }

  static LookupTransport installTransport(byte[] bytes) {
    return new LookupTransport() {
      public java.util.concurrent.CompletableFuture<
              io.vibeminer.infrastructure.acquisition.AcquiredBytes>
          configuration(io.vibeminer.infrastructure.acquisition.CdnRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(
            new io.vibeminer.infrastructure.acquisition.AcquiredBytes(
                request, MetadataRepairPlanningTest.Fixture.NOW, bytes, "0".repeat(64), false));
      }

      public java.util.concurrent.CompletableFuture<
              io.vibeminer.infrastructure.acquisition.AcquiredRange>
          range(io.vibeminer.infrastructure.acquisition.CdnRangeRequest request) {
        throw new AssertionError("install must use one whole object");
      }
    };
  }
}
