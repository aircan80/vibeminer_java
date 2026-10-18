package io.vibeminer.application.buildlibrary;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.LegacyBuildObservationImporter.*;
import io.vibeminer.application.discoveryhistory.DiscoveryHistoryLimits;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class BuildLibraryLocalPreparationTest {
  private static final Instant NOW = DefaultBuildLibraryServiceTest.NOW;

  private static Cursor cursor(int number) {
    return new Cursor("wow", "%064x".formatted(number));
  }

  private static Batch batch(int scanned, int imported, int already, int next) {
    return new Batch(
        "wow",
        scanned,
        imported,
        already,
        next == 0 ? Optional.empty() : Optional.of(cursor(next)),
        next == 0);
  }

  private static DefaultBuildLibraryService service(
      LegacyBuildObservationImporter importer, Executor executor) {
    var fake = new DefaultBuildLibraryServiceTest.Fake();
    return new DefaultBuildLibraryService(
        (product, token) -> {
          throw new AssertionError("Local preparation must not retrieve remote metadata");
        },
        fake,
        fake,
        fake,
        fake,
        DiscoveryHistoryLimits.defaults(),
        executor,
        DefaultBuildLibraryServiceTest.CLOCK,
        importer);
  }

  private static Throwable failure(CompletionStage<?> stage) {
    return assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join())
        .getCause();
  }

  @Test
  void fourBoundedStepsAggregateProgressAndReturnExactContinuationForNextEntry() {
    var calls = new AtomicInteger();
    LegacyBuildObservationImporter importer =
        (product, cursor, limits, recordedAt, token) -> {
          int call = calls.incrementAndGet();
          assertEquals("wow", product);
          assertEquals(NOW, recordedAt);
          assertEquals(Limits.defaults(), limits);
          assertFalse(token.isCancellationRequested());
          assertEquals(call == 1 ? Optional.empty() : Optional.of(cursor(call - 1)), cursor);
          return batch(2, 1, 1, call == 6 ? 0 : call);
        };
    try (var service = service(importer, Runnable::run)) {
      var first =
          service.prepareLocal("wow", Optional.empty()).completion().toCompletableFuture().join();
      assertEquals(4, calls.get());
      assertEquals(4, first.steps());
      assertEquals(batch(8, 4, 4, 4), first.progress());
      assertFalse(first.cancellationRequested());
      var second =
          service
              .prepareLocal("wow", first.progress().nextCursor())
              .completion()
              .toCompletableFuture()
              .join();
      assertEquals(6, calls.get());
      assertEquals(2, second.steps());
      assertEquals(batch(4, 2, 2, 0), second.progress());
      assertFalse(second.cancellationRequested());
    }
  }

  @Test
  void actualCompleteBatchStopsImmediatelyWithoutRemoteAdmission() {
    var calls = new AtomicInteger();
    try (var service =
        service(
            (product, cursor, limits, at, token) -> {
              calls.incrementAndGet();
              return batch(0, 0, 0, 0);
            },
            Runnable::run)) {
      var result =
          service.prepareLocal("wow", Optional.empty()).completion().toCompletableFuture().join();
      assertEquals(1, calls.get());
      assertEquals(1, result.steps());
      assertTrue(result.progress().complete());
      assertFalse(result.cancellationRequested());
    }
  }

  @Test
  void typedFailureKeepsEarlierAndCurrentCommittedProgressAndPreciseCause() {
    var calls = new AtomicInteger();
    var storage = new IllegalStateException("authored storage refusal");
    try (var service =
        service(
            (product, cursor, limits, at, token) -> {
              if (calls.incrementAndGet() == 1) return batch(2, 1, 1, 1);
              throw new LegacyBuildImportException(
                  LegacyBuildImportException.Code.CORRUPT_LEGACY,
                  "authored incompatible evidence",
                  batch(3, 2, 1, 2),
                  storage);
            },
            Runnable::run)) {
      var error =
          assertInstanceOf(
              LegacyBuildImportException.class,
              failure(service.prepareLocal("wow", Optional.empty()).completion()));
      assertEquals(LegacyBuildImportException.Code.CORRUPT_LEGACY, error.code());
      assertEquals(batch(5, 3, 2, 2), error.partialProgress());
      assertSame(
          storage, assertInstanceOf(LegacyBuildImportException.class, error.getCause()).getCause());
      assertEquals(2, calls.get());
    }
  }

  @Test
  void queuedCancellationPerformsNoImporterWorkAndFacadeCancellationDoesNotHideActualWork() {
    var queue = new DefaultBuildLibraryServiceTest.Queue();
    var calls = new AtomicInteger();
    try (var service =
        service(
            (product, cursor, limits, at, token) -> {
              calls.incrementAndGet();
              return batch(1, 1, 0, 0);
            },
            queue)) {
      var cancelled = service.prepareLocal("wow", Optional.empty());
      cancelled.requestCancellation();
      queue.run();
      assertInstanceOf(CancellationException.class, failure(cancelled.completion()));
      assertEquals(0, calls.get());
      var actual = service.prepareLocal("wow", Optional.empty());
      actual.completion().toCompletableFuture().cancel(true);
      queue.run();
      assertEquals(1, calls.get());
      assertEquals(1, actual.completion().toCompletableFuture().join().progress().importedPairs());
    }
  }

  @Test
  void cancellationAfterCommittedBatchReturnsActualProgressAndStartsNoMoreWork() {
    var owner = new AtomicReference<BuildLibraryService.LocalPreparationOperation>();
    var calls = new AtomicInteger();
    var queue = new DefaultBuildLibraryServiceTest.Queue();
    try (var service =
        service(
            (product, cursor, limits, at, token) -> {
              calls.incrementAndGet();
              owner.get().requestCancellation();
              return batch(2, 1, 0, 1);
            },
            queue)) {
      var operation = service.prepareLocal("wow", Optional.empty());
      owner.set(operation);
      queue.run();
      var result = operation.completion().toCompletableFuture().join();
      assertEquals(1, calls.get());
      assertEquals(1, result.steps());
      assertEquals(batch(2, 1, 0, 1), result.progress());
      assertTrue(result.cancellationRequested());
    }
  }

  @Test
  void importerCancellationWithCommittedPartialBatchReturnsCommittedResult() {
    try (var service =
        service(
            (product, cursor, limits, at, token) -> {
              throw new LegacyBuildImportCancelled(batch(3, 1, 1, 1));
            },
            Runnable::run)) {
      var result =
          service.prepareLocal("wow", Optional.empty()).completion().toCompletableFuture().join();
      assertEquals(batch(3, 1, 1, 1), result.progress());
      assertEquals(1, result.steps());
      assertTrue(result.cancellationRequested());
    }
  }

  @Test
  void queuedPreparationSharesFourActualSlotsAndReleasesBeforeCompletionCallbacks() {
    var queue = new DefaultBuildLibraryServiceTest.Queue();
    try (var service = service((product, cursor, limits, at, token) -> batch(1, 1, 0, 0), queue)) {
      var first = service.prepareLocal("wow", Optional.empty());
      for (int i = 0; i < 3; i++) service.prepareLocal("wow", Optional.empty());
      assertEquals(
          BuildLibraryException.Code.BUSY,
          assertInstanceOf(
                  BuildLibraryException.class,
                  failure(service.prepareLocal("wow", Optional.empty()).completion()))
              .code());
      var after = new AtomicReference<BuildLibraryService.LocalPreparationOperation>();
      first
          .completion()
          .whenComplete(
              (value, problem) -> after.set(service.prepareLocal("wow", Optional.empty())));
      first.completion().toCompletableFuture().cancel(true);
      assertEquals(
          BuildLibraryException.Code.BUSY,
          assertInstanceOf(
                  BuildLibraryException.class,
                  failure(service.prepareLocal("wow", Optional.empty()).completion()))
              .code());
      queue.run();
      assertNotNull(after.get());
      assertFalse(after.get().completion().toCompletableFuture().isDone());
      assertEquals(4, queue.tasks.size());
      while (!queue.tasks.isEmpty()) queue.run();
      assertEquals(
          1, after.get().completion().toCompletableFuture().join().progress().importedPairs());
    }
  }

  @Test
  void runningImporterRetainsAdmissionUntilActualRetirementDespiteCancellation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var queue = new DefaultBuildLibraryServiceTest.Queue();
    var calls = new AtomicInteger();
    try (var service =
        service(
            (product, cursor, limits, at, token) -> {
              if (calls.incrementAndGet() == 1) {
                entered.countDown();
                DefaultBuildLibraryServiceTest.await(release);
              }
              return batch(1, 1, 0, 0);
            },
            queue)) {
      var first = service.prepareLocal("wow", Optional.empty());
      var worker = new Thread(queue::run);
      worker.start();
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        first.requestCancellation();
        for (int i = 0; i < 3; i++) service.prepareLocal("wow", Optional.empty());
        assertEquals(
            BuildLibraryException.Code.BUSY,
            assertInstanceOf(
                    BuildLibraryException.class,
                    failure(service.prepareLocal("wow", Optional.empty()).completion()))
                .code());
        assertFalse(first.completion().toCompletableFuture().isDone());
        release.countDown();
        worker.join(2000);
        assertFalse(worker.isAlive());
        var result = first.completion().toCompletableFuture().join();
        assertEquals(1, result.progress().importedPairs());
        assertTrue(result.cancellationRequested());
        var next = service.prepareLocal("wow", Optional.empty());
        assertFalse(next.completion().toCompletableFuture().isDone());
        while (!queue.tasks.isEmpty()) queue.run();
        assertTrue(next.completion().toCompletableFuture().isDone());
      } finally {
        release.countDown();
        worker.join(2000);
      }
    }
  }

  @Test
  void missingImporterCompatibilityAndInvalidOrClosedRequestsAreExplicitFailures() {
    var fake = new DefaultBuildLibraryServiceTest.Fake();
    try (var existing =
        DefaultBuildLibraryServiceTest.service(
            (product, token) -> {
              throw new AssertionError();
            },
            fake,
            Runnable::run)) {
      assertInstanceOf(
          UnsupportedOperationException.class,
          failure(existing.prepareLocal("wow", Optional.empty()).completion()));
    }
    var calls = new AtomicInteger();
    var service =
        service(
            (product, cursor, limits, at, token) -> {
              calls.incrementAndGet();
              return batch(0, 0, 0, 0);
            },
            Runnable::run);
    try {
      assertEquals(
          BuildLibraryException.Code.INVALID_REQUEST,
          assertInstanceOf(
                  BuildLibraryException.class,
                  failure(service.prepareLocal("../wow", Optional.empty()).completion()))
              .code());
      assertEquals(
          BuildLibraryException.Code.INVALID_REQUEST,
          assertInstanceOf(
                  BuildLibraryException.class,
                  failure(
                      service
                          .prepareLocal("wow", Optional.of(new Cursor("wowf", "a".repeat(64))))
                          .completion()))
              .code());
      service.close();
      assertEquals(
          BuildLibraryException.Code.CLOSED,
          assertInstanceOf(
                  BuildLibraryException.class,
                  failure(service.prepareLocal("wow", Optional.empty()).completion()))
              .code());
      assertEquals(0, calls.get());
    } finally {
      service.close();
    }
  }
}
