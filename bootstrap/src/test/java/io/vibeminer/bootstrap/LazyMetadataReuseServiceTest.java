package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.content.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyMetadataReuseServiceTest {
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
    var item = new LazyMetadataReuseService(owner);
    assertEquals(0, calls.get());
    var failure =
        assertThrows(
            CompletionException.class,
            () -> item.planRetained(null, null, null).toCompletableFuture().join());
    assertEquals(
        ContentClaimException.Code.STORAGE_UNAVAILABLE,
        assertInstanceOf(ContentClaimException.class, failure.getCause()).code());
    assertNull(item.planRetained(null, null, null).toCompletableFuture().join());
    assertEquals(2, calls.get());
    item.close();
    assertEquals(0, closed.get(), "Borrowed owner must survive facade close");
    var sibling = new LazyMetadataReuseService(owner);
    assertNull(sibling.planRetained(null, null, null).toCompletableFuture().join());
    sibling.close();
    owner.close();
    assertEquals(1, closed.get());
    var later =
        assertThrows(
            CompletionException.class,
            () -> item.planRetained(null, null, null).toCompletableFuture().join());
    assertEquals(
        ContentClaimException.Code.INVALID_STATE,
        assertInstanceOf(ContentClaimException.class, later.getCause()).code());
  }

  @Test
  void facadeCloseDuringDelegateRegistrationCancelsOnlyItsActualRequest() throws Exception {
    var releaseFactory = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var releaseDelegate = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var job =
        new CompletableFuture<MetadataReusePlan>() {
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

                public MetadataReuseService metadataReuse() {
                  entered.countDown();
                  await(releaseDelegate);
                  return base.metadataReuse();
                }

                public void close() {
                  base.close();
                }
              };
            });
    var item = new LazyMetadataReuseService(owner);
    try {
      var request = item.planRetained(null, null, null).toCompletableFuture();
      releaseFactory.countDown();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      item.close();
      assertTrue(request.isCancelled());
      releaseDelegate.countDown();
      assertTrue(cancelled.await(2, TimeUnit.SECONDS));
    } finally {
      releaseFactory.countDown();
      releaseDelegate.countDown();
      item.close();
      owner.close();
    }
    assertEquals(1, closed.get());
  }

  @Test
  void closeBeforeOwnerDispatchInvokesActionCannotStartDelegate() throws Exception {
    var calls = new AtomicInteger();
    var closedResources = new AtomicInteger();
    var delegate =
        (MetadataReuseService)
            Proxy.newProxyInstance(
                MetadataReuseService.class.getClassLoader(),
                new Class<?>[] {MetadataReuseService.class},
                (proxy, method, args) -> {
                  calls.incrementAndGet();
                  return CompletableFuture.completedFuture(null);
                });
    var owner =
        new LazyBootstrapService(
            () ->
                new LazyBootstrapService.Resources() {
                  public BootstrapService service() {
                    throw new AssertionError();
                  }

                  public MetadataReuseService metadataReuse() {
                    return delegate;
                  }

                  public void close() {
                    closedResources.incrementAndGet();
                  }
                });
    var facade = new LazyMetadataReuseService(owner);
    facade.planRetained(null, null, null).toCompletableFuture().join();
    assertEquals(1, calls.get());
    // Freeze the owner's dispatch monitor after the facade admission check, before its action.
    var monitorField = LazyBootstrapService.class.getDeclaredField("monitor");
    monitorField.setAccessible(true);
    var monitor = monitorField.get(owner);
    var finished = new CompletableFuture<CompletionStage<MetadataReusePlan>>();
    var caller =
        new Thread(
            () -> {
              try {
                finished.complete(facade.planRetained(null, null, null));
              } catch (Throwable failure) {
                finished.completeExceptionally(failure);
              }
            });
    try {
      synchronized (monitor) {
        caller.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (caller.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
          Thread.yield();
        assertEquals(Thread.State.BLOCKED, caller.getState());
        facade.close();
      }
      var request = finished.get(2, TimeUnit.SECONDS).toCompletableFuture();
      assertTrue(request.isDone());
      assertEquals(1, calls.get(), "Closed facade must not invoke its late owner action");
      assertEquals(0, closedResources.get());
    } finally {
      facade.close();
      owner.close();
      caller.join(2000);
    }
  }

  private static LazyBootstrapService.Resources resources(
      CompletableFuture<MetadataReusePlan> job, AtomicInteger closed) {
    var service =
        (MetadataReuseService)
            Proxy.newProxyInstance(
                MetadataReuseService.class.getClassLoader(),
                new Class<?>[] {MetadataReuseService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close"))
                    throw new AssertionError("Borrowed delegate close belongs to runtime");
                  return job;
                });
    return new LazyBootstrapService.Resources() {
      public BootstrapService service() {
        throw new AssertionError("No install action");
      }

      public MetadataReuseService metadataReuse() {
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
