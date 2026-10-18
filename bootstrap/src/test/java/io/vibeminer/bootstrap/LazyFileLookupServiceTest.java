package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.FixtureDiscovery;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class LazyFileLookupServiceTest {
  private static final BuildSnapshot BUILD = FixtureDiscovery.builds().getFirst();

  @Test
  void bothFacadesShareOneLazyInitializationAndOneResourceOwner() throws Exception {
    var caller = Thread.currentThread();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var closed = new AtomicInteger();
    var bootstrap = new CompletableFuture<InstallPlan>();
    var root = new CompletableFuture<RootPlan>();
    var invoked = new CountDownLatch(2);
    var owner =
        new LazyBootstrapService(
            () -> {
              calls.incrementAndGet();
              entered.countDown();
              await(release);
              assertNotSame(caller, Thread.currentThread());
              return resources(bootstrap, root, invoked, closed);
            });
    var lookup = new LazyFileLookupService(owner);
    try {
      assertEquals(0, calls.get());
      var install = owner.inspectConfiguration(BUILD).toCompletableFuture();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var planned = lookup.planRoot(BUILD, LookupBudgets.defaults()).toCompletableFuture();
      release.countDown();
      assertTrue(invoked.await(2, TimeUnit.SECONDS));
      assertEquals(1, calls.get());
      owner.close();
      lookup.close();
      assertTrue(install.isCancelled());
      assertTrue(planned.isCancelled());
      assertTrue(bootstrap.isCancelled());
      assertTrue(root.isCancelled());
      assertEquals(1, closed.get());
      var failure =
          assertThrows(
              CompletionException.class,
              () -> lookup.planRoot(BUILD, LookupBudgets.defaults()).toCompletableFuture().join());
      assertEquals(
          FileLookupException.Code.CLOSED,
          assertInstanceOf(FileLookupException.class, failure.getCause()).code());
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
      owner.close();
    }
  }

  @Test
  void lookupInitializationFailureIsTypedAndRetryUsesTheSameOwner() {
    var calls = new AtomicInteger();
    var closed = new AtomicInteger();
    var owner =
        new LazyBootstrapService(
            () -> {
              if (calls.incrementAndGet() == 1)
                throw new IllegalStateException("synthetic lock busy");
              return resources(
                  new CompletableFuture<>(),
                  CompletableFuture.completedFuture(null),
                  new CountDownLatch(0),
                  closed);
            });
    try (var lookup = new LazyFileLookupService(owner)) {
      var failure =
          assertThrows(
              CompletionException.class,
              () -> lookup.planRoot(BUILD, LookupBudgets.defaults()).toCompletableFuture().join());
      assertEquals(
          FileLookupException.Code.STORAGE_FAILURE,
          assertInstanceOf(FileLookupException.class, failure.getCause()).code());
      assertNull(lookup.planRoot(BUILD, LookupBudgets.defaults()).toCompletableFuture().join());
      assertEquals(2, calls.get());
    }
    assertEquals(1, closed.get());
  }

  @RepeatedTest(20)
  void lookupCancellationBeforeDelegateRegistrationReachesTheActualRootJob() throws Exception {
    var factoryRelease = new CountDownLatch(1);
    var actionEntered = new CountDownLatch(1);
    var delegateRelease = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var job =
        new CompletableFuture<RootPlan>() {
          @Override
          public boolean cancel(boolean interrupt) {
            var result = super.cancel(interrupt);
            if (isCancelled()) cancelled.countDown();
            return result;
          }
        };
    var owner =
        new LazyBootstrapService(
            () -> {
              await(factoryRelease);
              var base = resources(new CompletableFuture<>(), job, new CountDownLatch(0), closed);
              return new LazyBootstrapService.Resources() {
                public BootstrapService service() {
                  return base.service();
                }

                public FileLookupService lookup() {
                  actionEntered.countDown();
                  await(delegateRelease);
                  return base.lookup();
                }

                public void close() {
                  base.close();
                }
              };
            });
    try (var lookup = new LazyFileLookupService(owner)) {
      var request = lookup.planRoot(BUILD, LookupBudgets.defaults()).toCompletableFuture();
      factoryRelease.countDown();
      assertTrue(actionEntered.await(2, TimeUnit.SECONDS));
      assertTrue(request.cancel(true));
      assertFalse(job.isCancelled());
      delegateRelease.countDown();
      assertTrue(cancelled.await(2, TimeUnit.SECONDS));
      assertTrue(job.isCancelled());
    } finally {
      factoryRelease.countDown();
      delegateRelease.countDown();
      owner.close();
    }
    assertEquals(1, closed.get());
  }

  private static LazyBootstrapService.Resources resources(
      CompletableFuture<InstallPlan> install,
      CompletableFuture<RootPlan> root,
      CountDownLatch invoked,
      AtomicInteger closed) {
    return new LazyBootstrapService.Resources() {
      public BootstrapService service() {
        return new BootstrapService() {
          public CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot build) {
            invoked.countDown();
            return install;
          }

          public CompletionStage<InstallInspection> download(InstallPlan plan) {
            throw new AssertionError();
          }

          public CompletionStage<InstallInspection> reopen(BuildSnapshot build) {
            throw new AssertionError();
          }

          public void close() {}
        };
      }

      public FileLookupService lookup() {
        return new FileLookupService() {
          public CompletionStage<RootPlan> planRoot(BuildSnapshot build, LookupBudgets budgets) {
            invoked.countDown();
            return root;
          }

          public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan plan) {
            throw new AssertionError();
          }

          public CompletionStage<RootLookupResult> lookupOffline(
              BuildSnapshot build, Set<Long> ids, LookupBudgets budgets) {
            throw new AssertionError();
          }

          public void close() {}
        };
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
