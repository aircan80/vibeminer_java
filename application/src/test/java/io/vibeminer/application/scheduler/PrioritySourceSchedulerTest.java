package io.vibeminer.application.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.ResolvedSourceIdentity;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PrioritySourceSchedulerTest {
  static ResolvedSourceIdentity source(int i) {
    return new ResolvedSourceIdentity("test.encoded", "key-" + i, "verified-v1");
  }

  @Test
  void reservesTwoSlotsForForegroundAndQueuedPromotionKeepsOneSource() {
    var executor = new Manual();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var owner = scheduler.newBulkOwner("a".repeat(64));
      var calls = new AtomicInteger();
      for (int i = 0; i < 2; i++) scheduler.submit(source(i), t -> {}, owner.demand());
      var queued = scheduler.submit(source(3), t -> calls.incrementAndGet(), owner.demand());
      assertEquals(2, executor.size());
      var foreground = queued.attach(PrioritySourceScheduler.Demand.foreground());
      assertEquals(3, executor.size());
      scheduler.submit(source(4), t -> {}, PrioritySourceScheduler.Demand.foreground());
      assertEquals(4, executor.size());
      scheduler.cancelBulk(owner);
      executor.drain();
      assertEquals(1, calls.get());
      queued.retirement().toCompletableFuture().join();
      foreground.close();
      assertThrows(
          PrioritySourceException.class,
          () -> scheduler.submit(source(8), t -> {}, owner.demand()));
    }
  }

  @Test
  void canceledIgnoredWorkerKeepsIdentityAndActualSlotUntilRunnableFinally() {
    var executor = new Manual();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var owner = scheduler.newBulkOwner("a".repeat(64));
      var first = scheduler.submit(source(1), t -> {}, owner.demand());
      scheduler.cancelBulk(owner);
      assertFalse(first.retirement().toCompletableFuture().isDone());
      var failure =
          assertThrows(
              PrioritySourceException.class,
              () ->
                  scheduler.submit(
                      source(1), t -> {}, PrioritySourceScheduler.Demand.foreground()));
      assertEquals(PrioritySourceException.Code.BUSY, failure.code());
      executor.drain();
      assertThrows(
          CompletionException.class, () -> first.retirement().toCompletableFuture().join());
      scheduler.submit(source(1), t -> {}, PrioritySourceScheduler.Demand.foreground());
      executor.drain();
    }
  }

  @Test
  void fifoLanesAdmitAnEligibleBulkAfterThreeForegroundDispatches() {
    var executor = new Manual();
    var order = new ArrayList<Integer>();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var owner = scheduler.newBulkOwner("a".repeat(64));
      for (int i = 0; i < 8; i++) {
        int value = i;
        scheduler.submit(
            source(i), t -> order.add(value), PrioritySourceScheduler.Demand.foreground());
      }
      scheduler.submit(source(8), t -> order.add(8), owner.demand());
      executor.drain();
      assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 8, 7), order);
    }
  }

  @Test
  void runningBulkPromotionNeverReclaimsItsBulkAdmissionBudget() {
    var executor = new Manual();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var owner = scheduler.newBulkOwner("a".repeat(64));
      var running = scheduler.submit(source(0), t -> {}, owner.demand());
      scheduler.submit(source(1), t -> {}, owner.demand());
      scheduler.submit(source(2), t -> {}, owner.demand());
      var foreground = running.attach(PrioritySourceScheduler.Demand.foreground());
      var pending = scheduler.submit(source(3), t -> {}, owner.demand());
      assertEquals(2, executor.size());
      foreground.close();
      assertEquals(2, executor.size());
      var interactive = pending.attach(PrioritySourceScheduler.Demand.foreground());
      assertEquals(3, executor.size());
      scheduler.submit(source(4), t -> {}, PrioritySourceScheduler.Demand.foreground());
      assertEquals(4, executor.size());
      interactive
          .close(); // Legitimately foreground-admitted work keeps its actual slot until finally.
      assertEquals(4, executor.size());
      executor.drain();
    }
  }

  @Test
  void distinctBuildAndForegroundConsumersSurviveModeOffUntilTheirOwnDetach() throws Exception {
    var executor = new Manual();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var token = new AtomicReference<io.vibeminer.application.CancellationToken>();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var a = scheduler.newBulkOwner("a".repeat(64));
      var b = scheduler.newBulkOwner("b".repeat(64));
      var flight =
          scheduler.submit(
              source(0),
              cancellation -> {
                token.set(cancellation);
                entered.countDown();
                await(release);
              },
              a.demand());
      flight.attach(b.demand());
      var interactive = flight.attach(PrioritySourceScheduler.Demand.foreground());
      var actual = new Thread(executor::runOne);
      actual.start();
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        scheduler.cancelBulk(a);
        assertFalse(token.get().isCancellationRequested());
        scheduler.cancelBulk(b);
        assertFalse(token.get().isCancellationRequested());
        interactive.close();
        assertTrue(token.get().isCancellationRequested());
        assertFalse(flight.retirement().toCompletableFuture().isDone());
        release.countDown();
        actual.join(2000);
        assertFalse(actual.isAlive());
        flight
            .retirement()
            .toCompletableFuture()
            .join(); // Actual successful work wins late cancellation.
      } finally {
        release.countDown();
        actual.join(2000);
      }
    }
  }

  @Test
  void modeOffRemovesUndispatchedBulkButCannotStopAnotherGeneration() {
    var executor = new Manual();
    var called = new AtomicInteger();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var old = scheduler.newBulkOwner("a".repeat(64));
      var next = scheduler.newBulkOwner("a".repeat(64));
      assertNotEquals(old.generation(), next.generation());
      for (int i = 0; i < 2; i++) scheduler.submit(source(i), t -> {}, next.demand());
      var canceled =
          scheduler.submit(source(3), t -> fail("Disabled queued work ran"), old.demand());
      var retained = scheduler.submit(source(4), t -> called.incrementAndGet(), next.demand());
      scheduler.cancelBulk(old);
      assertTrue(canceled.retirement().toCompletableFuture().isCompletedExceptionally());
      assertFalse(retained.retirement().toCompletableFuture().isDone());
      executor.drain();
      assertEquals(1, called.get());
    }
  }

  @Test
  void exactIdentityRequiresIssuedFlightHandleAndRetirementCopyCannotCancelIt() {
    var executor = new Manual();
    var calls = new AtomicInteger();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var first =
          scheduler.submit(
              source(1), t -> calls.incrementAndGet(), PrioritySourceScheduler.Demand.foreground());
      var failure =
          assertThrows(
              PrioritySourceException.class,
              () ->
                  scheduler.submit(
                      source(1),
                      t -> fail("Independent producer ran"),
                      PrioritySourceScheduler.Demand.foreground()));
      assertEquals(PrioritySourceException.Code.BUSY, failure.code());
      first.attach(PrioritySourceScheduler.Demand.foreground());
      first.retirement().toCompletableFuture().cancel(true);
      scheduler.submit(
          new ResolvedSourceIdentity("test.encoded", "key-1", "different-v2"),
          t -> calls.incrementAndGet(),
          PrioritySourceScheduler.Demand.foreground());
      executor.drain();
      assertEquals(2, calls.get());
      first.retirement().toCompletableFuture().join();
    }
  }

  @Test
  void boundsCoverQueuePerSourceAndGlobalDemandWithoutDiscardingAcceptedWork() {
    var executor = new Manual();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var first = scheduler.submit(source(0), t -> {}, PrioritySourceScheduler.Demand.foreground());
      for (int i = 1; i < 32; i++) first.attach(PrioritySourceScheduler.Demand.foreground());
      assertCapacity(() -> first.attach(PrioritySourceScheduler.Demand.foreground()));
      for (int i = 1; i < 68; i++)
        scheduler.submit(source(i), t -> {}, PrioritySourceScheduler.Demand.foreground());
      assertCapacity(
          () -> scheduler.submit(source(68), t -> {}, PrioritySourceScheduler.Demand.foreground()));
      executor.drain();
    }
    try (var scheduler = new PrioritySourceScheduler(new Manual())) {
      var flights = new ArrayList<PrioritySourceScheduler.ScheduledSource>();
      for (int i = 0; i < 8; i++) {
        var flight =
            scheduler.submit(source(i), t -> {}, PrioritySourceScheduler.Demand.foreground());
        flights.add(flight);
        for (int j = 1; j < 32; j++) flight.attach(PrioritySourceScheduler.Demand.foreground());
      }
      assertCapacity(
          () -> scheduler.submit(source(9), t -> {}, PrioritySourceScheduler.Demand.foreground()));
      flights.getFirst().initialDemand().close();
      scheduler.submit(source(9), t -> {}, PrioritySourceScheduler.Demand.foreground());
    }
  }

  @Test
  void directExecutorWorkAndTerminalCallbacksCanReenterFromAnotherThread() {
    var owner = new AtomicReference<PrioritySourceScheduler>();
    java.util.concurrent.Executor direct =
        task -> {
          crossThread(() -> owner.get().newBulkOwner("c".repeat(64)));
          task.run();
        };
    try (var scheduler = new PrioritySourceScheduler(direct)) {
      owner.set(scheduler);
      scheduler
          .submit(
              source(0),
              t -> crossThread(() -> scheduler.newBulkOwner("b".repeat(64))),
              PrioritySourceScheduler.Demand.foreground())
          .retirement()
          .toCompletableFuture()
          .join();
    }
    var queue = new Manual();
    try (var scheduler = new PrioritySourceScheduler(queue)) {
      var first = scheduler.submit(source(0), t -> {}, PrioritySourceScheduler.Demand.foreground());
      var callback =
          first
              .retirement()
              .thenRun(
                  () ->
                      crossThread(
                          () ->
                              scheduler.submit(
                                  source(0),
                                  t -> {},
                                  PrioritySourceScheduler.Demand.foreground())));
      queue.drain();
      callback.toCompletableFuture().join();
    }
  }

  @Test
  void rejectionFailureAndCloseHaveAuthoritativeRetirementWithoutExecutorOwnership() {
    var failure = new IllegalStateException("precise worker failure");
    try (var scheduler = new PrioritySourceScheduler(Runnable::run)) {
      var failed =
          scheduler.submit(
              source(0),
              t -> {
                throw failure;
              },
              PrioritySourceScheduler.Demand.foreground());
      assertSame(
          failure,
          assertThrows(
                  CompletionException.class, () -> failed.retirement().toCompletableFuture().join())
              .getCause());
    }
    try (var scheduler =
        new PrioritySourceScheduler(
            task -> {
              throw new RejectedExecutionException();
            })) {
      var failed =
          scheduler.submit(
              source(0),
              t -> fail("Rejected work ran"),
              PrioritySourceScheduler.Demand.foreground());
      assertInstanceOf(
          PrioritySourceException.class,
          assertThrows(
                  CompletionException.class, () -> failed.retirement().toCompletableFuture().join())
              .getCause());
    }
    var queue = new Manual();
    var scheduler = new PrioritySourceScheduler(queue);
    var active =
        scheduler.submit(
            source(0),
            t -> fail("Closed dispatched work ran"),
            PrioritySourceScheduler.Demand.foreground());
    scheduler.close();
    assertFalse(active.retirement().toCompletableFuture().isDone());
    assertEquals(
        PrioritySourceException.Code.CLOSED,
        assertThrows(
                PrioritySourceException.class,
                () ->
                    scheduler.submit(
                        source(1), t -> {}, PrioritySourceScheduler.Demand.foreground()))
            .code());
    queue.drain();
    assertTrue(active.retirement().toCompletableFuture().isDone());
    try (var other = new PrioritySourceScheduler(Runnable::run)) {
      var foreign = other.newBulkOwner("a".repeat(64));
      assertThrows(PrioritySourceException.class, () -> scheduler.cancelBulk(foreign));
    }
  }

  @Test
  void fatalSynchronousDispatchCannotStrandTheRestOfAnAdmittedBatch() throws Exception {
    var queue = new Manual();
    var synchronous = new AtomicBoolean();
    java.util.concurrent.Executor executor =
        task -> {
          if (synchronous.get()) task.run();
          else queue.execute(task);
        };
    var callbacks = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var fatal = new AssertionError("authored producer failure");
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var initial = new ArrayList<PrioritySourceScheduler.ScheduledSource>();
      for (int i = 0; i < 4; i++)
        initial.add(
            scheduler.submit(source(i), t -> {}, PrioritySourceScheduler.Demand.foreground()));
      for (int i = 0; i < 2; i++)
        initial
            .get(i)
            .retirement()
            .thenRun(
                () -> {
                  callbacks.countDown();
                  await(release);
                });
      var failed =
          scheduler.submit(
              source(4),
              t -> {
                throw fatal;
              },
              PrioritySourceScheduler.Demand.foreground());
      var survivor =
          scheduler.submit(source(5), t -> {}, PrioritySourceScheduler.Demand.foreground());
      var a = new Thread(queue.queue.remove());
      var b = new Thread(queue.queue.remove());
      a.start();
      b.start();
      try {
        assertTrue(callbacks.await(2, TimeUnit.SECONDS));
        synchronous.set(true);
        assertSame(
            fatal,
            assertThrows(
                AssertionError.class,
                () -> failed.attach(PrioritySourceScheduler.Demand.foreground())));
        assertTrue(
            survivor.retirement().toCompletableFuture().isDone(),
            "Every pre-admitted wrapper must dispatch or retire despite an earlier fatal producer");
        survivor.retirement().toCompletableFuture().join();
        assertSame(
            fatal,
            assertThrows(
                    CompletionException.class,
                    () -> failed.retirement().toCompletableFuture().join())
                .getCause());
      } finally {
        release.countDown();
        a.join(2000);
        b.join(2000);
        queue.drain();
      }
    }
  }

  @Test
  void executorErrorBeforeWrapperInvocationRetiresItsSourceAndPreservesFatalCause() {
    var fatal = new AssertionError("executor failed before invocation");
    try (var scheduler =
        new PrioritySourceScheduler(
            task -> {
              throw fatal;
            })) {
      assertSame(
          fatal,
          assertThrows(
              AssertionError.class,
              () ->
                  scheduler.submit(
                      source(0),
                      t -> fail("Rejected source ran"),
                      PrioritySourceScheduler.Demand.foreground())));
      // A second attempt reaches the executor again, rather than a leaked source returning BUSY.
      assertSame(
          fatal,
          assertThrows(
              AssertionError.class,
              () ->
                  scheduler.submit(
                      source(0),
                      t -> fail("Rejected source ran"),
                      PrioritySourceScheduler.Demand.foreground())));
    }
  }

  @Test
  void internalBindingFailureOrReentrantCloseCannotExecuteOrStrandProducer() {
    var queue = new Manual();
    try (var scheduler = new PrioritySourceScheduler(queue)) {
      var issued = new AtomicReference<PrioritySourceScheduler.ScheduledSource>();
      var failure = new IllegalStateException("binding refused");
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  scheduler.submit(
                      source(0),
                      t -> fail("Unbound producer executed"),
                      PrioritySourceScheduler.Demand.foreground(),
                      handle -> {
                        issued.set(handle);
                        crossThread(() -> scheduler.newBulkOwner("a".repeat(64)));
                        throw failure;
                      })));
      assertSame(
          failure,
          assertThrows(
                  CompletionException.class,
                  () -> issued.get().retirement().toCompletableFuture().join())
              .getCause());
      assertEquals(0, queue.size());
      scheduler.submit(source(0), t -> {}, PrioritySourceScheduler.Demand.foreground());
      queue.drain();
    }
    var scheduler = new PrioritySourceScheduler(Runnable::run);
    var retired =
        scheduler.submit(
            source(0),
            t -> fail("Closed unbound producer executed"),
            PrioritySourceScheduler.Demand.foreground(),
            issued -> scheduler.close());
    assertTrue(retired.retirement().toCompletableFuture().isCompletedExceptionally());
  }

  static void assertCapacity(Runnable action) {
    assertEquals(
        PrioritySourceException.Code.CAPACITY_EXCEEDED,
        assertThrows(PrioritySourceException.class, action::run).code());
  }

  static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      throw new AssertionError(failure);
    }
  }

  static void crossThread(Runnable action) {
    try {
      CompletableFuture.runAsync(action).get(2, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static final class Manual implements java.util.concurrent.Executor {
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    public void execute(Runnable task) {
      queue.add(task);
    }

    int size() {
      return queue.size();
    }

    void runOne() {
      queue.remove().run();
    }

    void drain() {
      while (!queue.isEmpty()) runOne();
    }
  }
}
