package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.item.*;
import io.vibeminer.domain.item.PreparedItemProjection;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyItemProjectionServiceTest {
  @Test
  void untouchedFacadeIsLazyAndInitializationFailureRetriesWithTypedOutcome() {
    var calls = new AtomicInteger();
    var closed = new AtomicInteger();
    var owner =
        new LazyBootstrapService(
            () -> {
              if (calls.incrementAndGet() == 1) throw new IllegalStateException("fixture failure");
              return resources(CompletableFuture.completedFuture(null), closed);
            });
    var item = new LazyItemProjectionService(owner);
    assertEquals(0, calls.get());
    var failure =
        assertThrows(
            CompletionException.class, () -> item.prepare(null).toCompletableFuture().join());
    assertEquals(
        ItemProjectionException.Code.STORAGE_UNAVAILABLE,
        assertInstanceOf(ItemProjectionException.class, failure.getCause()).code());
    assertNull(item.prepare(null).toCompletableFuture().join());
    assertEquals(2, calls.get());
    item.close();
    owner.close();
    assertEquals(1, closed.get());
    var later =
        assertThrows(
            CompletionException.class, () -> item.prepare(null).toCompletableFuture().join());
    assertEquals(
        ItemProjectionException.Code.CLOSED,
        assertInstanceOf(ItemProjectionException.class, later.getCause()).code());
  }

  @Test
  void cancellationDuringDelegateRegistrationReachesActualItemRequest() throws Exception {
    var releaseFactory = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var releaseDelegate = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var job =
        new CompletableFuture<PreparedItemProjection>() {
          public boolean cancel(boolean interrupt) {
            boolean result = super.cancel(interrupt);
            if (isCancelled()) cancelled.countDown();
            return result;
          }
        };
    var owner =
        new LazyBootstrapService(
            () -> {
              await(releaseFactory);
              var base = resources(job, closed);
              return new LazyBootstrapService.Resources() {
                public BootstrapService service() {
                  return base.service();
                }

                public ItemProjectionService itemProjection() {
                  entered.countDown();
                  await(releaseDelegate);
                  return base.itemProjection();
                }

                public void close() {
                  base.close();
                }
              };
            });
    var item = new LazyItemProjectionService(owner);
    try {
      var request = item.prepare(null).toCompletableFuture();
      releaseFactory.countDown();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTrue(request.cancel(true));
      releaseDelegate.countDown();
      assertTrue(cancelled.await(2, TimeUnit.SECONDS));
    } finally {
      releaseFactory.countDown();
      releaseDelegate.countDown();
      item.close();
    }
    assertEquals(1, closed.get());
  }

  private static LazyBootstrapService.Resources resources(
      CompletableFuture<PreparedItemProjection> job, AtomicInteger closed) {
    var service =
        (ItemProjectionService)
            Proxy.newProxyInstance(
                ItemProjectionService.class.getClassLoader(),
                new Class<?>[] {ItemProjectionService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close"))
                    throw new AssertionError("Borrowed delegate close belongs to runtime");
                  return job;
                });
    return new LazyBootstrapService.Resources() {
      public BootstrapService service() {
        throw new AssertionError("No install action");
      }

      public ItemProjectionService itemProjection() {
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
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
