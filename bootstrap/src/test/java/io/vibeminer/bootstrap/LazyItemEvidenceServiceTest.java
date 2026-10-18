package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.itemexport.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LazyItemEvidenceServiceTest {
  static final ItemEvidenceReceipt RECEIPT =
      new ItemEvidenceReceipt(10, "a".repeat(64), Instant.EPOCH);

  @Test
  void closedFacadeCannotInitializeCaptureOrSave() {
    var factories = new AtomicInteger();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              factories.incrementAndGet();
              throw new AssertionError();
            })) {
      var facade = new LazyItemEvidenceService(owner);
      facade.close();
      assertCode(ItemEvidenceException.Code.CLOSED, facade.capture(null));
      assertCode(
          ItemEvidenceException.Code.CLOSED,
          facade.save(null, "/fixture/output.json").completion());
      assertEquals(0, factories.get());
    }
  }

  @Test
  void closeDuringReentrantSaveCreationCancelsLateHandleButPreservesCommittedReceipt() {
    var facadeRef = new AtomicReference<LazyItemEvidenceService>();
    var cancelled = new AtomicInteger();
    var closes = new AtomicInteger();
    var terminal = new CompletableFuture<ItemEvidenceReceipt>();
    var delegate =
        new Stub() {
          public SaveOperation save(ItemEvidenceSnapshot snapshot, String path) {
            facadeRef.get().close();
            return operation(terminal, cancelled);
          }
        };
    try (var owner = owner(delegate, closes)) {
      var facade = new LazyItemEvidenceService(owner);
      facadeRef.set(facade);
      facade.capture(null).toCompletableFuture().join();
      var save = facade.save(null, "/fixture/output.json");
      assertEquals(1, cancelled.get());
      assertFalse(save.completion().toCompletableFuture().isDone());
      assertEquals(0, closes.get());
      terminal.complete(RECEIPT);
      assertEquals(RECEIPT, save.completion().toCompletableFuture().join());
    }
  }

  @Test
  void derivedFutureCancellationAndOwnerCloseCannotEraseBoundCommit() throws Exception {
    var bound = new CountDownLatch(1);
    var cancelled = new AtomicInteger();
    var closes = new AtomicInteger();
    var terminal = new CompletableFuture<ItemEvidenceReceipt>();
    var delegate =
        new Stub() {
          public SaveOperation save(ItemEvidenceSnapshot snapshot, String path) {
            bound.countDown();
            return operation(terminal, cancelled);
          }
        };
    var owner = owner(delegate, closes);
    var facade = new LazyItemEvidenceService(owner);
    facade.capture(null).toCompletableFuture().join();
    try {
      var save = facade.save(null, "/fixture/output.json");
      assertTrue(bound.await(2, TimeUnit.SECONDS));
      var derived = save.completion().toCompletableFuture();
      derived.cancel(true);
      assertEquals(0, cancelled.get());
      facade.close();
      assertEquals(1, cancelled.get());
      assertFalse(save.completion().toCompletableFuture().isDone());
      owner.close();
      terminal.complete(RECEIPT);
      assertEquals(RECEIPT, save.completion().toCompletableFuture().join());
    } finally {
      facade.close();
      owner.close();
    }
  }

  @Test
  void captureInitializationRetriesAndFacadeCloseCancelsOnlyItsOwnSource() throws Exception {
    var factories = new AtomicInteger();
    var closes = new AtomicInteger();
    var sourceEntered = new CountDownLatch(1);
    var sourceCancelled = new CountDownLatch(1);
    var source =
        new CompletableFuture<ItemEvidenceSnapshot>() {
          public boolean cancel(boolean interrupt) {
            boolean result = super.cancel(interrupt);
            if (isCancelled()) sourceCancelled.countDown();
            return result;
          }
        };
    var delegate =
        new Stub() {
          public CompletionStage<ItemEvidenceSnapshot> capture(ItemEvidenceSelection selection) {
            sourceEntered.countDown();
            return source;
          }

          public SaveOperation save(ItemEvidenceSnapshot snapshot, String path) {
            throw new AssertionError();
          }
        };
    try (var owner =
        new LazyBootstrapService(
            () -> {
              if (factories.incrementAndGet() == 1)
                throw new IllegalStateException("Initialization failure");
              return new LazyBootstrapService.Resources() {
                public BootstrapService service() {
                  throw new AssertionError();
                }

                public ItemEvidenceService itemEvidence() {
                  return delegate;
                }

                public void close() {
                  closes.incrementAndGet();
                }
              };
            })) {
      var facade = new LazyItemEvidenceService(owner);
      assertCode(ItemEvidenceException.Code.STORAGE_UNAVAILABLE, facade.capture(null));
      var request = facade.capture(null).toCompletableFuture();
      assertTrue(sourceEntered.await(2, TimeUnit.SECONDS));
      facade.close();
      assertTrue(request.isCancelled());
      assertTrue(sourceCancelled.await(2, TimeUnit.SECONDS));
      assertEquals(2, factories.get());
      assertEquals(0, closes.get());
    }
    assertEquals(1, closes.get());
  }

  @Test
  void ownerCloseWhileSaveHandleIsStillBeingCreatedCannotLoseItsLaterCommit() throws Exception {
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var factory = new CountDownLatch(1);
    var terminal = new CompletableFuture<ItemEvidenceReceipt>();
    var cancelled = new AtomicInteger();
    var closes = new AtomicInteger();
    var delegate =
        new Stub() {
          public SaveOperation save(ItemEvidenceSnapshot snapshot, String path) {
            entered.countDown();
            ItemEvidenceRuntimeLifecycleTest.hold(release, interrupted);
            return operation(terminal, cancelled);
          }
        };
    var owner =
        new LazyBootstrapService(
            () -> {
              try {
                assertTrue(factory.await(2, TimeUnit.SECONDS));
              } catch (InterruptedException failed) {
                throw new AssertionError(failed);
              }
              return new LazyBootstrapService.Resources() {
                public BootstrapService service() {
                  throw new AssertionError();
                }

                public ItemEvidenceService itemEvidence() {
                  return delegate;
                }

                public void close() {
                  closes.incrementAndGet();
                }
              };
            });
    var facade = new LazyItemEvidenceService(owner);
    CompletableFuture<Void> closing = null;
    try {
      var save = facade.save(null, "/fixture/output.json");
      factory.countDown();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      closing = CompletableFuture.runAsync(owner::close);
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertFalse(save.completion().toCompletableFuture().isDone());
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      terminal.complete(RECEIPT);
      assertEquals(RECEIPT, save.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
      assertEquals(1, cancelled.get());
    } finally {
      factory.countDown();
      release.countDown();
      facade.close();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      owner.close();
    }
    assertEquals(1, closes.get());
  }

  @Test
  void failureCompletionCanReenterCancellationFromAnotherThreadWithoutHoldingSaveMonitor()
      throws Exception {
    for (boolean factoryFailure : new boolean[] {false, true}) {
      var release = new CountDownLatch(1);
      var callback = new CompletableFuture<Void>();
      var owner =
          new LazyBootstrapService(
              () -> {
                try {
                  assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failed) {
                  throw new AssertionError(failed);
                }
                if (factoryFailure)
                  throw new IllegalStateException("Factory failure before action");
                return new LazyBootstrapService.Resources() {
                  public BootstrapService service() {
                    throw new AssertionError();
                  }

                  public ItemEvidenceService itemEvidence() {
                    return new Stub() {
                      public SaveOperation save(ItemEvidenceSnapshot snapshot, String path) {
                        throw new IllegalStateException("Action failed before handle binding");
                      }
                    };
                  }

                  public void close() {}
                };
              });
      var facade = new LazyItemEvidenceService(owner);
      try {
        var save = facade.save(null, "/fixture/output.json");
        save.completion()
            .whenComplete(
                (receipt, failure) -> {
                  try {
                    assertNotNull(failure);
                    CompletableFuture.runAsync(save::requestCancellation).get(2, TimeUnit.SECONDS);
                    callback.complete(null);
                  } catch (Throwable blocked) {
                    callback.completeExceptionally(blocked);
                  }
                });
        release.countDown();
        callback.get(3, TimeUnit.SECONDS);
      } finally {
        release.countDown();
        facade.close();
        owner.close();
      }
    }
  }

  private abstract static class Stub implements ItemEvidenceService {
    public CompletionStage<ItemEvidenceSnapshot> capture(ItemEvidenceSelection selection) {
      return CompletableFuture.completedFuture(null);
    }

    public void close() {
      throw new AssertionError("Borrowed delegate lifetime belongs to runtime");
    }
  }

  static ItemEvidenceService.SaveOperation operation(
      CompletableFuture<ItemEvidenceReceipt> terminal, AtomicInteger cancelled) {
    return new ItemEvidenceService.SaveOperation() {
      public CompletionStage<ItemEvidenceReceipt> completion() {
        return terminal.minimalCompletionStage();
      }

      public void requestCancellation() {
        cancelled.incrementAndGet();
      }
    };
  }

  static LazyBootstrapService owner(ItemEvidenceService service, AtomicInteger closes) {
    return new LazyBootstrapService(
        () ->
            new LazyBootstrapService.Resources() {
              public BootstrapService service() {
                throw new AssertionError();
              }

              public ItemEvidenceService itemEvidence() {
                return service;
              }

              public void close() {
                closes.incrementAndGet();
              }
            });
  }

  static void assertCode(ItemEvidenceException.Code code, CompletionStage<?> future) {
    var failure =
        assertThrows(CompletionException.class, () -> future.toCompletableFuture().join());
    assertEquals(code, assertInstanceOf(ItemEvidenceException.class, failure.getCause()).code());
  }
}
