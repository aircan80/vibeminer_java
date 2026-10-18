package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazySelectedFileServiceTest {
  @Test
  void untouchedFacadeDoesNotInitializeAndClosedFailureIsTyped() {
    var calls = new AtomicInteger();
    var owner =
        new LazyBootstrapService(
            () -> {
              calls.incrementAndGet();
              throw new AssertionError();
            });
    var facade = new LazySelectedFileService(owner);
    assertEquals(0, calls.get());
    facade.close();
    owner.close();
    var failure =
        assertThrows(
            CompletionException.class,
            () -> facade.planCandidates(null, null, null).toCompletableFuture().join());
    assertEquals(
        SelectedFileException.Code.CLOSED,
        assertInstanceOf(SelectedFileException.class, failure.getCause()).code());
    assertEquals(0, calls.get());
  }

  @Test
  void initializationFailureCanRetryAndAllFacadesShareTheOwner() {
    var calls = new AtomicInteger();
    var closed = new AtomicInteger();
    var owner =
        new LazyBootstrapService(
            () -> {
              if (calls.incrementAndGet() == 1) throw new IllegalStateException("test lock");
              return resources(CompletableFuture.completedFuture(null), closed);
            });
    var facade = new LazySelectedFileService(owner);
    try {
      var failure =
          assertThrows(
              CompletionException.class,
              () -> facade.planCandidates(null, null, null).toCompletableFuture().join());
      assertEquals(
          SelectedFileException.Code.STORAGE_UNAVAILABLE,
          assertInstanceOf(SelectedFileException.class, failure.getCause()).code());
      assertNull(facade.planCandidates(null, null, null).toCompletableFuture().join());
      assertEquals(2, calls.get());
    } finally {
      facade.close();
      owner.close();
    }
    assertEquals(1, closed.get());
  }

  @Test
  void cancellationBeforeDelegateRegistrationReachesSelectedJob() throws Exception {
    var factoryRelease = new CountDownLatch(1);
    var actionEntered = new CountDownLatch(1);
    var delegateRelease = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var job =
        new CompletableFuture<CandidatePlan>() {
          public boolean cancel(boolean interrupt) {
            boolean changed = super.cancel(interrupt);
            if (isCancelled()) cancelled.countDown();
            return changed;
          }
        };
    var owner =
        new LazyBootstrapService(
            () -> {
              await(factoryRelease);
              var base = resources(job, closed);
              return new LazyBootstrapService.Resources() {
                public BootstrapService service() {
                  return base.service();
                }

                public SelectedFileService selectedFiles() {
                  actionEntered.countDown();
                  await(delegateRelease);
                  return base.selectedFiles();
                }

                public void close() {
                  base.close();
                }
              };
            });
    var facade = new LazySelectedFileService(owner);
    try {
      var request = facade.planCandidates(null, null, null).toCompletableFuture();
      factoryRelease.countDown();
      assertTrue(actionEntered.await(2, TimeUnit.SECONDS));
      assertTrue(request.cancel(true));
      delegateRelease.countDown();
      assertTrue(cancelled.await(2, TimeUnit.SECONDS));
    } finally {
      factoryRelease.countDown();
      delegateRelease.countDown();
      facade.close();
    }
    assertEquals(1, closed.get());
  }

  @Test
  void pendingSelectedConsumerIsCancelledBySharedOwnerClose() throws Exception {
    var invoked = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var job = new CompletableFuture<CandidatePlan>();
    var owner = new LazyBootstrapService(() -> resources(job, closed, invoked));
    var facade = new LazySelectedFileService(owner);
    var request = facade.planCandidates(null, null, null).toCompletableFuture();
    assertTrue(invoked.await(2, TimeUnit.SECONDS));
    owner.close();
    assertTrue(request.isCancelled());
    assertTrue(job.isCancelled());
    facade.close();
    assertEquals(1, closed.get());
  }

  @Test
  void variantOfflineOverloadForwardsExactContextAndDoesNotAffectAnotherConsumer()
      throws Exception {
    var build =
        new BuildSnapshot(
            "wow",
            "Synthetic",
            "Published",
            "us",
            "one",
            "a".repeat(32),
            "b".repeat(32),
            Instant.EPOCH,
            true);
    var variant =
        new FileVariantAssociation(
            build,
            new ResolvedSourceIdentity("test.root", "root", "v1"),
            42,
            "c".repeat(32),
            2,
            3,
            4,
            5,
            6,
            Optional.empty());
    var budgets = SelectedFileBudgets.defaults();
    var firstJob = new CompletableFuture<SelectedFileAssociation>();
    var secondJob = new CompletableFuture<SelectedFileAssociation>();
    var jobs =
        new ConcurrentLinkedQueue<CompletableFuture<SelectedFileAssociation>>(
            List.of(firstJob, secondJob));
    var invoked = new CountDownLatch(2);
    var firstInvoked = new CountDownLatch(1);
    var releaseInitialization = new CountDownLatch(1);
    var releaseFirstReturn = new CountDownLatch(1);
    var firstCancelled = new CountDownLatch(1);
    firstJob.whenComplete(
        (value, failure) -> {
          if (firstJob.isCancelled()) firstCancelled.countDown();
        });
    var closed = new AtomicInteger();
    var delegate =
        (SelectedFileService)
            Proxy.newProxyInstance(
                SelectedFileService.class.getClassLoader(),
                new Class<?>[] {SelectedFileService.class},
                (proxy, method, args) -> {
                  assertEquals("inspectOffline", method.getName());
                  assertEquals(FileVariantAssociation.class, method.getParameterTypes()[0]);
                  assertSame(variant, args[0]);
                  assertSame(budgets, args[1]);
                  var job = jobs.remove();
                  invoked.countDown();
                  if (job == firstJob) {
                    firstInvoked.countDown();
                    await(releaseFirstReturn);
                  }
                  return job;
                });
    var owner =
        new LazyBootstrapService(
            () -> {
              await(releaseInitialization);
              return new LazyBootstrapService.Resources() {
                public BootstrapService service() {
                  throw new AssertionError("No install action");
                }

                public SelectedFileService selectedFiles() {
                  return delegate;
                }

                public void close() {
                  closed.incrementAndGet();
                }
              };
            });
    var facade = new LazySelectedFileService(owner);
    try {
      var first = facade.inspectOffline(variant, budgets).toCompletableFuture();
      releaseInitialization.countDown();
      assertTrue(firstInvoked.await(2, TimeUnit.SECONDS));
      var second = facade.inspectOffline(variant, budgets).toCompletableFuture();
      assertTrue(invoked.await(2, TimeUnit.SECONDS));
      assertTrue(first.cancel(true));
      assertFalse(firstJob.isCancelled(), "Delegate is still held before returning its future");
      assertFalse(secondJob.isCancelled());
      releaseFirstReturn.countDown();
      assertTrue(firstCancelled.await(2, TimeUnit.SECONDS));
      assertTrue(firstJob.isCancelled(), "Cancellation must reach the subsequently bound delegate");
      assertFalse(secondJob.isCancelled());
      secondJob.complete(null);
      assertNull(second.get(2, TimeUnit.SECONDS));
    } finally {
      releaseInitialization.countDown();
      releaseFirstReturn.countDown();
      facade.close();
    }
    assertEquals(1, closed.get());
    var failure =
        assertThrows(
            CompletionException.class,
            () -> facade.inspectOffline(variant, budgets).toCompletableFuture().join());
    assertEquals(
        SelectedFileException.Code.CLOSED,
        assertInstanceOf(SelectedFileException.class, failure.getCause()).code());
  }

  private static LazyBootstrapService.Resources resources(
      CompletableFuture<CandidatePlan> job, AtomicInteger closed) {
    return resources(job, closed, new CountDownLatch(0));
  }

  private static LazyBootstrapService.Resources resources(
      CompletableFuture<CandidatePlan> job, AtomicInteger closed, CountDownLatch invoked) {
    var service =
        (SelectedFileService)
            Proxy.newProxyInstance(
                SelectedFileService.class.getClassLoader(),
                new Class<?>[] {SelectedFileService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close"))
                    throw new AssertionError("facade must not close borrowed delegate directly");
                  invoked.countDown();
                  return job;
                });
    return new LazyBootstrapService.Resources() {
      public BootstrapService service() {
        throw new AssertionError("no install action");
      }

      public SelectedFileService selectedFiles() {
        return service;
      }

      public void close() {
        closed.incrementAndGet();
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(2, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
