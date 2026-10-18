package io.vibeminer.application.itemnames;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ItemNamesServiceTest {
  @Test
  void rejectedExecutorAndMalformedLimitsNeverLeakAdmissionOrCallBackend() {
    var fake = new Fake();
    try (var service =
        new DefaultItemNamesService(
            fake,
            task -> {
              throw new RejectedExecutionException();
            })) {
      for (int n = 0; n < 8; n++)
        assertEquals(ItemNamesException.Code.BUSY, code(service.prepare(request)));
      assertEquals(0, fake.calls);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SparseNameLimits(
                0, 131072, 4096, 4 << 20, 4 << 20, 131072, 4 << 20, 200000, 1000, 64L << 20, 65535,
                16384, 65535, 32L << 20, 128L << 20, 100));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SparseNameLimits(
                1000000, 131072, 4096, 4 << 20, 4 << 20, 131072, 4 << 20, 200000, 1000, 64L << 20,
                65536, 16384, 65535, 32L << 20, 128L << 20, 100));
  }

  @Test
  void badEvidenceRevocationDoesNotRevokeAnUnrelatedSelectedRole() {
    var original = pair.names();
    var e = original.evidence();
    var a = e.association();
    var v = a.descriptor().association();
    var changed =
        new io.vibeminer.domain.selectedfile.FileVariantAssociation(
            v.build(),
            v.rootSource(),
            v.fileDataId(),
            v.contentKey(),
            v.localeFlags(),
            v.contentFlags() + 1,
            v.rawFlagsFirst(),
            v.rawFlagsSecond(),
            v.rawFlagsThird(),
            v.nameHash());
    var descriptor =
        new io.vibeminer.domain.selectedfile.SelectedFileDescriptor(
            changed, a.descriptor().actualEncodingKey(), a.descriptor().encodedVerifierSemantics());
    var association =
        new io.vibeminer.domain.selectedfile.SelectedFileAssociation(
            descriptor, a.configuration(), a.root(), a.proof(), a.representation());
    var evidence =
        new ItemProjectionEvidence(
            association, e.localVerification(), e.chunks(), e.windows(), e.fullDecoded());
    var other =
        new PreparedItemNames(
            pair.item(),
            new PreparedSparseNames(original.definition(), evidence, original.coverage()));
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      fake.prepared = other;
      service
          .prepare(new ItemNamesRequest(pair.item(), descriptor, "enUS", limits))
          .toCompletableFuture()
          .join();
      fake.failure =
          new ItemNamesException(ItemNamesException.Code.MISSING_LOCAL, "selected role gone");
      assertEquals(ItemNamesException.Code.MISSING_LOCAL, code(service.prepare(request)));
      fake.failure = null;
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
      assertEquals(other, service.lookup(other, 101).toCompletableFuture().join().pair());
    }
  }

  @Test
  void unreviewedItemSchemaFailsBeforeBackendIo() {
    var unsupported =
        new PreparedItemProjection(
            ItemNamesFixture.baseItemDefinition("item-v1", "enUS", "c".repeat(64)),
            ItemNamesFixture.evidence(false, Instant.EPOCH),
            pair.item().coverage());
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          code(
              service.prepare(
                  new ItemNamesRequest(unsupported, request.sparseSource(), "enUS", limits))));
      assertEquals(0, fake.calls);
    }
  }

  static PreparedItemNames pair() {
    return new PreparedItemNames(
        ItemNamesFixture.item(Instant.EPOCH), ItemNamesFixture.names(Instant.EPOCH));
  }

  final PreparedItemNames pair = pair();
  final SparseNameLimits limits =
      new SparseNameLimits(
          1000000,
          131072,
          4096,
          4 << 20,
          4 << 20,
          131072,
          4 << 20,
          200000,
          1000,
          64L << 20,
          65535,
          16384,
          65535,
          32L << 20,
          128L << 20,
          100);
  final ItemNamesRequest request =
      new ItemNamesRequest(
          pair.item(), pair.names().evidence().association().descriptor(), "enUS", limits);

  static class Fake implements ItemNamesBackend {
    int calls;
    RuntimeException failure;
    PreparedItemNames prepared = pair();

    public PreparedItemNames prepare(ItemNamesRequest r, CancellationToken t) {
      calls++;
      if (failure != null) throw failure;
      return prepared;
    }

    public ItemNamesPage page(
        PreparedItemNames p, Optional<ItemNamesCursor> c, int n, CancellationToken t) {
      calls++;
      if (failure != null) throw failure;
      return new ItemNamesPage(p, List.of(), Optional.empty());
    }

    public ItemNamesLookup lookup(PreparedItemNames p, long id, CancellationToken t) {
      calls++;
      if (failure != null) throw failure;
      return new ItemNamesLookup(
          p,
          new JoinedItemLookup(
              new ItemLookupResult(
                  id,
                  ItemLookupResult.Outcome.NO_PHYSICAL_MATCH,
                  Optional.empty(),
                  OptionalLong.empty(),
                  p.item().coverage()),
              Optional.empty()));
    }
  }

  static ItemNamesException.Code code(CompletionStage<?> f) {
    return assertInstanceOf(
            ItemNamesException.class,
            assertThrows(CompletionException.class, () -> f.toCompletableFuture().join())
                .getCause())
        .code();
  }

  @Test
  void malformedIdsPagesAndUnpreparedPairNeverReachPort() {
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      assertEquals(ItemNamesException.Code.INVALID_REQUEST, code(service.lookup(pair, 0)));
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST, code(service.page(pair, Optional.empty(), 501)));
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
      assertEquals(0, fake.calls);
    }
  }

  @Test
  void explicitPrepareEnablesOnlyPairedContextAndMissingRevokesIt() {
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      assertEquals(pair, service.prepare(request).toCompletableFuture().join());
      assertEquals(
          101,
          service.lookup(pair, 101).toCompletableFuture().join().result().item().requestedId());
      fake.failure = new ItemNamesException(ItemNamesException.Code.MISSING_LOCAL, "gone");
      assertEquals(
          ItemNamesException.Code.MISSING_LOCAL, code(service.page(pair, Optional.empty(), 10)));
      fake.failure = null;
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
      assertEquals(3, fake.calls);
    }
  }

  @Test
  void failedReprepareRevokesEarlierExactPair() {
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      fake.failure = new ItemNamesException(ItemNamesException.Code.CORRUPT, "late");
      assertEquals(ItemNamesException.Code.CORRUPT, code(service.prepare(request)));
      fake.failure = null;
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
      assertEquals(2, fake.calls);
    }
  }

  @Test
  void bothArtifactCursorIsCheckedBeforeQueryPort() {
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      var cursor =
          new ItemNamesCursor(
              pair.item().artifactId(),
              "0".repeat(64),
              pair.snapshotId(),
              pair.item().sourceRole(),
              pair.names().sourceRole(),
              pair.locale(),
              ItemNamesCursor.QUERY_REVISION,
              ItemNamesCursor.OPTIONS_REVISION,
              101,
              50);
      assertEquals(
          ItemNamesException.Code.STALE_CURSOR, code(service.page(pair, Optional.of(cursor), 50)));
      assertEquals(1, fake.calls);
    }
  }

  @Test
  void cancellationRetainsQueuedWorkerSlotsUntilTheyRetire() {
    var queued = new ArrayDeque<Runnable>();
    var fake = new Fake();
    var service = new DefaultItemNamesService(fake, queued::add);
    var pending = new ArrayList<CompletableFuture<PreparedItemNames>>();
    for (int n = 0; n < 4; n++) pending.add(service.prepare(request).toCompletableFuture());
    pending.getFirst().cancel(true);
    assertEquals(ItemNamesException.Code.BUSY, code(service.prepare(request)));
    queued.removeFirst().run();
    assertEquals(0, fake.calls);
    service.close();
    while (!queued.isEmpty()) queued.removeFirst().run();
    assertEquals(0, fake.calls);
    assertEquals(ItemNamesException.Code.CLOSED, code(service.prepare(request)));
  }

  @Test
  void canonicalObservationsDoNotChangeSelectedDescriptorIdentity() {
    var fake = new Fake();
    var later = Instant.ofEpochSecond(100);
    var observed =
        new ItemNamesRequest(
            ItemNamesFixture.item(later),
            ItemNamesFixture.names(later).evidence().association().descriptor(),
            "enUS",
            limits);
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      assertEquals(pair, service.prepare(observed).toCompletableFuture().join());
    }
  }

  @Test
  void authorizationIsBoundedAndReplacedProofDoesNotAuthorizeOldPair() {
    var fake = new Fake();
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      for (int n = 0; n < 16; n++) {
        var names =
            new PreparedSparseNames(
                ItemNamesFixture.definition("decoder-" + n, "enUS", ItemNamesFixture.schemaSha()),
                pair.names().evidence(),
                pair.names().coverage());
        fake.prepared = new PreparedItemNames(pair.item(), names);
        service.prepare(request).toCompletableFuture().join();
      }
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
      var old = fake.prepared;
      var e = old.names().evidence();
      var updated =
          new ItemProjectionEvidence(
              e.association(),
              new ItemProjectionEvidence.LocalVerification(
                  e.localVerification().object(),
                  Instant.ofEpochSecond(20),
                  e.localVerification().verifierRevision()),
              e.chunks(),
              e.windows(),
              e.fullDecoded());
      fake.prepared =
          new PreparedItemNames(
              pair.item(),
              new PreparedSparseNames(old.names().definition(), updated, old.names().coverage()));
      service.prepare(request).toCompletableFuture().join();
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(old, 101)));
    }
  }

  NamedItemRow row(long id, int physicalOrdinal, ItemNameMatch name) {
    var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
    for (var binding : pair.item().definition().interpretation().schema().fields())
      values.add(
          new ItemPhysicalRow.IntegerCandidate(
              binding.field(),
              1,
              8,
              ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
              1,
              binding.candidateBits(),
              binding.sign()));
    return new NamedItemRow(new ItemPhysicalRow(id, physicalOrdinal, 0, values), name);
  }

  @Test
  void emptyPhysicalNamesAndMissingNamesPreserveItemAnchor() {
    var empty =
        new ItemNameMatch(
            101,
            ItemNameMatch.Outcome.PHYSICAL_NAME,
            Optional.of(new DisplayCandidate("", 101, 0, 0, 5252, 5, 5)),
            OptionalLong.empty());
    var missing =
        new ItemNameMatch(
            202, ItemNameMatch.Outcome.NO_PHYSICAL_MATCH, Optional.empty(), OptionalLong.empty());
    var fake =
        new Fake() {
          @Override
          public ItemNamesPage page(
              PreparedItemNames p, Optional<ItemNamesCursor> c, int n, CancellationToken t) {
            return new ItemNamesPage(
                p, List.of(row(101, 0, empty), row(202, 1, missing)), Optional.empty());
          }
        };
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      var page = service.page(pair, Optional.empty(), 2).toCompletableFuture().join();
      assertEquals(
          List.of(101L, 202L), page.rows().stream().map(r -> r.item().recordId()).toList());
      assertEquals("", page.rows().getFirst().name().candidate().orElseThrow().value());
    }
  }

  @Test
  void backendCannotMoveNameOutsideIndependentSparseCoverage() {
    var outside =
        new ItemNameMatch(
            101,
            ItemNameMatch.Outcome.PHYSICAL_NAME,
            Optional.of(new DisplayCandidate("", 101, 0, 0, 5251, 5, 5)),
            OptionalLong.empty());
    var fake =
        new Fake() {
          @Override
          public ItemNamesPage page(
              PreparedItemNames p, Optional<ItemNamesCursor> c, int n, CancellationToken t) {
            return new ItemNamesPage(p, List.of(row(101, 0, outside)), Optional.empty());
          }
        };
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      service.prepare(request).toCompletableFuture().join();
      assertEquals(ItemNamesException.Code.CORRUPT, code(service.page(pair, Optional.empty(), 10)));
      assertEquals(ItemNamesException.Code.NOT_READY, code(service.lookup(pair, 101)));
    }
  }

  @Test
  void malformedLocaleAndBackendLookupIdentityFailAtTheirBoundaries() {
    var fake =
        new Fake() {
          @Override
          public ItemNamesLookup lookup(PreparedItemNames p, long id, CancellationToken t) {
            return super.lookup(p, id + 1, t);
          }
        };
    try (var service = new DefaultItemNamesService(fake, Runnable::run)) {
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          code(
              service.prepare(
                  new ItemNamesRequest(pair.item(), request.sparseSource(), "frFR", limits))));
      assertEquals(0, fake.calls);
      service.prepare(request).toCompletableFuture().join();
      assertEquals(ItemNamesException.Code.CORRUPT, code(service.lookup(pair, 101)));
    }
  }

  @Test
  void runningCancelledWorkersRetireBeforeCapacityIsReleased() throws Exception {
    var entered = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    var fake =
        new Fake() {
          @Override
          public PreparedItemNames prepare(ItemNamesRequest r, CancellationToken t) {
            entered.countDown();
            boolean done = false;
            while (!done)
              try {
                release.await();
                done = true;
              } catch (InterruptedException ignored) {
              }
            return prepared;
          }
        };
    try (var executor = Executors.newFixedThreadPool(4);
        var service = new DefaultItemNamesService(fake, executor)) {
      var jobs = new ArrayList<CompletableFuture<PreparedItemNames>>();
      for (int n = 0; n < 4; n++) jobs.add(service.prepare(request).toCompletableFuture());
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        jobs.forEach(f -> f.cancel(true));
        assertEquals(ItemNamesException.Code.BUSY, code(service.prepare(request)));
      } finally {
        release.countDown();
      }
      assertTrue(jobs.stream().allMatch(CompletableFuture::isCancelled));
    } finally {
      release.countDown();
    }
  }
}
