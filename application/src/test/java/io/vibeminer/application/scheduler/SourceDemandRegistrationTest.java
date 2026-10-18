package io.vibeminer.application.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class SourceDemandRegistrationTest {
  @Test
  void acceptedCanceledWrapperReportsNeverStartedOnlyAfterItsActualRetirement() {
    var queue = new PrioritySourceSchedulerTest.Manual();
    try (var scheduler = new PrioritySourceScheduler(queue)) {
      var owner = scheduler.newBulkOwner("a".repeat(64));
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(0));
      var rejected = new AtomicInteger();
      registration.register(owner.demand(), failure -> fail(failure));
      registration.start(
          token -> fail("Canceled producer entered"), failure -> rejected.incrementAndGet());
      scheduler.cancelBulk(owner);
      assertEquals(0, rejected.get());
      queue.drain();
      assertEquals(1, rejected.get());
      registration.close();
      assertEquals(1, rejected.get());
    }
  }

  @Test
  void directProducerSuccessAndFatalFailureNeverRunNeverStartedCleanup() {
    try (var scheduler = new PrioritySourceScheduler(Runnable::run)) {
      var completed = new AtomicInteger();
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(0));
      registration.register(PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      registration.start(
          token -> {
            registration.close();
            completed.incrementAndGet();
          },
          failure -> fail(failure));
      assertEquals(1, completed.get());
      var fatal = new AssertionError("producer fatal");
      var failed = new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(1));
      failed.register(PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      assertSame(
          fatal,
          assertThrows(
              AssertionError.class,
              () ->
                  failed.start(
                      token -> {
                        throw fatal;
                      },
                      failure -> fail("Entered producer was cleaned twice"))));
    }
  }

  @Test
  void queuedSourcePromotesAndCancellationBeforeBindingClosesOnlyOwnDemand() {
    var queue = new PrioritySourceSchedulerTest.Manual();
    try (var scheduler = new PrioritySourceScheduler(queue)) {
      var owner = scheduler.newBulkOwner("a".repeat(64));
      for (int i = 0; i < 2; i++)
        scheduler.submit(PrioritySourceSchedulerTest.source(i), t -> {}, owner.demand());
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(3));
      var bulk = registration.register(owner.demand(), failure -> fail(failure));
      var calls = new AtomicInteger();
      registration.start(
          token -> {
            assertFalse(token.isCancellationRequested());
            calls.incrementAndGet();
          },
          failure -> fail(failure));
      var canceled =
          registration.register(
              PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      canceled.close();
      registration.register(PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      bulk.close();
      scheduler.cancelBulk(owner);
      queue.drain();
      assertEquals(1, calls.get());
    }
  }

  @Test
  void disabledInitialGenerationCanYieldToSurvivingForegroundDemand() {
    var queue = new PrioritySourceSchedulerTest.Manual();
    try (var scheduler = new PrioritySourceScheduler(queue)) {
      var owner = scheduler.newBulkOwner("a".repeat(64));
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(0));
      var rejected = new AtomicInteger();
      registration.register(
          owner.demand(),
          failure -> {
            assertEquals(
                PrioritySourceException.Code.BULK_DISABLED,
                assertInstanceOf(PrioritySourceException.class, failure).code());
            rejected.incrementAndGet();
          });
      registration.register(PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      scheduler.cancelBulk(owner);
      var calls = new AtomicInteger();
      registration.start(token -> calls.incrementAndGet(), failure -> fail(failure));
      queue.drain();
      assertEquals(1, calls.get());
      assertEquals(1, rejected.get());
    }
  }

  @Test
  void noDemandAndExecutorRejectionHaveOneCleanupOutsideRegistrationLock() {
    try (var scheduler = new PrioritySourceScheduler(Runnable::run)) {
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(0));
      var count = new AtomicInteger();
      registration.start(
          token -> fail("No consumer producer"),
          failure -> {
            crossThread(registration::close);
            count.incrementAndGet();
          });
      assertEquals(1, count.get());
    }
    try (var scheduler =
        new PrioritySourceScheduler(
            task -> {
              throw new RejectedExecutionException();
            })) {
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(0));
      registration.register(PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      var count = new AtomicInteger();
      registration.start(
          token -> fail("Rejected producer entered"),
          failure -> {
            crossThread(registration::close);
            count.incrementAndGet();
          });
      assertEquals(1, count.get());
    }
  }

  @Test
  void foregroundEnrollingWhileExecutorHasNotReturnedSurvivesBulkModeOff() throws Exception {
    var queue = new PrioritySourceSchedulerTest.Manual();
    var executorEntered = new CountDownLatch(1);
    var returnFromExecute = new CountDownLatch(1);
    java.util.concurrent.Executor executor =
        task -> {
          executorEntered.countDown();
          try {
            assertTrue(returnFromExecute.await(3, TimeUnit.SECONDS));
          } catch (InterruptedException failure) {
            throw new AssertionError(failure);
          }
          queue.execute(task);
        };
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var bulk = scheduler.newBulkOwner("a".repeat(64));
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(0));
      var failures = new AtomicInteger();
      registration.register(bulk.demand(), failure -> failures.incrementAndGet());
      var calls = new AtomicInteger();
      var starting =
          CompletableFuture.runAsync(
              () ->
                  registration.start(
                      token -> {
                        assertFalse(token.isCancellationRequested());
                        calls.incrementAndGet();
                      },
                      failure -> failures.incrementAndGet()));
      try {
        assertTrue(executorEntered.await(2, TimeUnit.SECONDS));
        registration.register(
            PrioritySourceScheduler.Demand.foreground(), failure -> failures.incrementAndGet());
        scheduler.cancelBulk(bulk);
        returnFromExecute.countDown();
        starting.get(2, TimeUnit.SECONDS);
        queue.drain();
        assertEquals(1, calls.get(), "Already enrolled foreground demand must survive mode-off");
        assertEquals(0, failures.get());
      } finally {
        returnFromExecute.countDown();
        starting.get(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void unrelatedFatalDispatchCannotRetireAnAcceptedUnrunRegistration() throws Exception {
    var queue = new LinkedBlockingQueue<Runnable>();
    var dispatch = new AtomicBoolean();
    var dispatchNumber = new AtomicInteger();
    java.util.concurrent.Executor executor =
        task -> {
          if (dispatch.get() && dispatchNumber.getAndIncrement() == 0) task.run();
          else queue.add(task);
        };
    var callbacksEntered = new CountDownLatch(2);
    var releaseCallbacks = new CountDownLatch(1);
    var neverStarted = new AtomicInteger();
    try (var scheduler = new PrioritySourceScheduler(executor)) {
      var first =
          scheduler.submit(
              PrioritySourceSchedulerTest.source(0),
              t -> {},
              PrioritySourceScheduler.Demand.foreground());
      var second =
          scheduler.submit(
              PrioritySourceSchedulerTest.source(1),
              t -> {},
              PrioritySourceScheduler.Demand.foreground());
      for (int i = 2; i < 4; i++)
        scheduler.submit(
            PrioritySourceSchedulerTest.source(i),
            t -> {},
            PrioritySourceScheduler.Demand.foreground());
      scheduler.submit(
          PrioritySourceSchedulerTest.source(4),
          t -> {
            throw new AssertionError("Other producer");
          },
          PrioritySourceScheduler.Demand.foreground());
      first
          .retirement()
          .whenComplete(
              (v, e) -> {
                callbacksEntered.countDown();
                PrioritySourceSchedulerTest.await(releaseCallbacks);
              });
      second
          .retirement()
          .whenComplete(
              (v, e) -> {
                callbacksEntered.countDown();
                PrioritySourceSchedulerTest.await(releaseCallbacks);
              });
      var a = new Thread(queue.remove());
      var b = new Thread(queue.remove());
      a.start();
      b.start();
      var registration =
          new SourceDemandRegistration(scheduler, PrioritySourceSchedulerTest.source(5));
      registration.register(PrioritySourceScheduler.Demand.foreground(), failure -> fail(failure));
      try {
        assertTrue(callbacksEntered.await(2, TimeUnit.SECONDS));
        dispatch.set(true);
        assertThrows(
            AssertionError.class,
            () ->
                registration.start(
                    token -> fail("Withdrawn wrapper must not enter"),
                    failure -> neverStarted.incrementAndGet()));
        assertFalse(
            registration.retirement().toCompletableFuture().isDone(),
            "Executor-accepted wrapper remains owned until its actual finally");
        assertEquals(0, neverStarted.get());
      } finally {
        releaseCallbacks.countDown();
        a.join(2000);
        b.join(2000);
      }
      while (!queue.isEmpty()) queue.remove().run();
      assertTrue(registration.retirement().toCompletableFuture().isDone());
      assertEquals(1, neverStarted.get());
    }
  }

  static void crossThread(Runnable action) {
    try {
      CompletableFuture.runAsync(action).get(2, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }
}
