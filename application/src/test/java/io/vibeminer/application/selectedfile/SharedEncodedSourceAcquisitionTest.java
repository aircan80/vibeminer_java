package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.DefaultSelectedFileServiceTest.*;
import static io.vibeminer.application.selectedfile.SelectedFileFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import io.vibeminer.domain.selectedfile.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class SharedEncodedSourceAcquisitionTest {
  @Test
  void closingSourceBoundsLifetimeRetirementBindingsDespiteCanceledFollowerChurn()
      throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("closing-bound", C);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    try (var selected = f.service(Runnable::run, Runnable::run);
        var sources = Executors.newSingleThreadExecutor();
        var core = new EncodedSourceAcquisition(f.files, sources, null);
        var keeper = core.enter(variant.slot(), A)) {
      var candidates = await(selected.planCandidates(variant.build(), variant, BUDGETS));
      var located = located(selected, candidates, 0).acquisition().orElseThrow();
      var issued = f.gateway.authorizeEncodedSource(located, CancellationToken.none());
      f.gateway.afterProspectiveGate =
          () -> {
            entered.countDown();
            hold(release);
          };
      var first =
          CompletableFuture.supplyAsync(
              () ->
                  core.acquire(
                      keeper,
                      issued.plan(),
                      issued.gateway(),
                      PrioritySourceScheduler.Demand.foreground(),
                      CancellationToken.none()));
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        // Cancellation begins after reservation, forcing the late-follower enrollment boundary.
        f.files.afterReserve = () -> cancelled.set(true);
        for (int follower = 0; follower < 32; follower++) {
          cancelled.set(false);
          try (var lease = core.enter(variant.slot(), A)) {
            if (follower < 31)
              assertThrows(
                  CancellationException.class,
                  () ->
                      core.acquire(
                          lease,
                          issued.plan(),
                          issued.gateway(),
                          PrioritySourceScheduler.Demand.foreground(),
                          cancelled::get));
            else
              assertEquals(
                  EncodedSourceException.Code.CAPACITY_EXCEEDED,
                  assertThrows(
                          EncodedSourceException.class,
                          () ->
                              core.acquire(
                                  lease,
                                  issued.plan(),
                                  issued.gateway(),
                                  PrioritySourceScheduler.Demand.foreground(),
                                  cancelled::get))
                      .code());
          }
        }
      } finally {
        f.files.afterReserve = () -> {};
        release.countDown();
        first.get(3, TimeUnit.SECONDS);
      }
      assertEquals(1, f.gateway.acquisitions);
    }
  }

  @Test
  void duplicateLeaseAcquireCannotCreateAnUnownedSourceConsumer() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("single-use", C);
    var submitted = new CountDownLatch(1);
    var queued = new AtomicReference<Runnable>();
    var cancellation = new AtomicBoolean();
    try (var core =
            new EncodedSourceAcquisition(
                f.files,
                task -> {
                  assertTrue(queued.compareAndSet(null, task));
                  submitted.countDown();
                },
                null);
        var selected = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(selected.planCandidates(variant.build(), variant, BUDGETS));
      var located = located(selected, candidates, 0).acquisition().orElseThrow();
      var issued = f.gateway.authorizeEncodedSource(located, CancellationToken.none());
      var consumer = core.enter(variant.slot(), A);
      var operation =
          CompletableFuture.runAsync(
              () -> {
                try (consumer) {
                  core.acquire(
                      consumer,
                      issued.plan(),
                      issued.gateway(),
                      PrioritySourceScheduler.Demand.foreground(),
                      cancellation::get);
                }
              });
      assertTrue(submitted.await(2, TimeUnit.SECONDS));
      var duplicate =
          assertThrows(
              EncodedSourceException.class,
              () ->
                  core.acquire(
                      consumer,
                      issued.plan(),
                      issued.gateway(),
                      PrioritySourceScheduler.Demand.foreground(),
                      CancellationToken.none()));
      assertEquals(EncodedSourceException.Code.INVALID_REQUEST, duplicate.code());
      cancellation.set(true);
      assertThrows(ExecutionException.class, () -> operation.get(3, TimeUnit.SECONDS));
      assertFalse(consumer.retirement().toCompletableFuture().isDone());
      queued.get().run();
      consumer.retirement().toCompletableFuture().get(2, TimeUnit.SECONDS);
      assertEquals(0, f.gateway.acquisitions);
      assertTrue(f.files.workers.isEmpty());
    }
  }

  @Test
  void cancelledConsumerRetirementCannotRetireItsHeldActualSource() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("retirement", C);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    f.gateway.enteringAcquisition =
        () -> {
          entered.countDown();
          hold(release);
        };
    try (var sources = Executors.newSingleThreadExecutor();
        var scheduler = new PrioritySourceScheduler(sources);
        var core = new EncodedSourceAcquisition(f.files, sources, scheduler);
        var selected = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(selected.planCandidates(variant.build(), variant, BUDGETS));
      var located = located(selected, candidates, 0).acquisition().orElseThrow();
      var issued = f.gateway.authorizeEncodedSource(located, CancellationToken.none());
      var consumer = core.enter(variant.slot(), A);
      var operation =
          CompletableFuture.runAsync(
              () -> {
                try (consumer) {
                  core.acquire(
                      consumer,
                      issued.plan(),
                      issued.gateway(),
                      PrioritySourceScheduler.Demand.foreground(),
                      cancelled::get);
                }
              });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        cancelled.set(true);
        assertThrows(ExecutionException.class, () -> operation.get(3, TimeUnit.SECONDS));
        var copy = consumer.retirement().toCompletableFuture();
        assertFalse(copy.isDone());
        copy.complete(null);
        assertFalse(
            consumer.retirement().toCompletableFuture().isDone(),
            "Caller cannot forge actual source retirement");
      } finally {
        release.countDown();
      }
      consumer.retirement().toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertTrue(f.files.workers.isEmpty());
    }
  }

  @Test
  void sharedCoreBoundsLeasesAcrossIndependentConsumers() {
    var f = new SelectedFileFixture();
    try (var core = new EncodedSourceAcquisition(f.files, Runnable::run, null)) {
      var leases = new java.util.ArrayList<EncodedSourceAcquisition.ConsumerLease>();
      try {
        for (int slot = 0; slot < 8; slot++) {
          var key = new ContentSlotKey("wow", String.format("%032x", slot + 1));
          for (int consumer = 0; consumer < 32; consumer++) leases.add(core.enter(key, A));
          assertEquals(
              EncodedSourceException.Code.CAPACITY_EXCEEDED,
              assertThrows(EncodedSourceException.class, () -> core.enter(key, A)).code());
        }
        assertEquals(
            EncodedSourceException.Code.CAPACITY_EXCEEDED,
            assertThrows(
                    EncodedSourceException.class,
                    () -> core.enter(new ContentSlotKey("wow", "f".repeat(32)), A))
                .code());
      } finally {
        leases.forEach(EncodedSourceAcquisition.ConsumerLease::close);
      }
      try (var admittedAgain = core.enter(new ContentSlotKey("wow", C), A)) {
        assertNotNull(admittedAgain);
      }
    }
  }

  @Test
  void borrowedCoreRetainsCanonicalBytesWithoutInventingAnAssociation() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("selected", C);
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var core = new EncodedSourceAcquisition(f.files, Runnable::run, scheduler);
        var selected =
            new DefaultSelectedFileService(
                f.gateway,
                f.builds,
                f.files,
                f.pins,
                Runnable::run,
                Runnable::run,
                scheduler,
                core)) {
      var candidates = await(selected.planCandidates(variant.build(), variant, BUDGETS));
      var located = located(selected, candidates, 0).acquisition().orElseThrow();
      var authorized = f.gateway.authorizeEncodedSource(located, CancellationToken.none());
      try (var consumer = core.enter(variant.slot(), A)) {
        var receipt =
            core.acquire(
                consumer,
                authorized.plan(),
                authorized.gateway(),
                PrioritySourceScheduler.Demand.foreground(),
                CancellationToken.none());
        assertEquals(variant.slot(), receipt.slot());
        assertEquals(1, f.gateway.acquisitions);
        assertTrue(
            f.files.associations.isEmpty(), "Only the consumer may attach a typed association");
      }
      var result =
          await(
              selected.retain(
                  new SelectedFilePlan(
                      located.choice(),
                      java.util.Optional.of(located),
                      java.util.Optional.empty(),
                      located.cost())));
      assertEquals(variant.build().snapshotId(), result.snapshotId());
      assertEquals(1, f.gateway.acquisitions);
      selected.close();
      // Closing the facade must not close its borrowed source authority/core.
      try (var stillUsable = core.enter(variant.slot(), A)) {
        assertNotNull(stillUsable);
      }
    }
  }

  @Test
  void publicPlanTupleCannotGrantTransferWithoutItsOriginalIssuer() throws Exception {
    var f = new SelectedFileFixture();
    var variant = f.add("selected", C);
    try (var core = new EncodedSourceAcquisition(f.files, Runnable::run, null);
        var selected = f.service(Runnable::run, Runnable::run)) {
      var candidates = await(selected.planCandidates(variant.build(), variant, BUDGETS));
      var located = located(selected, candidates, 0).acquisition().orElseThrow();
      var issued = f.gateway.authorizeEncodedSource(located, CancellationToken.none());
      var p = issued.plan();
      var changed =
          new EncodedSourcePlan(
              "forged-session",
              p.sourceBuild(),
              p.slot(),
              p.encodingKey(),
              p.encodedBytes(),
              p.location(),
              p.cost(),
              p.limits());
      try (var consumer = core.enter(variant.slot(), A)) {
        var failure =
            assertThrows(
                EncodedSourceException.class,
                () ->
                    core.acquire(
                        consumer,
                        changed,
                        issued.gateway(),
                        PrioritySourceScheduler.Demand.foreground(),
                        CancellationToken.none()));
        assertEquals(EncodedSourceException.Code.INVALID_PLAN, failure.code());
        assertTrue(f.files.slots.isEmpty());
        assertEquals(0, f.gateway.acquisitions);
      }
    }
  }

  @Test
  void selectedAndIndependentConsumerShareOneActualFlightAndCancelOnlyTheirOwnMembership()
      throws Exception {
    for (boolean selectedStarts : java.util.List.of(false, true)) {
      var f = new SelectedFileFixture();
      var variant = f.add("selected", C);
      var bulkSlot = new ContentSlotKey("wow", "d".repeat(32));
      var publisherEntered = new CountDownLatch(1);
      var releasePublisher = new CountDownLatch(1);
      var followerClaimed = new CountDownLatch(1);
      var selectedThread = new AtomicReference<Thread>();
      var bulkThread = new AtomicReference<Thread>();
      var bulkPublications = new AtomicInteger();
      var bulkMemberships = new AtomicInteger();
      try (var jobs = Executors.newFixedThreadPool(2);
          var sources = Executors.newSingleThreadExecutor();
          var scheduler = new PrioritySourceScheduler(sources);
          var core = new EncodedSourceAcquisition(f.files, sources, scheduler);
          var selected =
              new DefaultSelectedFileService(
                  f.gateway,
                  f.builds,
                  f.files,
                  f.pins,
                  task ->
                      jobs.execute(
                          () -> {
                            selectedThread.set(Thread.currentThread());
                            task.run();
                          }),
                  sources,
                  scheduler,
                  core)) {
        var candidates = await(selected.planCandidates(variant.build(), variant, BUDGETS));
        var located = located(selected, candidates, 0).acquisition().orElseThrow();
        var original = f.gateway.authorizeEncodedSource(located, CancellationToken.none()).plan();
        var bulkPlan =
            new EncodedSourcePlan(
                "synthetic-inventory-member",
                original.sourceBuild(),
                bulkSlot,
                original.encodingKey(),
                original.encodedBytes(),
                original.location(),
                original.cost(),
                original.limits());
        var bulkOwner = scheduler.newBulkOwner(variant.build().snapshotId());
        var demand = bulkOwner.demand();
        EncodedSourceGateway bulkAuthority =
            new EncodedSourceGateway() {
              public void validate(EncodedSourcePlan issued, CancellationToken token) {
                token.throwIfCancellationRequested();
                if (!bulkPlan.equals(issued))
                  throw new EncodedSourceException(
                      EncodedSourceException.Code.INVALID_PLAN,
                      "Changed synthetic member authority");
              }

              public ContentRepresentation acquire(
                  EncodedSourcePlan issued, Observer observer, CancellationToken token) {
                validate(issued, token);
                bulkPublications.incrementAndGet();
                publisherEntered.countDown();
                hold(releasePublisher);
                var selectedBytes = representation(located);
                var receipt =
                    new ContentRepresentation(
                        bulkSlot,
                        selectedBytes.encoded(),
                        selectedBytes.scope(),
                        selectedBytes.sourceReceipt());
                observer.verifiedBeforePromotion(receipt);
                f.files.physical.add(receipt.encoded().object().digest());
                return receipt;
              }

              public ContentRepresentation inspectAvailable(
                  EncodedSourcePlan issued, ContentRepresentation value, CancellationToken token) {
                validate(issued, token);
                if (!f.files.physical.contains(value.encoded().object().digest()))
                  throw new EncodedSourceException(
                      EncodedSourceException.Code.MISSING_LOCAL, "Missing synthetic bytes");
                return value;
              }
            };
        f.gateway.enteringAcquisition =
            () -> {
              publisherEntered.countDown();
              hold(releasePublisher);
            };
        var followedSlot = selectedStarts ? bulkSlot : variant.slot();
        f.files.afterWorkerClaim =
            permit -> {
              if (f.files.slots.values().stream()
                  .anyMatch(
                      row ->
                          row.token().equals(permit.reservationToken())
                              && row.slot().equals(followedSlot))) followerClaimed.countDown();
            };
        java.util.function.Supplier<CompletableFuture<ContentRepresentation>> startBulk =
            () ->
                CompletableFuture.supplyAsync(
                    () -> {
                      bulkThread.set(Thread.currentThread());
                      try (var consumer = core.enter(bulkSlot, A)) {
                        var result =
                            core.acquire(
                                consumer,
                                bulkPlan,
                                bulkAuthority,
                                demand,
                                CancellationToken.none());
                        if (demand.isCancellationRequested())
                          throw new CancellationException("Bulk mode off");
                        bulkMemberships.incrementAndGet();
                        return result;
                      }
                    });
        CompletableFuture<ContentRepresentation> bulk;
        CompletionStage<SelectedFileAssociation> selectedResult;
        if (selectedStarts) {
          selectedResult =
              selected.retain(
                  new SelectedFilePlan(
                      located.choice(),
                      java.util.Optional.of(located),
                      java.util.Optional.empty(),
                      located.cost()));
          assertTrue(publisherEntered.await(2, TimeUnit.SECONDS));
          bulk = startBulk.get();
        } else {
          bulk = startBulk.get();
          assertTrue(publisherEntered.await(2, TimeUnit.SECONDS));
          selectedResult =
              selected.retain(
                  new SelectedFilePlan(
                      located.choice(),
                      java.util.Optional.of(located),
                      java.util.Optional.empty(),
                      located.cost()));
        }
        try {
          assertTrue(followerClaimed.await(2, TimeUnit.SECONDS));
          awaitBlocked(selectedStarts ? bulkThread : selectedThread);
          if (selectedStarts) selected.close();
          else scheduler.cancelBulk(bulkOwner);
          releasePublisher.countDown();
          if (selectedStarts) {
            assertThrows(CancellationException.class, () -> await(selectedResult));
            assertEquals(bulkSlot, bulk.get(3, TimeUnit.SECONDS).slot());
            assertEquals(1, bulkMemberships.get());
            assertTrue(f.files.associations.isEmpty());
          } else {
            assertEquals(variant.build().snapshotId(), await(selectedResult).snapshotId());
            assertThrows(ExecutionException.class, () -> bulk.get(3, TimeUnit.SECONDS));
            assertEquals(0, bulkMemberships.get());
            assertEquals(1, f.files.associations.size());
          }
          assertEquals(1, bulkPublications.get() + f.gateway.acquisitions);
          var a = f.files.slots.get(variant.slot()).representation().orElseThrow();
          var b = f.files.slots.get(bulkSlot).representation().orElseThrow();
          assertEquals(a.encoded(), b.encoded());
          assertEquals(a.sourceReceipt(), b.sourceReceipt());
          assertTrue(f.files.workers.isEmpty());
        } finally {
          releasePublisher.countDown();
        }
      }
    }
  }

  static void hold(CountDownLatch latch) {
    boolean done = false;
    while (!done) {
      try {
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        done = true;
      } catch (InterruptedException ignoredCancellation) {
      }
    }
  }

  static void awaitBlocked(AtomicReference<Thread> thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      var actual = thread.get();
      if (actual != null
          && (actual.getState() == Thread.State.WAITING
              || actual.getState() == Thread.State.TIMED_WAITING)) return;
      Thread.onSpinWait();
    }
    fail("Follower did not reach its source wait");
  }
}
