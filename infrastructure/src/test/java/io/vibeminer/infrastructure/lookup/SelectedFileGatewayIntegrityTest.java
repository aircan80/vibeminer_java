package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.infrastructure.lookup.OfficialSelectedFileGatewayTest.choice;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelectedFileGatewayIntegrityTest {
  @TempDir Path directory;

  @Test
  void unknownEnSizesRemainUnknownUntilExplicitLooseMetadataProvidesKnownSize() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, true)) {
      var plan = s.candidates();
      assertTrue(
          plan.candidates().stream()
              .allMatch(c -> c.encodedBytes().isEmpty() && c.specificationIndex().isEmpty()));
      var located =
          s.gateway
              .probeLoose(choice(plan, s.fixture.selectedEKey), CancellationToken.none())
              .located()
              .orElseThrow();
      assertEquals(s.fixture.selected.length, located.cost().encodedTransferBytes());
      var retained = s.gateway.acquireEncoded(located, ignored -> {}, CancellationToken.none());
      var association =
          new SelectedFileAssociation(
              new SelectedFileDescriptor(
                  s.variant, s.fixture.selectedEKey, ContentRepresentation.VERIFIER_SEMANTICS),
              s.config,
              s.root,
              plan.proof(),
              retained);
      s.fixture.rejectNetwork = true;
      assertEquals(
          retained,
          s.gateway.inspectRetained(
              association, SelectedFileBudgets.defaults(), CancellationToken.none()));
      assertTrue(
          association.proof().authenticatedCandidates().stream()
              .allMatch(c -> c.encodedBytes().isEmpty()));
    }
  }

  @Test
  void changedCandidateLocatorAndForged404PermitFailBeforeAnyHttpOrPublication() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var plan = s.candidates();
      var selected = choice(plan, s.fixture.selectedEKey);
      var original =
          s.gateway.probeLoose(selected, CancellationToken.none()).located().orElseThrow();
      int before = s.fixture.requests.size();
      var forgedChoice =
          new CandidateChoice(
              plan,
              new EncodedCandidate("f".repeat(32), OptionalLong.of(14), OptionalLong.empty()));
      assertThrows(
          SelectedFileException.class,
          () -> s.gateway.probeLoose(forgedChoice, CancellationToken.none()));
      assertThrows(
          SelectedFileException.class,
          () ->
              s.gateway.validateLoose404Permit(
                  new Loose404Permit("caller-made", selected), CancellationToken.none()));
      var changed =
          new LocatedFilePlan(
              original.sessionId(),
              selected,
              original.locator(),
              original.encodedBytes(),
              new AcquisitionCost(0, 0));
      var failed =
          assertThrows(
              EncodedAcquisitionFailure.class,
              () ->
                  s.gateway.acquireEncoded(
                      changed,
                      p -> {
                        throw new AssertionError();
                      },
                      CancellationToken.none()));
      assertEquals(
          EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failed.publication());
      assertEquals(SelectedFileException.Code.INVALID_PLAN, failed.failure().code());
      assertEquals(before, s.fixture.requests.size());
    }
  }

  @Test
  void expiredAndEvictedSessionsCannotBeReused() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var oldest = s.candidates();
      for (int i = 0; i < 16; i++) s.candidates();
      assertThrows(
          SelectedFileException.class,
          () -> s.gateway.validateCandidatePlan(oldest, CancellationToken.none()));
    }
  }

  @Test
  void corruptOrTrailingPayloadNeverReachesCompletionObserverAndLeavesNoStage() throws Exception {
    for (boolean trailing : new boolean[] {false, true})
      try (var s =
          new OfficialSelectedFileGatewayTest.Setup(directory.resolve("c" + trailing), false)) {
        var plan = s.candidates();
        var located =
            s.gateway
                .probeLoose(choice(plan, s.fixture.selectedEKey), CancellationToken.none())
                .located()
                .orElseThrow();
        byte[] bad =
            trailing
                ? Arrays.copyOf(s.fixture.selected, s.fixture.selected.length + 1)
                : s.fixture.selected.clone();
        bad[bad.length - 1] ^= 1;
        s.fixture.bodies.put(s.fixture.route("GET", s.fixture.selectedEKey), bad);
        var calls = new AtomicInteger();
        var error =
            assertThrows(
                EncodedAcquisitionFailure.class,
                () ->
                    s.gateway.acquireEncoded(
                        located, p -> calls.incrementAndGet(), CancellationToken.none()));
        assertEquals(
            EncodedAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, error.publication());
        assertEquals(0, calls.get());
        try (var files = Files.walk(directory)) {
          assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("stage-")));
        }
      }
  }

  @Test
  void ignoredPayloadRangeAndCorruptArchiveMetadataNeverCreateReadyEncodedReceipt()
      throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var plan = s.candidates();
      var selected = choice(plan, s.fixture.selectedEKey);
      var located =
          s.gateway.probeLoose(selected, CancellationToken.none()).located().orElseThrow();
      s.fixture.statuses.put(s.fixture.route("GET", s.fixture.selectedEKey), 200);
      var failed =
          assertThrows(
              EncodedAcquisitionFailure.class,
              () ->
                  s.gateway.acquireEncoded(
                      located,
                      p -> {
                        throw new AssertionError();
                      },
                      CancellationToken.none()));
      assertEquals(SelectedFileException.Code.INTEGRITY_FAILURE, failed.failure().code());
      s.fixture.statuses.put(s.fixture.route("HEAD", s.fixture.selectedEKey), 404);
      var permit = s.gateway.probeLoose(selected, CancellationToken.none()).missing().orElseThrow();
      s.fixture.bodies.put(s.fixture.route("GET", s.fixture.archiveKey + ".index"), new byte[28]);
      assertThrows(
          SelectedFileException.class,
          () ->
              s.gateway.searchArchives(
                  permit, ArchiveSearchBudgets.defaults(), CancellationToken.none()));
    }
  }

  @Test
  void boundedArchiveSearchReportsIncompleteRatherThanAbsenceAndParentGeometryCapIsIndependent()
      throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var plan = s.candidates();
      s.fixture.statuses.put(s.fixture.route("HEAD", s.fixture.selectedEKey), 404);
      var permit =
          s.gateway
              .probeLoose(choice(plan, s.fixture.selectedEKey), CancellationToken.none())
              .missing()
              .orElseThrow();
      var partial =
          s.gateway.searchArchives(
              permit,
              new ArchiveSearchBudgets(1, 1, 1, Duration.ofSeconds(1), 8L << 30),
              CancellationToken.none());
      assertTrue(partial.located().isEmpty());
      assertFalse(partial.complete());
      var tooSmall =
          s.gateway.searchArchives(
              permit,
              new ArchiveSearchBudgets(1, 2L << 20, 10, Duration.ofSeconds(1), 10),
              CancellationToken.none());
      assertFalse(tooSmall.complete());
      assertTrue(tooSmall.located().isEmpty());
    }
  }

  @Test
  void cancellationDuringPayloadWaitCancelsWireAndObserverFailureRemainsUncertain()
      throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var plan = s.candidates();
      var located =
          s.gateway
              .probeLoose(choice(plan, s.fixture.selectedEKey), CancellationToken.none())
              .located()
              .orElseThrow();
      var cancelled = new AtomicBoolean();
      var entered = new CountDownLatch(1);
      int cancelledBefore = s.fixture.cancellations.get();
      s.fixture.requestObserver =
          request -> {
            if (request.method().equals("GET")
                && request.uri().getPath().endsWith(s.fixture.selectedEKey)) entered.countDown();
          };
      s.fixture.stalled.add(s.fixture.route("GET", s.fixture.selectedEKey));
      try (var worker = Executors.newSingleThreadExecutor()) {
        var future =
            worker.submit(
                () ->
                    assertThrows(
                        EncodedAcquisitionFailure.class,
                        () ->
                            s.gateway.acquireEncoded(
                                located,
                                p -> {
                                  throw new AssertionError();
                                },
                                cancelled::get)));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        cancelled.set(true);
        var failure = future.get(2, TimeUnit.SECONDS);
        assertEquals(SelectedFileException.Code.CANCELLED, failure.failure().code());
        assertTrue(s.fixture.cancellations.get() > cancelledBefore);
      }
      s.fixture.stalled.clear();
      var uncertain =
          assertThrows(
              EncodedAcquisitionFailure.class,
              () ->
                  s.gateway.acquireEncoded(
                      located,
                      p -> {
                        throw new IllegalStateException("synthetic receipt write failure");
                      },
                      CancellationToken.none()));
      assertEquals(EncodedAcquisitionFailure.Publication.UNCERTAIN, uncertain.publication());
    }
  }

  @Test
  void malformedHttpTotalsAndAuthenticatedEnTocPageCorruptionFailClosed() throws Exception {
    for (String corruption : List.of("total", "en", "toc", "page"))
      try (var s =
          new OfficialSelectedFileGatewayTest.Setup(directory.resolve(corruption), false)) {
        if (corruption.equals("en")) {
          var object = s.fixture.objects.get(s.fixture.encodingEKey).clone();
          object[object.length - 1] ^= 1;
          s.fixture.objects.put(s.fixture.encodingEKey, object);
          assertThrows(SelectedFileException.class, s::candidates);
          continue;
        }
        var plan = s.candidates();
        var selected = choice(plan, s.fixture.selectedEKey);
        if (corruption.equals("total")) {
          var located =
              s.gateway.probeLoose(selected, CancellationToken.none()).located().orElseThrow();
          s.fixture.headerOverrides.put(
              s.fixture.route("GET", s.fixture.selectedEKey),
              Map.of(
                  "Content-Range",
                  List.of(
                      "bytes 0-"
                          + (s.fixture.selected.length - 1)
                          + "/"
                          + (s.fixture.selected.length + 1))));
          var failure =
              assertThrows(
                  EncodedAcquisitionFailure.class,
                  () ->
                      s.gateway.acquireEncoded(
                          located,
                          p -> {
                            throw new AssertionError();
                          },
                          CancellationToken.none()));
          assertEquals(SelectedFileException.Code.INTEGRITY_FAILURE, failure.failure().code());
        } else {
          s.fixture.statuses.put(s.fixture.route("HEAD", s.fixture.selectedEKey), 404);
          var permit =
              s.gateway.probeLoose(selected, CancellationToken.none()).missing().orElseThrow();
          var index = s.fixture.archiveIndex.clone();
          index[corruption.equals("toc") ? 4096 : 0] ^= 1;
          s.fixture.objects.put(s.fixture.archiveKey + ".index", index);
          assertThrows(
              SelectedFileException.class,
              () ->
                  s.gateway.searchArchives(
                      permit, ArchiveSearchBudgets.defaults(), CancellationToken.none()));
        }
      }
  }

  @Test
  void payloadBytesRequestsAndMaximumObjectSizeRemainIndependentOfMetadataConsent()
      throws Exception {
    for (int limit = 0; limit < 3; limit++)
      try (var s =
          new OfficialSelectedFileGatewayTest.Setup(directory.resolve("budget" + limit), false)) {
        var defaults = SelectedFileBudgets.defaults();
        var budget =
            new SelectedFileBudgets(
                defaults.lookup(),
                limit == 2 ? 13 : defaults.maximumEncodedFileBytes(),
                limit == 1 ? 4 : defaults.maximumPayloadRangeBytes(),
                limit == 1 ? 1 : defaults.maximumPayloadRequests(),
                limit == 0 ? 13 : defaults.maximumPayloadWireBytes(),
                defaults.maximumPayloadDuration());
        var plan =
            s.gateway.prepareCandidates(
                s.build, s.config, s.root, s.variant, budget, CancellationToken.none());
        var error =
            assertThrows(
                SelectedFileException.class,
                () ->
                    s.gateway.probeLoose(
                        choice(plan, s.fixture.selectedEKey), CancellationToken.none()));
        assertEquals(SelectedFileException.Code.BUDGET_EXCEEDED, error.code());
        assertTrue(
            s.fixture.requests.stream()
                .noneMatch(
                    r ->
                        r.method().equals("GET")
                            && r.uri().getPath().endsWith(s.fixture.selectedEKey)));
      }
  }

  @Test
  void oneMetadataDeadlineCoversRetainedRootReplayAndSubsequentEnRequests() throws Exception {
    for (boolean expireDuringRoot : List.of(false, true))
      try (var s =
          new OfficialSelectedFileGatewayTest.Setup(
              directory.resolve("deadline" + expireDuringRoot), false)) {
        var nanos = new AtomicLong();
        var advanced = new AtomicBoolean();
        ObjectStore bounded =
            new ObjectStore() {
              public StoredObject publish(
                  java.io.InputStream source,
                  ExpectedObjectChecksum expected,
                  long maximum,
                  CancellationToken token) {
                return s.store.publish(source, expected, maximum, token);
              }

              public StoredObject publishVerified(
                  java.io.InputStream source,
                  long length,
                  long maximum,
                  CompletionVerification verification,
                  CancellationToken token) {
                return s.store.publishVerified(source, length, maximum, verification, token);
              }

              public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) {
                return s.store.verify(digest, token);
              }

              public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
                if (advanced.compareAndSet(false, true))
                  nanos.set(Duration.ofMinutes(expireDuringRoot ? 11 : 9).toNanos());
                return s.store.openRead(digest, token);
              }

              public void close() {}
            };
        s.fixture.requestObserver =
            request -> {
              if (request.uri().getPath().endsWith(s.fixture.encodingEKey))
                nanos.addAndGet(Duration.ofMinutes(2).toNanos());
            };
        var client = s.fixture.client();
        var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
        try (var gateway =
            new OfficialSelectedFileGateway(
                bounded,
                new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
                new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
                new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
                clock,
                nanos::get)) {
          var error =
              assertThrows(
                  SelectedFileException.class,
                  () ->
                      gateway.prepareCandidates(
                          s.build,
                          s.config,
                          s.root,
                          s.variant,
                          SelectedFileBudgets.defaults(),
                          CancellationToken.none()));
          assertEquals(SelectedFileException.Code.BUDGET_EXCEEDED, error.code());
        }
      }
  }

  @Test
  void finalHeadCompletionCannotAuthorizePlanAfterTheSharedStageDeadline() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var head = new AtomicBoolean();
      var checksAfterHead = new AtomicInteger();
      s.fixture.requestObserver =
          request -> {
            if (request.method().equals("HEAD")
                && request.uri().getPath().endsWith(s.fixture.selectedEKey)) head.set(true);
          };
      java.util.function.LongSupplier nanos =
          () ->
              head.get() && checksAfterHead.incrementAndGet() >= 2
                  ? Duration.ofMinutes(11).toNanos()
                  : 0;
      var client = s.fixture.client();
      var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
      try (var gateway =
          new OfficialSelectedFileGateway(
              s.store,
              new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
              new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
              new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
              clock,
              nanos)) {
        var plan =
            gateway.prepareCandidates(
                s.build,
                s.config,
                s.root,
                s.variant,
                SelectedFileBudgets.defaults(),
                CancellationToken.none());
        var failed =
            assertThrows(
                SelectedFileException.class,
                () ->
                    gateway.probeLoose(
                        choice(plan, s.fixture.selectedEKey), CancellationToken.none()));
        assertEquals(SelectedFileException.Code.BUDGET_EXCEEDED, failed.code());
      }
    }
  }

  @Test
  void transientEnHttpFailuresRetainUnavailabilityRatherThanClaimingCorruptData() throws Exception {
    for (int status : new int[] {404, 503})
      try (var s =
          new OfficialSelectedFileGatewayTest.Setup(directory.resolve("http" + status), false)) {
        s.fixture.statuses.put(s.fixture.route("GET", s.fixture.encodingEKey), status);
        var failure = assertThrows(SelectedFileException.class, s::candidates);
        assertEquals(SelectedFileException.Code.UNAVAILABLE, failure.code());
      }
  }

  @Test
  void retainedRootStorageIoFailureRemainsStorageUnavailable() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      ObjectStore failing =
          new ObjectStore() {
            public StoredObject publish(
                java.io.InputStream source,
                ExpectedObjectChecksum expected,
                long maximum,
                CancellationToken token) {
              throw new AssertionError();
            }

            public StoredObject publishVerified(
                java.io.InputStream source,
                long length,
                long maximum,
                CompletionVerification verification,
                CancellationToken token) {
              throw new AssertionError();
            }

            public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) {
              throw new AssertionError();
            }

            public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
              throw new ObjectStoreException(
                  ObjectStoreException.Code.IO_FAILURE,
                  "synthetic root read failure",
                  new java.io.IOException("synthetic"));
            }

            public void close() {}
          };
      var client = s.fixture.client();
      var clock = Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC);
      try (var gateway =
          new OfficialSelectedFileGateway(
              failing,
              new PinnedCdnTransport(client, clock, Duration.ofSeconds(1)),
              new PinnedCdnRangeTransport(client, clock, Duration.ofSeconds(1)),
              new CdnMetadataTransport(client, clock, Duration.ofSeconds(1)),
              clock)) {
        var failure =
            assertThrows(
                SelectedFileException.class,
                () ->
                    gateway.prepareCandidates(
                        s.build,
                        s.config,
                        s.root,
                        s.variant,
                        SelectedFileBudgets.defaults(),
                        CancellationToken.none()));
        assertEquals(SelectedFileException.Code.STORAGE_UNAVAILABLE, failure.code());
      }
    }
  }

  @Test
  void unsafeConfigurationReadBoundIsRejectedBeforeRootReplayOrNetwork() throws Exception {
    try (var s = new OfficialSelectedFileGatewayTest.Setup(directory, false)) {
      var b = LookupBudgets.defaults();
      var oversized =
          new LookupBudgets(
              Long.MAX_VALUE,
              Long.MAX_VALUE,
              b.maximumMetadataRequests(),
              b.maximumMetadataDecodedBytes(),
              b.maximumIndexBytes(),
              b.maximumPageBytes(),
              b.maximumCandidates(),
              b.maximumRootEncodedBytes(),
              b.maximumRootDecodedBytes(),
              b.maximumHeaderBytes(),
              b.maximumChunks(),
              b.maximumEncodedChunkBytes(),
              b.maximumDecodedChunkBytes(),
              b.maximumDecodedWorkBytes(),
              b.maximumSelectedIds(),
              b.maximumMatches(),
              b.maximumRootRecords());
      var normal = SelectedFileBudgets.defaults();
      var policy =
          new SelectedFileBudgets(
              oversized,
              normal.maximumEncodedFileBytes(),
              normal.maximumPayloadRangeBytes(),
              normal.maximumPayloadRequests(),
              normal.maximumPayloadWireBytes(),
              normal.maximumPayloadDuration());
      int before = s.fixture.requests.size();
      var failed =
          assertThrows(
              SelectedFileException.class,
              () ->
                  s.gateway.prepareCandidates(
                      s.build, s.config, s.root, s.variant, policy, CancellationToken.none()));
      assertEquals(SelectedFileException.Code.BUDGET_EXCEEDED, failed.code());
      assertEquals(before, s.fixture.requests.size());
    }
  }
}
