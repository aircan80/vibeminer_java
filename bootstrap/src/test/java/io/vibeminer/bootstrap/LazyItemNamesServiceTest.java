package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.item.PreparedItemNames;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyItemNamesServiceTest {
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
    var item = new LazyItemNamesService(owner);
    assertEquals(0, calls.get());
    var failure =
        assertThrows(
            CompletionException.class, () -> item.prepare(null).toCompletableFuture().join());
    assertEquals(
        ItemNamesException.Code.STORAGE_UNAVAILABLE,
        assertInstanceOf(ItemNamesException.class, failure.getCause()).code());
    assertNull(item.prepare(null).toCompletableFuture().join());
    assertEquals(2, calls.get());
    item.close();
    assertEquals(0, closed.get(), "Borrowed owner must survive facade close");
    var sibling = new LazyItemNamesService(owner);
    assertNull(sibling.prepare(null).toCompletableFuture().join());
    sibling.close();
    owner.close();
    assertEquals(1, closed.get());
    var later =
        assertThrows(
            CompletionException.class, () -> item.prepare(null).toCompletableFuture().join());
    assertEquals(
        ItemNamesException.Code.CLOSED,
        assertInstanceOf(ItemNamesException.class, later.getCause()).code());
  }

  @Test
  void facadeCloseDuringDelegateRegistrationCancelsOnlyItsActualRequest() throws Exception {
    var releaseFactory = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var releaseDelegate = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var job =
        new CompletableFuture<PreparedItemNames>() {
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

                public ItemNamesService itemNames() {
                  entered.countDown();
                  await(releaseDelegate);
                  return base.itemNames();
                }

                public void close() {
                  base.close();
                }
              };
            });
    var item = new LazyItemNamesService(owner);
    try {
      var request = item.prepare(null).toCompletableFuture();
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
        (ItemNamesService)
            Proxy.newProxyInstance(
                ItemNamesService.class.getClassLoader(),
                new Class<?>[] {ItemNamesService.class},
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

                  public ItemNamesService itemNames() {
                    return delegate;
                  }

                  public void close() {
                    closedResources.incrementAndGet();
                  }
                });
    var facade = new LazyItemNamesService(owner);
    facade.prepare(null).toCompletableFuture().join();
    assertEquals(1, calls.get());
    // Freeze the owner's dispatch monitor after the facade admission check, before its action.
    var monitorField = LazyBootstrapService.class.getDeclaredField("monitor");
    monitorField.setAccessible(true);
    var monitor = monitorField.get(owner);
    var finished = new CompletableFuture<CompletionStage<PreparedItemNames>>();
    var caller =
        new Thread(
            () -> {
              try {
                finished.complete(facade.prepare(null));
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
        (ItemNamesService)
            Proxy.newProxyInstance(
                ItemNamesService.class.getClassLoader(),
                new Class<?>[] {ItemNamesService.class},
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

                  public ItemNamesService itemNames() {
                    return delegate;
                  }

                  public void close() {
                    closes.incrementAndGet();
                  }
                })) {
      var facade = new LazyItemNamesService(owner);
      facade.prepare(null).toCompletableFuture().join();
      facade.page(null, java.util.Optional.empty(), 10).toCompletableFuture().join();
      facade.lookup(null, 1).toCompletableFuture().join();
      assertEquals(java.util.List.of("prepare", "page", "lookup"), calls);
      facade.close();
      assertEquals(0, closes.get());
    }
    assertEquals(1, closes.get());
  }

  @Test
  void closedSearchCannotInitializeTheBorrowedRuntime() {
    var initialized = new AtomicInteger();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              initialized.incrementAndGet();
              throw new AssertionError("Closed search must not initialize resources");
            })) {
      var facade = new LazyItemNamesService(owner);
      facade.close();
      var failure =
          assertThrows(
              CompletionException.class,
              () ->
                  facade
                      .search(null, null, java.util.Optional.empty(), 10)
                      .toCompletableFuture()
                      .join());
      assertEquals(
          ItemNamesException.Code.CLOSED,
          assertInstanceOf(ItemNamesException.class, failure.getCause()).code());
      assertEquals(0, initialized.get());
    }
  }

  @Test
  void closeDuringSearchCreationCancelsLateSourceWithoutClosingSiblingOwner() throws Exception {
    var factoryGate = new CountDownLatch(1);
    var creating = new CountDownLatch(1);
    var returnSource = new CountDownLatch(1);
    var sourceCancelled = new CountDownLatch(1);
    var resourceCloses = new AtomicInteger();
    var source =
        new CompletableFuture<ItemNamesSearchPage>() {
          public boolean cancel(boolean interrupt) {
            boolean result = super.cancel(interrupt);
            if (isCancelled()) sourceCancelled.countDown();
            return result;
          }
        };
    var delegate =
        (ItemNamesService)
            Proxy.newProxyInstance(
                ItemNamesService.class.getClassLoader(),
                new Class<?>[] {ItemNamesService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("search")) {
                    creating.countDown();
                    await(returnSource);
                    return source;
                  }
                  if (method.getName().equals("close"))
                    throw new AssertionError("Facade cannot close borrowed delegate");
                  return CompletableFuture.completedFuture(null);
                });
    var owner =
        new LazyBootstrapService(
            () -> {
              await(factoryGate);
              return new LazyBootstrapService.Resources() {
                public BootstrapService service() {
                  throw new AssertionError();
                }

                public ItemNamesService itemNames() {
                  return delegate;
                }

                public void close() {
                  resourceCloses.incrementAndGet();
                }
              };
            });
    var facade = new LazyItemNamesService(owner);
    try {
      var request = facade.search(null, null, java.util.Optional.empty(), 10).toCompletableFuture();
      factoryGate.countDown();
      assertTrue(creating.await(2, TimeUnit.SECONDS));
      facade.close();
      assertTrue(request.isCancelled());
      assertEquals(1, sourceCancelled.getCount(), "Source future is not bound yet");
      returnSource.countDown();
      assertTrue(sourceCancelled.await(2, TimeUnit.SECONDS));
      assertEquals(0, resourceCloses.get());
      var sibling = new LazyItemNamesService(owner);
      assertNull(sibling.prepare(null).toCompletableFuture().join());
      sibling.close();
      assertEquals(0, resourceCloses.get());
    } finally {
      factoryGate.countDown();
      returnSource.countDown();
      facade.close();
      owner.close();
    }
    assertEquals(1, resourceCloses.get());
  }

  private static LazyBootstrapService.Resources resources(
      CompletableFuture<PreparedItemNames> job, AtomicInteger closed) {
    var service =
        (ItemNamesService)
            Proxy.newProxyInstance(
                ItemNamesService.class.getClassLoader(),
                new Class<?>[] {ItemNamesService.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close"))
                    throw new AssertionError("Borrowed delegate close belongs to runtime");
                  return job;
                });
    return new LazyBootstrapService.Resources() {
      public BootstrapService service() {
        throw new AssertionError("No install action");
      }

      public ItemNamesService itemNames() {
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
