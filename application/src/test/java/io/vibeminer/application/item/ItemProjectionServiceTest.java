package io.vibeminer.application.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ItemProjectionServiceTest {
  @Test
  void canonicalPersistedObservationPreservesExactDescriptorIdentity() {
    var original = request.source();
    var variant = original.association();
    var b = variant.build();
    var later =
        new io.vibeminer.domain.BuildSnapshot(
            b.productId(),
            "Later display label",
            b.channel(),
            b.region(),
            b.versionLabel(),
            b.buildConfig(),
            b.cdnConfig(),
            java.time.Instant.ofEpochSecond(100),
            b.fixture(),
            b.evidence());
    var observed =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            later,
            variant.rootSource(),
            variant.fileDataId(),
            variant.contentKey(),
            variant.localeFlags(),
            variant.contentFlags(),
            variant.rawFlagsFirst(),
            variant.rawFlagsSecond(),
            variant.rawFlagsThird(),
            variant.nameHash());
    var descriptor =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            observed, original.actualEncodingKey(), original.encodedVerifierSemantics());
    assertEquals(original.role(), descriptor.role());
    assertEquals(b.snapshotId(), later.snapshotId());
    try (var service = new DefaultItemProjectionService(new Fake(), Runnable::run)) {
      assertEquals(
          handle,
          service
              .prepare(new ItemProjectionRequest(descriptor, "enUS", request.limits()))
              .toCompletableFuture()
              .join());
    }
  }

  @Test
  void differentEncodingDescriptorCannotReuseCanonicalContext() {
    var source = request.source();
    var changed =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            source.association(), "0".repeat(32), source.encodedVerifierSemantics());
    try (var service = new DefaultItemProjectionService(new Fake(), Runnable::run)) {
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          code(service.prepare(new ItemProjectionRequest(changed, "enUS", request.limits()))));
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
    }
  }

  @Test
  void malformedLocalBoundsCannotBecomeRequests() {
    assertThrows(
        IllegalArgumentException.class, () -> new ItemProjectionLimits(1, 4194305, 1, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new ItemProjectionLimits(1, 1, 1, -1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new ItemProjectionLimits(0, 1, 1, 0, 1, 0));
  }

  @Test
  void rejectedExecutorNeverCallsBackendAndDoesNotLeakSlots() {
    var fake = new Fake();
    try (var service =
        new DefaultItemProjectionService(
            fake,
            task -> {
              throw new RejectedExecutionException();
            })) {
      for (int n = 0; n < 8; n++)
        assertEquals(ItemProjectionException.Code.BUSY, code(service.prepare(request)));
      assertEquals(0, fake.calls);
    }
  }

  @Test
  void lookupCannotReturnAnotherRequestedId() {
    var fake =
        new Fake() {
          @Override
          public ItemLookupResult lookup(PreparedItemProjection h, long id, CancellationToken t) {
            return new ItemLookupResult(
                id + 1,
                ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
                Optional.empty(),
                OptionalLong.empty(),
                h.coverage());
          }
        };
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      assertEquals(ItemProjectionException.Code.CORRUPT, code(service.lookup(handle, 1)));
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
    }
  }

  final PreparedItemProjection handle = ItemFixture.handle();
  final ItemProjectionRequest request =
      new ItemProjectionRequest(
          handle.evidence().association().descriptor(),
          "enUS",
          new ItemProjectionLimits(1000000, 3000000, 10, 200000, 100000, 10000));

  static class Fake implements ItemProjectionBackend {
    int calls;
    PreparedItemProjection result = ItemFixture.handle();
    RuntimeException failure;

    public PreparedItemProjection prepare(ItemProjectionRequest r, CancellationToken t) {
      calls++;
      if (failure != null) throw failure;
      return result;
    }

    public ItemPage page(
        PreparedItemProjection h, Optional<ItemPageCursor> c, int n, CancellationToken t) {
      calls++;
      if (failure != null) throw failure;
      return new ItemPage(h, List.of(), Optional.empty());
    }

    public ItemLookupResult lookup(PreparedItemProjection h, long id, CancellationToken t) {
      calls++;
      if (failure != null) throw failure;
      return new ItemLookupResult(
          id,
          ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
          Optional.empty(),
          OptionalLong.empty(),
          h.coverage());
    }
  }

  static ItemProjectionException.Code code(CompletionStage<?> result) {
    return assertInstanceOf(
            ItemProjectionException.class,
            assertThrows(CompletionException.class, () -> result.toCompletableFuture().join())
                .getCause())
        .code();
  }

  @Test
  void invalidInputsAndUnpreparedHandlesNeverReachBackend() {
    var fake = new Fake();
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      assertEquals(ItemProjectionException.Code.INVALID_REQUEST, code(service.lookup(handle, 0)));
      assertEquals(
          ItemProjectionException.Code.INVALID_REQUEST,
          code(service.page(handle, Optional.empty(), 501)));
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
      assertEquals(0, fake.calls);
    }
  }

  @Test
  void explicitPrepareThenQueriesAndMissingRevokesReadiness() {
    var fake = new Fake();
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      assertEquals(handle, service.prepare(request).toCompletableFuture().join());
      assertEquals(7, service.lookup(handle, 7).toCompletableFuture().join().requestedId());
      fake.failure =
          new ItemProjectionException(ItemProjectionException.Code.MISSING_LOCAL, "gone");
      assertEquals(
          ItemProjectionException.Code.MISSING_LOCAL,
          code(service.page(handle, Optional.empty(), 10)));
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 7)));
      assertEquals(3, fake.calls);
    }
  }

  @Test
  void staleCursorRejectedBeforePort() {
    var fake = new Fake();
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      var cursor =
          new ItemPageCursor(
              "0".repeat(64),
              handle.snapshotId(),
              handle.sourceRole(),
              "enUS",
              ItemPageCursor.QUERY_REVISION,
              1,
              10);
      assertEquals(
          ItemProjectionException.Code.STALE_CURSOR,
          code(service.page(handle, Optional.of(cursor), 10)));
      assertEquals(1, fake.calls);
    }
  }

  @Test
  void failedPrepareNeverMakesReady() {
    var fake = new Fake();
    fake.failure = new ItemProjectionException(ItemProjectionException.Code.CORRUPT, "late");
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      assertEquals(ItemProjectionException.Code.CORRUPT, code(service.prepare(request)));
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
    }
  }

  @Test
  void missingEvidenceOnReprepareRevokesEarlierAuthorization() {
    var fake = new Fake();
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      fake.failure =
          new ItemProjectionException(ItemProjectionException.Code.MISSING_LOCAL, "gone");
      assertEquals(ItemProjectionException.Code.MISSING_LOCAL, code(service.prepare(request)));
      fake.failure = null;
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
      assertEquals(2, fake.calls);
    }
  }

  @Test
  void substitutedPreparationContextNeverAuthorizesQueries() {
    var fake = new Fake();
    fake.result =
        new PreparedItemProjection(
            ItemFixture.definition("test-decoder", "frFR", "c".repeat(64)),
            handle.evidence(),
            handle.coverage());
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      assertEquals(ItemProjectionException.Code.CORRUPT, code(service.prepare(request)));
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
    }
  }

  @Test
  void boundedAuthorizationEvictsOldContextAndReplacementInvalidatesOldProof() {
    var fake = new Fake();
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      for (int n = 0; n < 16; n++) {
        fake.result =
            new PreparedItemProjection(
                ItemFixture.definition("decoder-" + n, "enUS", "c".repeat(64)),
                handle.evidence(),
                handle.coverage());
        service.prepare(request).toCompletableFuture().join();
      }
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
      var latest = fake.result;
      var e = latest.evidence();
      fake.result =
          new PreparedItemProjection(
              latest.definition(),
              new ItemProjectionEvidence(
                  e.association(),
                  new ItemProjectionEvidence.LocalVerification(
                      e.localVerification().object(),
                      java.time.Instant.ofEpochSecond(10),
                      "sha256-v1"),
                  e.chunks(),
                  e.windows(),
                  e.fullDecoded()),
              latest.coverage());
      service.prepare(request).toCompletableFuture().join();
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(latest, 1)));
    }
  }

  @Test
  void invalidBackendRowOrderingRevokesHandle() {
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var field : ItemArtifactDefinition.Field.values())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              field,
              1,
              8,
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              1,
              32,
              ItemArtifactDefinition.Sign.UNSIGNED));
    var fake =
        new Fake() {
          @Override
          public ItemPage page(
              PreparedItemProjection h, Optional<ItemPageCursor> c, int n, CancellationToken t) {
            return new ItemPage(
                h,
                List.of(new ItemPhysicalRow(2, 0, 0, values), new ItemPhysicalRow(1, 1, 0, values)),
                Optional.empty());
          }
        };
    try (var service = new DefaultItemProjectionService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      assertEquals(
          ItemProjectionException.Code.CORRUPT, code(service.page(handle, Optional.empty(), 2)));
      assertEquals(ItemProjectionException.Code.NOT_READY, code(service.lookup(handle, 1)));
    }
  }

  @Test
  void cancellationHoldsRunningCapacityUntilBackendActuallyRetires() throws Exception {
    var entered = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    var fake =
        new Fake() {
          @Override
          public PreparedItemProjection prepare(ItemProjectionRequest r, CancellationToken t) {
            entered.countDown();
            boolean done = false;
            while (!done)
              try {
                release.await();
                done = true;
              } catch (InterruptedException ignored) {
              }
            return result;
          }
        };
    try (var executor = Executors.newFixedThreadPool(4);
        var service = new DefaultItemProjectionService(fake, executor)) {
      var pending = new ArrayList<CompletableFuture<PreparedItemProjection>>();
      for (int n = 0; n < 4; n++) pending.add(service.prepare(request).toCompletableFuture());
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        pending.forEach(f -> f.cancel(true));
        assertEquals(ItemProjectionException.Code.BUSY, code(service.prepare(request)));
        assertTrue(pending.stream().allMatch(CompletableFuture::isCancelled));
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void fourQueuedJobsKeepCapacityEvenWhenCancelledUntilRetirement() {
    var queue = new ArrayDeque<Runnable>();
    var fake = new Fake();
    var service = new DefaultItemProjectionService(fake, queue::add);
    var futures = new ArrayList<CompletableFuture<PreparedItemProjection>>();
    for (int i = 0; i < 4; i++) futures.add(service.prepare(request).toCompletableFuture());
    futures.getFirst().cancel(true);
    assertEquals(ItemProjectionException.Code.BUSY, code(service.prepare(request)));
    queue.removeFirst().run();
    assertEquals(0, fake.calls);
    service.prepare(request);
    service.close();
    while (!queue.isEmpty()) queue.removeFirst().run();
    assertEquals(0, fake.calls);
    assertEquals(ItemProjectionException.Code.CLOSED, code(service.prepare(request)));
  }
}
