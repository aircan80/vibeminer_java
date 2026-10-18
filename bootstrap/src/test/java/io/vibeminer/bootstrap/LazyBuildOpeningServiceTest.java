package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LazyBuildOpeningServiceTest {
  @Test
  void constructionIsLazyAndOperationsShareTheExistingInitialization() throws Exception {
    var calls = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var fake = new Fake();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              calls.incrementAndGet();
              entered.countDown();
              await(release);
              return resources(fake);
            })) {
      var facade = new LazyBuildOpeningService(owner);
      assertEquals(0, calls.get());
      var one = facade.open(reference(), "enUS");
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var two = facade.open(reference(), "enUS");
      assertEquals(BuildOpenProgress.initial(), one.progress());
      release.countDown();
      assertTrue(fake.bound.await(2, TimeUnit.SECONDS));
      fake.second.await(2, TimeUnit.SECONDS);
      assertEquals(1, calls.get());
      assertEquals(2, fake.opens.get());
      facade.close();
      assertFalse(one.completion().toCompletableFuture().isDone());
      fake.result.completeExceptionally(new CancellationException());
      assertThrows(CompletionException.class, () -> two.completion().toCompletableFuture().join());
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancellationBeforeInitializationSuppressesRealOpen() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var fake = new Fake();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              entered.countDown();
              await(release);
              return resources(fake);
            })) {
      var facade = new LazyBuildOpeningService(owner);
      var op = facade.open(reference(), "enUS");
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      op.requestCancellation();
      release.countDown();
      assertThrows(
          CompletionException.class,
          () -> op.completion().toCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join());
      assertEquals(0, fake.opens.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancellationDuringBindingForwardsOnlyWhenDelegateExistsAndCommitWins() throws Exception {
    var fake = new Fake();
    fake.releaseBind = new CountDownLatch(1);
    try (var owner = new LazyBootstrapService(() -> resources(fake))) {
      var facade = new LazyBuildOpeningService(owner);
      var ref = reference();
      var op = facade.open(ref, "enUS");
      assertTrue(fake.bound.await(2, TimeUnit.SECONDS));
      op.requestCancellation();
      assertFalse(op.completion().toCompletableFuture().isDone());
      fake.releaseBind.countDown();
      assertTrue(fake.cancelled.await(2, TimeUnit.SECONDS));
      assertFalse(op.completion().toCompletableFuture().isDone());
      var expected = admission(ref);
      fake.result.complete(expected);
      assertSame(expected, op.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
    } finally {
      fake.releaseBind.countDown();
    }
  }

  @Test
  void derivedFutureCannotCompleteOrCancelTheSourceAndProgressDelegates() throws Exception {
    var fake = new Fake();
    try (var owner = new LazyBootstrapService(() -> resources(fake))) {
      var facade = new LazyBuildOpeningService(owner);
      var ref = reference();
      var op = facade.open(ref, "enUS");
      assertTrue(fake.subscribed.await(2, TimeUnit.SECONDS));
      var derived = op.completion().toCompletableFuture();
      derived.cancel(true);
      assertFalse(fake.result.isDone());
      assertEquals(0, fake.cancels.get());
      fake.progress =
          new BuildOpenProgress(
              BuildOpenProgress.Stage.VERIFYING_RETAINED,
              OptionalLong.empty(),
              OptionalLong.empty(),
              0,
              OptionalInt.empty());
      assertEquals(fake.progress, op.progress());
      var expected = admission(ref);
      fake.result.complete(expected);
      assertSame(expected, op.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void facadeCloseIsLocalAndParentClosedIsTyped() throws Exception {
    var fake = new Fake();
    var resourcesClosed = new AtomicInteger();
    var owner =
        new LazyBootstrapService(
            () ->
                new LazyBootstrapService.Resources() {
                  public BootstrapService service() {
                    return null;
                  }

                  public BuildOpeningService buildOpening() {
                    return fake;
                  }

                  public void close() {
                    resourcesClosed.incrementAndGet();
                  }
                });
    try {
      var first = new LazyBuildOpeningService(owner);
      var second = new LazyBuildOpeningService(owner);
      var op = first.open(reference(), "enUS");
      assertTrue(fake.bound.await(2, TimeUnit.SECONDS));
      first.close();
      assertEquals(0, resourcesClosed.get());
      assertEquals(0, fake.closes.get());
      assertFalse(op.completion().toCompletableFuture().isDone());
      assertCode(BuildOpeningException.Code.CLOSED, first.open(reference(), "enUS"));
      second.open(reference(), "enUS");
      assertEquals(2, fake.opens.get());
      fake.result.completeExceptionally(new CancellationException());
      owner.close();
      assertCode(BuildOpeningException.Code.CLOSED, second.open(reference(), "enUS"));
    } finally {
      owner.close();
    }
  }

  @Test
  void parentCloseRequestsCancellationWithoutFabricatingSourceCompletion() throws Exception {
    var fake = new Fake();
    var owner = new LazyBootstrapService(() -> resources(fake));
    try {
      var facade = new LazyBuildOpeningService(owner);
      var ref = reference();
      var op = facade.open(ref, "enUS");
      assertTrue(fake.subscribed.await(2, TimeUnit.SECONDS));
      owner.close();
      assertTrue(fake.cancelled.await(2, TimeUnit.SECONDS));
      assertFalse(op.completion().toCompletableFuture().isDone());
      var expected = admission(ref);
      fake.result.complete(expected);
      assertSame(expected, op.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
    } finally {
      owner.close();
    }
  }

  @Test
  void pendingRequestsUseSharedOwnerCapacityUntilTheyActuallyFinish() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var fake = new Fake();
    try (var owner =
        new LazyBootstrapService(
            () -> {
              entered.countDown();
              await(release);
              return resources(fake);
            })) {
      var facade = new LazyBuildOpeningService(owner);
      var pending = new ArrayList<BuildOpeningService.OpenOperation>();
      for (int i = 0; i < 64; i++) pending.add(facade.open(reference(), "enUS"));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      pending.getFirst().requestCancellation();
      assertFalse(pending.getFirst().completion().toCompletableFuture().isDone());
      assertCode(BuildOpeningException.Code.BUSY, facade.open(reference(), "enUS"));
      facade.close();
      release.countDown();
      for (var operation : pending)
        assertThrows(
            CompletionException.class,
            () ->
                operation.completion().toCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join());
      assertEquals(0, fake.opens.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void synchronousCompletionCallbackCanWaitForCrossThreadCancellationReentry() throws Exception {
    var fake = new Fake();
    var callback = new CompletableFuture<Void>();
    try (var owner = new LazyBootstrapService(() -> resources(fake));
        var reentry = Executors.newSingleThreadExecutor()) {
      var facade = new LazyBuildOpeningService(owner);
      var operation = facade.open(reference(), "enUS");
      assertTrue(fake.subscribed.await(2, TimeUnit.SECONDS));
      operation
          .completion()
          .whenComplete(
              (value, failure) -> {
                try {
                  reentry.submit(operation::requestCancellation).get(1, TimeUnit.SECONDS);
                  callback.complete(null);
                } catch (Exception blocked) {
                  callback.completeExceptionally(blocked);
                }
              });
      fake.onCancel = () -> fake.result.completeExceptionally(new CancellationException());
      operation.requestCancellation();
      callback.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void lateBindingCancellationInvokesDelegateOutsideItsMonitor() throws Exception {
    var fake = new Fake();
    fake.releaseBind = new CountDownLatch(1);
    var reentered = new CompletableFuture<Void>();
    var first = new AtomicBoolean(true);
    try (var owner = new LazyBootstrapService(() -> resources(fake));
        var reentry = Executors.newSingleThreadExecutor()) {
      var operation = new LazyBuildOpeningService(owner).open(reference(), "enUS");
      assertTrue(fake.bound.await(2, TimeUnit.SECONDS));
      fake.onCancel =
          () -> {
            if (!first.compareAndSet(true, false)) return;
            try {
              reentry.submit(operation::requestCancellation).get(1, TimeUnit.SECONDS);
              reentered.complete(null);
            } catch (Exception blocked) {
              reentered.completeExceptionally(blocked);
            }
          };
      operation.requestCancellation();
      fake.releaseBind.countDown();
      reentered.get(2, TimeUnit.SECONDS);
      fake.result.completeExceptionally(new CancellationException());
    } finally {
      fake.releaseBind.countDown();
    }
  }

  @Test
  void defaultResourcesRefuseOpeningWithoutInventingSupport() {
    try (var owner =
        new LazyBootstrapService(
            () ->
                new LazyBootstrapService.Resources() {
                  public BootstrapService service() {
                    return null;
                  }

                  public void close() {}
                })) {
      assertCode(
          BuildOpeningException.Code.UNSUPPORTED_METADATA,
          new LazyBuildOpeningService(owner).open(reference(), "enUS"));
    }
  }

  static void assertCode(
      BuildOpeningException.Code code, BuildOpeningService.OpenOperation operation) {
    var failure =
        assertThrows(
            CompletionException.class,
            () ->
                operation.completion().toCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join());
    assertEquals(code, ((BuildOpeningException) failure.getCause()).code());
  }

  static LazyBootstrapService.Resources resources(Fake fake) {
    return new LazyBootstrapService.Resources() {
      public BootstrapService service() {
        return null;
      }

      public BuildOpeningService buildOpening() {
        return fake;
      }

      public void close() {}
    };
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  static class Fake implements BuildOpeningService {
    final CompletableFuture<BuildMetadataAdmission> result = new CompletableFuture<>();
    final AtomicInteger opens = new AtomicInteger(),
        cancels = new AtomicInteger(),
        closes = new AtomicInteger();
    final CountDownLatch subscribed = new CountDownLatch(1),
        bound = new CountDownLatch(1),
        second = new CountDownLatch(1),
        cancelled = new CountDownLatch(1);
    volatile CountDownLatch releaseBind = new CountDownLatch(0);
    volatile BuildOpenProgress progress = BuildOpenProgress.initial();
    volatile Runnable onCancel = () -> {};

    public OpenOperation open(BuildLibraryReference r, String locale) {
      if (opens.incrementAndGet() == 2) second.countDown();
      bound.countDown();
      await(releaseBind);
      return new OpenOperation() {
        public CompletionStage<BuildMetadataAdmission> completion() {
          subscribed.countDown();
          return result.minimalCompletionStage();
        }

        public BuildOpenProgress progress() {
          return progress;
        }

        public void requestCancellation() {
          cancels.incrementAndGet();
          cancelled.countDown();
          onCancel.run();
        }
      };
    }

    public void close() {
      closes.incrementAndGet();
    }
  }

  static BuildLibraryReference reference() {
    var now = Instant.EPOCH;
    var versions =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/versions", now, new byte[] {1});
    var cdns =
        new SourceDocument(
            "https://us.version.battle.net/v2/products/wow/cdns", now, new byte[] {2});
    var values =
        Map.of(
            "Region",
            "us",
            "BuildConfig",
            "a".repeat(32),
            "CDNConfig",
            "b".repeat(32),
            "VersionsName",
            "authored");
    var build =
        new BuildSnapshot(
            "wow",
            "Unresolved",
            "Published",
            "us",
            "authored",
            "a".repeat(32),
            "b".repeat(32),
            now,
            false,
            new BuildEvidence("", "", List.of(versions, cdns)));
    var event =
        new DiscoveryEvent(
            UUID.randomUUID(),
            "wow",
            "us",
            now,
            "authored-v1",
            new DiscoveryEvent.ParsedSource(
                versions,
                List.of("Region", "BuildConfig", "CDNConfig", "VersionsName"),
                Optional.empty()),
            new DiscoveryEvent.ParsedSource(
                cdns, List.of("Name", "Path", "Hosts"), Optional.empty()),
            new DiscoveryEvent.CdnRow(
                0, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "authored.invalid")),
            List.of(new DiscoveryEvent.ObservedBuild(build, 0, values)));
    return BuildLibraryReference.from(event, 1).getFirst();
  }

  static BuildMetadataAdmission admission(BuildLibraryReference reference) {
    var required = new ArrayList<BuildMetadataAdmission.Requirement>();
    for (var role :
        List.of(
            BuildMetadataAdmission.Role.BUILD_CONFIG,
            BuildMetadataAdmission.Role.CDN_CONFIG,
            BuildMetadataAdmission.Role.ENCODING,
            BuildMetadataAdmission.Role.ROOT,
            BuildMetadataAdmission.Role.INSTALL))
      required.add(new BuildMetadataAdmission.Requirement(role, role.name()));
    var verified =
        required.stream()
            .map(
                r ->
                    new BuildMetadataAdmission.VerifiedRequirement(
                        r,
                        new StoredObject(new ObjectDigest("c".repeat(64)), 10),
                        new BuildMetadataAdmission.ProofIdentity(r.role(), "d".repeat(64))))
            .toList();
    return new BuildMetadataAdmission(
        reference,
        "enUS",
        "authored-all-variants-v1",
        required,
        new BuildMetadataAdmission.DownloadDeclaration(
            BuildMetadataAdmission.DownloadApplicability.AUTHENTICATED_NOT_PUBLISHED,
            "e".repeat(64)),
        new BuildMetadataAdmission.SizeDeclaration(
            BuildMetadataAdmission.SizeApplicability.AUTHENTICATED_NOT_PUBLISHED, "9".repeat(64)),
        new BuildMetadataAdmission.LocatorRequirements(List.of(), "f".repeat(64)),
        verified);
  }
}
