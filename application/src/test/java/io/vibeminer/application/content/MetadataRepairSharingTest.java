package io.vibeminer.application.content;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class MetadataRepairSharingTest {
  final DefaultMetadataRepairServiceTest fixture = new DefaultMetadataRepairServiceTest();

  @Test
  void sameConsentSharesSourceAndAttachesEachSurvivor() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      var first = service.execute(plan).toCompletableFuture();
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      var second = service.execute(plan).toCompletableFuture();
      fixture.release.countDown();
      assertEquals(
          first.get(10, TimeUnit.SECONDS).completedAudit(),
          second.get(10, TimeUnit.SECONDS).completedAudit());
      assertEquals(1, fixture.events.stream().filter("GET"::equals).count());
      assertEquals(2, fixture.events.stream().filter("attach"::equals).count());
    }
  }

  @Test
  void firstCancellationDoesNotCancelSharedSource() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      var first = service.execute(plan).toCompletableFuture();
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      var second = service.execute(plan).toCompletableFuture();
      first.cancel(true);
      fixture.release.countDown();
      assertTrue(second.get(10, TimeUnit.SECONDS).completedAudit().isPresent());
      assertEquals(1, fixture.events.stream().filter("GET"::equals).count());
      assertEquals(1, fixture.events.stream().filter("attach"::equals).count());
    }
  }

  @Test
  void cancellationChurnCannotExceedLifetimeSubscriberBound() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      var first = service.execute(plan).toCompletableFuture();
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      for (int i = 1; i < 32; i++) service.execute(plan).toCompletableFuture().cancel(false);
      var failure =
          assertInstanceOf(ContentClaimException.class, fixture.failure(service.execute(plan)));
      assertEquals(ContentClaimException.Code.BUSY, failure.code());
      fixture.release.countDown();
      assertTrue(first.get(10, TimeUnit.SECONDS).completedAudit().isPresent());
      assertEquals(1, fixture.events.stream().filter("attach"::equals).count());
    }
  }

  @Test
  void allCancelledIgnoredIoKeepsOwnerAndRejectsLateJoin() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      fixture.retired = new CountDownLatch(1);
      var first = service.execute(plan).toCompletableFuture();
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      var second = service.execute(plan).toCompletableFuture();
      first.cancel(false);
      second.cancel(false);
      assertNotNull(fixture.owner);
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertInstanceOf(ContentClaimException.class, fixture.failure(service.execute(plan)))
              .code());
      assertFalse(fixture.events.contains("retire"));
      fixture.release.countDown();
      assertTrue(fixture.retired.await(10, TimeUnit.SECONDS));
      assertFalse(fixture.events.contains("attach"));
    }
  }

  @Test
  void queuedCancellationNeverClaimsAuthority() {
    var queued = new java.util.ArrayList<Runnable>();
    try (var service = fixture.service(queued::add)) {
      var preparing = service.prepareRoot(fixture.root()).toCompletableFuture();
      queued.removeFirst().run();
      var plan = preparing.join();
      var first = service.execute(plan).toCompletableFuture();
      var second = service.execute(plan).toCompletableFuture();
      first.cancel(false);
      second.cancel(false);
      queued.removeFirst().run();
      assertFalse(fixture.events.contains("GET"));
      assertNull(fixture.owner);
    }
  }

  @Test
  void rejectedExecutionReleasesAdmissionWithoutClaimingAuthority() {
    var reject = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.Executor executor =
        action -> {
          if (reject.get()) throw new RejectedExecutionException("fixture");
          action.run();
        };
    try (var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      reject.set(true);
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertInstanceOf(ContentClaimException.class, fixture.failure(service.execute(plan)))
              .code());
      assertNull(fixture.owner);
      assertFalse(fixture.events.contains("GET"));
      reject.set(false);
      assertTrue(service.execute(plan).toCompletableFuture().join().completedAudit().isPresent());
    }
  }

  @Test
  void closeCancelsEverySubscriberButRetiresOnlyAfterIo() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      fixture.retired = new CountDownLatch(1);
      var first = service.execute(plan).toCompletableFuture();
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      var second = service.execute(plan).toCompletableFuture();
      service.close();
      assertTrue(first.isCancelled());
      assertTrue(second.isCancelled());
      assertNotNull(fixture.owner);
      fixture.release.countDown();
      assertTrue(fixture.retired.await(10, TimeUnit.SECONDS));
      assertFalse(fixture.events.contains("attach"));
    }
  }

  @Test
  void attachmentFailureIsLocalAndLateJoinRunsOnActualWorker() throws Exception {
    var caller = Thread.currentThread();
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var late =
        new java.util.concurrent.atomic.AtomicReference<CompletableFuture<MetadataRepairResult>>();
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      fixture.attachmentHook =
          () -> {
            assertNotSame(caller, Thread.currentThread());
            if (count.getAndIncrement() == 0) {
              late.set(service.execute(plan).toCompletableFuture());
              throw new IllegalStateException("first consumer only");
            }
          };
      var first = service.execute(plan).toCompletableFuture();
      assertInstanceOf(IllegalStateException.class, fixture.failure(first));
      assertTrue(late.get().get(10, TimeUnit.SECONDS).completedAudit().isPresent());
      assertEquals(1, fixture.events.stream().filter("GET"::equals).count());
      assertEquals(2, count.get());
    }
  }

  @Test
  void differentIssuanceCannotJoinSameCanonicalWorker() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var firstPlan = fixture.prepare(service);
      var secondPlan = fixture.prepare(service);
      fixture.issued = firstPlan;
      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      var first = service.execute(firstPlan).toCompletableFuture();
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertInstanceOf(
                  ContentClaimException.class, fixture.failure(service.execute(secondPlan)))
              .code());
      fixture.release.countDown();
      first.get(10, TimeUnit.SECONDS);
      assertEquals(1, fixture.events.stream().filter("GET"::equals).count());
    }
  }

  @Test
  void canceledQueuedFlightOccupiesOneActualSlotUntilRunnableRetires() {
    var queued = new java.util.ArrayList<Runnable>();
    try (var service = fixture.service(queued::add)) {
      var preparing = service.prepareRoot(fixture.root()).toCompletableFuture();
      queued.removeFirst().run();
      var plan = preparing.join();
      var first = service.execute(plan).toCompletableFuture();
      var second = service.execute(plan).toCompletableFuture();
      for (int i = 0; i < 3; i++) service.prepareRoot(fixture.root());
      first.cancel(false);
      second.cancel(false);
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertInstanceOf(
                  ContentClaimException.class, fixture.failure(service.prepareRoot(fixture.root())))
              .code());
      assertEquals(4, queued.size());
      queued.removeFirst().run();
      var admitted = service.prepareRoot(fixture.root()).toCompletableFuture();
      assertFalse(admitted.isDone());
      assertEquals(4, queued.size());
      assertNull(fixture.owner);
    }
  }

  @Test
  void sharedSourceFailureCompletesBothWithoutAttachment() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = fixture.service(executor)) {
      var plan = fixture.prepare(service);
      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      fixture.failBeforeGate = new IllegalStateException("source failed");
      var first = service.execute(plan).toCompletableFuture();
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      var second = service.execute(plan).toCompletableFuture();
      fixture.release.countDown();
      assertSame(fixture.failure(first), fixture.failure(second));
      assertEquals(1, fixture.events.stream().filter("GET"::equals).count());
      assertFalse(fixture.events.contains("attach"));
      assertNull(fixture.owner);
    }
  }
}
