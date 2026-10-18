package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.content.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyMetadataRepairServiceTest {
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
    var item = new LazyMetadataRepairService(owner);
    assertEquals(0, calls.get());
    var failure =
        assertThrows(
            CompletionException.class, () -> item.prepareRoot(null).toCompletableFuture().join());
    assertEquals(
        ContentClaimException.Code.STORAGE_UNAVAILABLE,
        assertInstanceOf(ContentClaimException.class, failure.getCause()).code());
    assertNull(item.prepareRoot(null).toCompletableFuture().join());
    assertEquals(2, calls.get());
    item.close();
    assertEquals(0, closed.get(), "Borrowed owner must survive facade close");
    var sibling = new LazyMetadataRepairService(owner);
    assertNull(sibling.prepareRoot(null).toCompletableFuture().join());
    sibling.close();
    owner.close();
    assertEquals(1, closed.get());
    var later =
        assertThrows(
            CompletionException.class, () -> item.prepareRoot(null).toCompletableFuture().join());
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
        new CompletableFuture<MetadataRepairPlan>() {
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

                public MetadataRepairService metadataRepair() {
                  entered.countDown();
                  await(releaseDelegate);
                  return base.metadataRepair();
                }

                public void close() {
                  base.close();
                }
              };
            });
    var item = new LazyMetadataRepairService(owner);
    try {
      var request = item.prepareRoot(null).toCompletableFuture();
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
        (MetadataRepairService)
            Proxy.newProxyInstance(
                MetadataRepairService.class.getClassLoader(),
                new Class<?>[] {MetadataRepairService.class},
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

                  public MetadataRepairService metadataRepair() {
                    return delegate;
                  }

                  public void close() {
                    closedResources.incrementAndGet();
                  }
                });
    var facade = new LazyMetadataRepairService(owner);
    facade.prepareRoot(null).toCompletableFuture().join();
    assertEquals(1, calls.get());
    // Freeze the owner's dispatch monitor after the facade admission check, before its action.
    var monitorField = LazyBootstrapService.class.getDeclaredField("monitor");
    monitorField.setAccessible(true);
    var monitor = monitorField.get(owner);
    var finished = new CompletableFuture<CompletionStage<MetadataRepairPlan>>();
    var caller =
        new Thread(
            () -> {
              try {
                finished.complete(facade.prepareRoot(null));
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

  @Test
  void allRepairOperationsForwardThroughTheSameBorrowedOwner() {
    var calls = new java.util.ArrayList<String>();
    var closes = new AtomicInteger();
    var delegate =
        (MetadataRepairService)
            Proxy.newProxyInstance(
                MetadataRepairService.class.getClassLoader(),
                new Class<?>[] {MetadataRepairService.class},
                (proxy, method, args) -> {
                  calls.add(method.getName());
                  return CompletableFuture.completedFuture(null);
                });
    try (var owner =
        new LazyBootstrapService(
            () ->
                new LazyBootstrapService.Resources() {
                  public BootstrapService service() {
                    throw new AssertionError();
                  }

                  public MetadataRepairService metadataRepair() {
                    return delegate;
                  }

                  public void close() {
                    closes.incrementAndGet();
                  }
                })) {
      var facade = new LazyMetadataRepairService(owner);
      facade.prepareInstall(null, null).toCompletableFuture().join();
      facade.prepareRoot(null).toCompletableFuture().join();
      facade.execute(null).toCompletableFuture().join();
      assertEquals(java.util.List.of("prepareInstall", "prepareRoot", "execute"), calls);
      facade.close();
      assertEquals(0, closes.get());
    }
    assertEquals(1, closes.get());
  }

  private static LazyBootstrapService.Resources resources(
      CompletableFuture<MetadataRepairPlan> job, AtomicInteger closed) {
    var service =
        (MetadataRepairService)
            Proxy.newProxyInstance(
                MetadataRepairService.class.getClassLoader(),
                new Class<?>[] {MetadataRepairService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close"))
                    throw new AssertionError("Borrowed delegate close belongs to runtime");
                  return job;
                });
    return new LazyBootstrapService.Resources() {
      public BootstrapService service() {
        throw new AssertionError("No install action");
      }

      public MetadataRepairService metadataRepair() {
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
