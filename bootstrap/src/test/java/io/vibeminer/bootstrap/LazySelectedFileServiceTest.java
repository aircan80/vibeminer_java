package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.selectedfile.*;
import java.lang.reflect.Proxy;
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
