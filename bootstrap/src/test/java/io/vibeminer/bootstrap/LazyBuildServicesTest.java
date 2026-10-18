package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.BootstrapService;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.inventory.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LazyBuildServicesTest {
  static final BuildRetention.Target TARGET =
      new BuildRetention.Target(
          "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 1, "v1", "e".repeat(64));
  static final BuildRetention.Policy POLICY = new BuildRetention.Policy(10, 100, 100, 10, 2);

  record Access(
      CompletionStage<?> result, java.util.function.Supplier<?> progress, Runnable cancel) {}

  static Access call(LazyBuildServices service, int kind) {
    if (kind == 0) {
      var op = service.prepare(null);
      return new Access(op.completion(), op::progress, op::requestCancellation);
    }
    if (kind == 1) {
      var op = service.read((BuildOverview.Request) null);
      return new Access(op.completion(), op::progress, op::requestCancellation);
    }
    var op = service.read(TARGET);
    return new Access(op.completion(), () -> null, op::requestCancellation);
  }

  @Test
  void constructionDoesNotInitializeAndAllPortsShareOneExistingRuntime() throws Exception {
    var calls = new AtomicInteger();
    var resources = new Resources();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              calls.incrementAndGet();
              return resources;
            })) {
      var service = new LazyBuildServices(owner);
      assertEquals(0, calls.get());
      for (int i = 0; i < 3; i++) {
        var op = call(service, i);
        assertTrue(resources.gates[i].subscribed.await(2, TimeUnit.SECONDS));
        resources.gates[i].result.completeExceptionally(new CancellationException());
        assertThrows(CompletionException.class, () -> op.result.toCompletableFuture().join());
      }
      assertEquals(1, calls.get());
      assertEquals(0, resources.closed.get());
      service.close();
      assertEquals(0, resources.closed.get());
    }
  }

  @Test
  void everyPortCancellationBeforeInitializationSuppressesActualAction() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var resources = new Resources();
      try (var owner =
          new LazyBootstrapService(
              () -> {
                entered.countDown();
                await(release);
                return resources;
              })) {
        var service = new LazyBuildServices(owner);
        var op = call(service, kind);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        op.cancel.run();
        assertFalse(op.result.toCompletableFuture().isDone());
        release.countDown();
        assertThrows(
            CompletionException.class,
            () -> op.result.toCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join());
        assertEquals(0, resources.gates[kind].calls.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void initialProgressIsTruthfulAndLaterProgressComesFromActualOperation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var resources = new Resources();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              entered.countDown();
              await(release);
              return resources;
            })) {
      var service = new LazyBuildServices(owner);
      var prep = call(service, 0);
      var view = call(service, 1);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(BuildInventoryPreparationProgress.initial(), prep.progress.get());
      assertEquals(BuildOverview.Progress.initial(), view.progress.get());
      release.countDown();
      assertTrue(resources.gates[0].subscribed.await(2, TimeUnit.SECONDS));
      assertTrue(resources.gates[1].subscribed.await(2, TimeUnit.SECONDS));
      resources.prepProgress =
          new BuildInventoryPreparationProgress(
              BuildInventoryPreparationProgress.Stage.PREPARING_INVENTORY,
              OptionalLong.empty(),
              OptionalLong.empty(),
              Optional.empty());
      assertEquals(resources.prepProgress, prep.progress.get());
      assertEquals(resources.overviewProgress, view.progress.get());
      service.close();
      assertFalse(prep.result.toCompletableFuture().isDone());
      resources.failAll();
    } finally {
      release.countDown();
    }
  }

  @Test
  void lateBindingForEveryPortForwardsCancellationWithoutPrematureRetirement() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      var resources = new Resources();
      var initialize = new CountDownLatch(1);
      var gate = resources.gates[kind];
      gate.bindRelease = new CountDownLatch(1);
      try (var owner =
          new LazyBootstrapService(
              () -> {
                await(initialize);
                return resources;
              })) {
        var service = new LazyBuildServices(owner);
        var op = call(service, kind);
        initialize.countDown();
        assertTrue(gate.entered.await(2, TimeUnit.SECONDS));
        op.cancel.run();
        gate.bindRelease.countDown();
        assertTrue(gate.cancelled.await(2, TimeUnit.SECONDS));
        assertFalse(op.result.toCompletableFuture().isDone());
        gate.result.completeExceptionally(new CancellationException());
        assertThrows(
            CompletionException.class,
            () -> op.result.toCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join());
      } finally {
        initialize.countDown();
        gate.bindRelease.countDown();
      }
    }
  }

  @Test
  void derivedFutureCancellationCannotEraseCommittedModeOrCancelActualAction() throws Exception {
    var resources = new Resources();
    try (var owner = new LazyBootstrapService(() -> resources)) {
      var service = new LazyBuildServices(owner);
      var op = service.setMode(TARGET, 0, BuildRetention.Mode.DOWNLOAD_ALL, POLICY);
      var gate = resources.gates[2];
      assertTrue(gate.subscribed.await(2, TimeUnit.SECONDS));
      op.completion().toCompletableFuture().cancel(true);
      assertEquals(0, gate.cancels.get());
      op.requestCancellation();
      assertFalse(op.completion().toCompletableFuture().isDone());
      var view = new BuildRetentionModeService.View(TARGET, Optional.empty(), Optional.empty());
      gate.result.complete(view);
      assertSame(view, op.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void facadeCloseDoesNotCloseOwnerOtherFacadesOrActualService() throws Exception {
    var resources = new Resources();
    try (var owner = new LazyBootstrapService(() -> resources)) {
      var first = new LazyBuildServices(owner);
      var second = new LazyBuildServices(owner);
      var op = call(first, 0);
      assertTrue(resources.gates[0].subscribed.await(2, TimeUnit.SECONDS));
      first.close();
      assertEquals(0, resources.closed.get());
      assertFalse(op.result.toCompletableFuture().isDone());
      var other = call(second, 1);
      assertTrue(resources.gates[1].subscribed.await(2, TimeUnit.SECONDS));
      assertEquals(0, resources.gates[1].cancels.get());
      assertThrows(
          CompletionException.class, () -> call(first, 2).result.toCompletableFuture().join());
      resources.failAll();
    }
  }

  @Test
  void completionCancellationCallbacksCanReenterFromAnotherThread() throws Exception {
    for (int kind = 0; kind < 3; kind++) {
      var resources = new Resources();
      var gate = resources.gates[kind];
      var callback = new CompletableFuture<Void>();
      try (var owner = new LazyBootstrapService(() -> resources);
          var executor = Executors.newSingleThreadExecutor()) {
        var op = call(new LazyBuildServices(owner), kind);
        assertTrue(gate.subscribed.await(2, TimeUnit.SECONDS));
        op.result.whenComplete(
            (v, f) -> {
              try {
                executor.submit(op.cancel).get(1, TimeUnit.SECONDS);
                callback.complete(null);
              } catch (Exception failed) {
                callback.completeExceptionally(failed);
              }
            });
        gate.onCancel = () -> gate.result.completeExceptionally(new CancellationException());
        op.cancel.run();
        callback.get(2, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void defaultResourcesAndClosedOwnerRefuseWithoutDiskOrInventedSupport() throws Exception {
    var owner =
        new LazyBootstrapService(
            () ->
                new LazyBootstrapService.Resources() {
                  public BootstrapService service() {
                    return null;
                  }

                  public void close() {}
                });
    var service = new LazyBuildServices(owner);
    try {
      for (int i = 0; i < 3; i++)
        assertThrows(
            CompletionException.class,
            () ->
                call(service, 0)
                    .result
                    .toCompletableFuture()
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join());
    } finally {
      owner.close();
    }
    for (int i = 0; i < 3; i++) {
      var kind = i;
      assertThrows(
          CompletionException.class,
          () ->
              call(service, kind)
                  .result
                  .toCompletableFuture()
                  .orTimeout(2, TimeUnit.SECONDS)
                  .join());
    }
  }

  @Test
  void fatalGetterAdmissionFailureCompletesAndDoesNotPoisonSharedRuntime() throws Exception {
    var fatal = new AssertionError("unsupported adapter failed before actual operation");
    var resources =
        new Resources() {
          @Override
          public BuildInventoryPreparationService inventoryPreparation() {
            throw fatal;
          }
        };
    try (var owner = new LazyBootstrapService(() -> resources)) {
      var facade = new LazyBuildServices(owner);
      var failed = facade.prepare(null);
      var error =
          assertThrows(
              CompletionException.class,
              () ->
                  failed.completion().toCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join());
      assertSame(fatal, error.getCause());
      var usable = facade.resume(TARGET);
      assertTrue(resources.gates[2].subscribed.await(2, TimeUnit.SECONDS));
      var saved = new BuildRetentionModeService.View(TARGET, Optional.empty(), Optional.empty());
      resources.gates[2].result.complete(saved);
      assertSame(saved, usable.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void allSharedSlotsRetireBeforeSuccessAndFailureCallbacksAdmitAnotherOperation()
      throws Exception {
    for (boolean failed : List.of(false, true)) {
      var sources = new CopyOnWriteArrayList<CompletableFuture<BuildRetentionModeService.View>>();
      var calls = new AtomicInteger();
      var delegate =
          new BuildRetentionModeService() {
            public Operation read(BuildRetention.Target target) {
              calls.incrementAndGet();
              var result = new CompletableFuture<View>();
              sources.add(result);
              return new Operation() {
                public CompletionStage<View> completion() {
                  return result.minimalCompletionStage();
                }

                public void requestCancellation() {}
              };
            }

            public Operation setMode(
                BuildRetention.Target t, long g, BuildRetention.Mode m, BuildRetention.Policy p) {
              throw new AssertionError();
            }

            public Operation resume(BuildRetention.Target t) {
              throw new AssertionError();
            }

            public void close() {}
          };
      try (var owner =
          new LazyBootstrapService(
              () ->
                  new Resources() {
                    public BuildRetentionModeService retentionMode() {
                      return delegate;
                    }
                  })) {
        owner
            .dispatchResources(r -> CompletableFuture.completedFuture(null), f -> f)
            .toCompletableFuture()
            .join();
        var facade = new LazyBuildServices(owner);
        var operations = new ArrayList<BuildRetentionModeService.Operation>();
        for (int i = 0; i < 64; i++) operations.add(facade.read(TARGET));
        assertEquals(64, calls.get());
        operations.getFirst().completion().whenComplete((v, f) -> facade.read(TARGET));
        if (failed) sources.getFirst().completeExceptionally(new CancellationException());
        else
          sources
              .getFirst()
              .complete(
                  new BuildRetentionModeService.View(TARGET, Optional.empty(), Optional.empty()));
        assertEquals(65, calls.get(), "Actual terminal callback must see shared admission retired");
        sources.forEach(source -> source.completeExceptionally(new CancellationException()));
        facade.close();
      }
    }
  }

  @Test
  void failedGetterRetiresSharedSlotBeforePublicFailureCallback() throws Exception {
    var factory = new CountDownLatch(1);
    var resources =
        new Resources() {
          public BuildInventoryPreparationService inventoryPreparation() {
            throw new AssertionError("before actual source");
          }
        };
    try (var owner =
        new LazyBootstrapService(
            () -> {
              await(factory);
              return resources;
            })) {
      var facade = new LazyBuildServices(owner);
      var failed = facade.prepare(null);
      for (int i = 0; i < 63; i++) facade.read(TARGET);
      var callback = new CompletableFuture<Void>();
      failed
          .completion()
          .whenComplete(
              (v, f) -> {
                facade.read(TARGET);
                callback.complete(null);
              });
      factory.countDown();
      callback.get(2, TimeUnit.SECONDS);
      assertEquals(64, resources.gates[2].calls.get());
      resources.failAll();
      facade.close();
    } finally {
      factory.countDown();
    }
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  static final class Gate {
    final CompletableFuture<Object> result =
        new CompletableFuture<>() {
          @Override
          public CompletableFuture<Object> whenComplete(
              java.util.function.BiConsumer<? super Object, ? super Throwable> action) {
            var registered = super.whenComplete(action);
            subscribed.countDown();
            return registered;
          }
        };
    final CountDownLatch entered = new CountDownLatch(1),
        subscribed = new CountDownLatch(1),
        cancelled = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger(), cancels = new AtomicInteger();
    volatile CountDownLatch bindRelease = new CountDownLatch(0);
    volatile Runnable onCancel = () -> {};

    void enter() {
      calls.incrementAndGet();
      entered.countDown();
      await(bindRelease);
    }

    void cancel() {
      cancels.incrementAndGet();
      cancelled.countDown();
      onCancel.run();
    }

    @SuppressWarnings("unchecked")
    <T> CompletionStage<T> completion() {
      return (CompletionStage<T>) (CompletionStage<?>) result;
    }
  }

  static class Resources implements LazyBootstrapService.Resources {
    final Gate[] gates = {new Gate(), new Gate(), new Gate()};
    final AtomicInteger closed = new AtomicInteger();
    volatile BuildInventoryPreparationProgress prepProgress =
        BuildInventoryPreparationProgress.initial();
    volatile BuildOverview.Progress overviewProgress = BuildOverview.Progress.initial();

    public BootstrapService service() {
      return null;
    }

    public BuildInventoryPreparationService inventoryPreparation() {
      return new BuildInventoryPreparationService() {
        public Operation prepare(
            io.vibeminer.application.buildopening.BuildMetadataAdmission admission) {
          var g = gates[0];
          g.enter();
          return new Operation() {
            public CompletionStage<BuildInventoryPreparationResult> completion() {
              return g.completion();
            }

            public BuildInventoryPreparationProgress progress() {
              return prepProgress;
            }

            public void requestCancellation() {
              g.cancel();
            }
          };
        }

        public void close() {
          throw new AssertionError("Borrowed");
        }
      };
    }

    public BuildOverviewService buildOverview() {
      return new BuildOverviewService() {
        public Operation read(BuildOverview.Request r) {
          var g = gates[1];
          g.enter();
          return new Operation() {
            public CompletionStage<BuildOverview.Snapshot> completion() {
              return g.completion();
            }

            public BuildOverview.Progress progress() {
              return overviewProgress;
            }

            public void requestCancellation() {
              g.cancel();
            }
          };
        }

        public void close() {
          throw new AssertionError("Borrowed");
        }
      };
    }

    public BuildRetentionModeService retentionMode() {
      return new BuildRetentionModeService() {
        Operation op() {
          var g = gates[2];
          g.enter();
          return new Operation() {
            public CompletionStage<View> completion() {
              return g.completion();
            }

            public void requestCancellation() {
              g.cancel();
            }
          };
        }

        public Operation read(BuildRetention.Target t) {
          return op();
        }

        public Operation setMode(
            BuildRetention.Target t, long n, BuildRetention.Mode m, BuildRetention.Policy p) {
          return op();
        }

        public Operation resume(BuildRetention.Target t) {
          return op();
        }

        public void close() {
          throw new AssertionError("Borrowed");
        }
      };
    }

    void failAll() {
      for (var g : gates) g.result.completeExceptionally(new CancellationException());
    }

    public void close() {
      closed.incrementAndGet();
    }
  }
}
