package io.vibeminer.application.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DefaultDiscoveryHistoryServiceTest {
  static final DiscoveryHistoryFilter FILTER =
      new DiscoveryHistoryFilter("wow", Optional.of("us"), Optional.empty());

  static DiscoveryEvent event() {
    return DiscoveryHistoryContractsTest.event(UUID.randomUUID(), Instant.EPOCH);
  }

  @Test
  void refreshReturnsTheExactCommittedEventEvenAfterLateCancellation() {
    var event = event();
    var observed = new AtomicInteger();
    var repo = new Repository();
    var operation = new AtomicReference<DiscoveryHistoryService.RefreshOperation>();
    var queue = new Queue();
    repo.appendAction =
        (value, token) -> {
          operation.get().requestCancellation();
          return value;
        };
    try (var service =
        new DefaultDiscoveryHistoryService(
            (p, r, t) -> {
              observed.incrementAndGet();
              return event;
            },
            repo,
            queue)) {
      var refresh = service.refreshObservation("wow", "us");
      operation.set(refresh);
      refresh.completion().toCompletableFuture().cancel(true);
      queue.run();
      assertSame(event, refresh.completion().toCompletableFuture().join());
      assertEquals(1, observed.get());
      assertEquals(1, repo.appends);
    }
  }

  @Test
  void cancellationAndCloseDoNotRetireQueuedActualJobs() {
    var queue = new Queue();
    var calls = new AtomicInteger();
    var repo = new Repository();
    var service =
        new DefaultDiscoveryHistoryService(
            (p, r, t) -> {
              calls.incrementAndGet();
              return event();
            },
            repo,
            queue);
    var operations = new ArrayList<DiscoveryHistoryService.RefreshOperation>();
    for (int i = 0; i < 4; i++) operations.add(service.refreshObservation("wow", "us"));
    operations.forEach(DiscoveryHistoryService.RefreshOperation::requestCancellation);
    assertCode(
        DiscoveryHistoryException.Code.BUSY, service.refreshObservation("wow", "us").completion());
    assertEquals(4, queue.tasks.size());
    assertEquals(0, calls.get());
    queue.run();
    var replacement = service.refreshObservation("wow", "us");
    assertEquals(4, queue.tasks.size());
    service.close();
    assertCode(
        DiscoveryHistoryException.Code.CLOSED, service.history(FILTER, Optional.empty(), 25));
    while (!queue.tasks.isEmpty()) queue.run();
    for (var operation : operations) assertCancelled(operation.completion());
    assertCancelled(replacement.completion());
    assertEquals(0, calls.get());
    assertEquals(0, repo.appends);
  }

  @Test
  void completionCallbacksCanAdmitNextJobAfterItsActualSlotRetiresOutsideMonitor() throws Exception {
    var queue = new Queue();
    var repo = new Repository();
    try (var service = new DefaultDiscoveryHistoryService((p, r, t) -> event(), repo, queue)) {
      var first = service.refreshObservation("wow", "us");
      for (int i = 0; i < 3; i++) service.refreshObservation("wow", "us");
      var callback = new CompletableFuture<Void>();
      first
          .completion()
          .whenComplete(
              (value, failure) -> {
                try {
                  var next =
                      CompletableFuture.supplyAsync(
                              () -> service.history(FILTER, Optional.empty(), 25))
                          .get(2, TimeUnit.SECONDS);
                  assertFalse(next.toCompletableFuture().isDone());
                  callback.complete(null);
                } catch (Throwable blocked) {
                  callback.completeExceptionally(blocked);
                }
              });
      queue.run();
      callback.get(3, TimeUnit.SECONDS);
      assertEquals(4, queue.tasks.size());
      while (!queue.tasks.isEmpty()) queue.run();
    }
  }

  @Test
  void persistenceFailureExplicitlyReportsNotSavedWithPreciseCause() {
    var cause =
        new DiscoveryHistoryException(
            DiscoveryHistoryException.Code.QUOTA_EXCEEDED, "Authored quota refusal");
    var repo = new Repository();
    repo.appendAction =
        (event, token) -> {
          throw cause;
        };
    try (var service =
        new DefaultDiscoveryHistoryService((p, r, t) -> event(), repo, Runnable::run)) {
      var failure = failure(service.refreshObservation("wow", "us").completion());
      assertEquals(DiscoveryHistoryException.Code.OBSERVATION_NOT_SAVED, failure.code());
      assertSame(cause, failure.getCause());
    }
  }

  @Test
  void listAndSelectionAreOfflineAndUseChosenEventsOwnDocumentsAndDuplicateOrdinal() {
    var chosen = event();
    var row = chosen.builds().getFirst();
    chosen =
        new DiscoveryEvent(
            chosen.eventId(),
            chosen.productId(),
            chosen.region(),
            chosen.completedAt(),
            chosen.parserRevision(),
            chosen.versions(),
            chosen.cdns(),
            chosen.selectedCdn(),
            List.of(row, new DiscoveryEvent.ObservedBuild(row.build(), 3, row.versionsValues())));
    var repo = new Repository();
    repo.stored = chosen;
    try (var service =
        new DefaultDiscoveryHistoryService(
            (p, r, t) -> {
              throw new AssertionError("History must not refresh");
            },
            repo,
            Runnable::run)) {
      assertTrue(
          service
              .history(FILTER, Optional.empty(), 25)
              .toCompletableFuture()
              .join()
              .events()
              .isEmpty());
      var selection =
          new DiscoveryObservationSelection(chosen.eventId(), row.build().snapshotId(), 3);
      var result = service.select(selection).toCompletableFuture().join();
      assertSame(chosen, result.event());
      assertEquals(3, result.build().versionsRowOrdinal());
      assertSame(
          chosen.versions().source(), result.build().build().evidence().documents().getFirst());
      assertEquals(
          DiscoveryHistorySelection.RemoteContentAvailability.NOT_CHECKED,
          result.remoteContentAvailability());
      assertCode(
          DiscoveryHistoryException.Code.NOT_FOUND,
          service.select(
              new DiscoveryObservationSelection(chosen.eventId(), row.build().snapshotId(), 9)));
    }
  }

  @Test
  void invalidAndRejectedAdmissionsDoNotRunGatewayOrRepository() {
    var repo = new Repository();
    var calls = new AtomicInteger();
    try (var service =
        new DefaultDiscoveryHistoryService(
            (p, r, t) -> {
              calls.incrementAndGet();
              return event();
            },
            repo,
            command -> {
              throw new RejectedExecutionException("Rejected");
            })) {
      assertCode(
          DiscoveryHistoryException.Code.INVALID_REQUEST,
          service.refreshObservation("../wow", "us").completion());
      assertCode(
          DiscoveryHistoryException.Code.INVALID_REQUEST,
          service.history(FILTER, Optional.empty(), 101));
      assertCode(DiscoveryHistoryException.Code.INVALID_REQUEST, service.select(null));
      for (int i = 0; i < 6; i++)
        assertCode(
            DiscoveryHistoryException.Code.BUSY,
            service.refreshObservation("wow", "us").completion());
      assertEquals(0, calls.get());
      assertEquals(0, repo.appends);
    }
  }

  @Test
  void malformedGatewayResultNeverReachesAppend() {
    var repo = new Repository();
    try (var service =
        new DefaultDiscoveryHistoryService((p, r, t) -> event(), repo, Runnable::run)) {
      assertCode(
          DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
          service.refreshObservation("wow", "kr").completion());
      assertEquals(0, repo.appends);
    }
  }

  @Test
  void ignoredCancellationDuringSourceReadKeepsCapacityUntilActualRetirement() throws Exception {
    var queue = new Queue();
    var repo = new Repository();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service =
        new DefaultDiscoveryHistoryService(
            (p, r, t) -> {
              entered.countDown();
              await(release);
              return event();
            },
            repo,
            queue);
    var first = service.refreshObservation("wow", "us");
    for (int i = 0; i < 3; i++) service.refreshObservation("wow", "us");
    var actual = new Thread(queue::run);
    actual.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      first.requestCancellation();
      assertFalse(first.completion().toCompletableFuture().isDone());
      assertCode(
          DiscoveryHistoryException.Code.BUSY, service.history(FILTER, Optional.empty(), 25));
      service.close();
      assertFalse(first.completion().toCompletableFuture().isDone());
      release.countDown();
      actual.join(2000);
      assertFalse(actual.isAlive());
      assertCancelled(first.completion());
      assertEquals(0, repo.appends);
      while (!queue.tasks.isEmpty()) queue.run();
    } finally {
      release.countDown();
      actual.join(2000);
      service.close();
    }
  }

  @Test
  void closeWhileAppendIgnoresCancellationStillReportsItsActualCommit() throws Exception {
    var queue = new Queue();
    var repo = new Repository();
    var event = event();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    repo.appendAction =
        (value, token) -> {
          entered.countDown();
          await(release);
          return value;
        };
    var service = new DefaultDiscoveryHistoryService((p, r, t) -> event, repo, queue);
    var refresh = service.refreshObservation("wow", "us");
    var worker = new Thread(queue::run);
    worker.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      service.close();
      assertFalse(refresh.completion().toCompletableFuture().isDone());
      release.countDown();
      worker.join(2000);
      assertFalse(worker.isAlive());
      assertSame(event, refresh.completion().toCompletableFuture().join());
    } finally {
      release.countDown();
      worker.join(2000);
      service.close();
    }
  }

  @Test
  void terminalPageCannotEscapeIncomingWatermarkPositionOrRequestedSize() {
    var cursor = new DiscoveryHistoryCursor(FILTER.identity(), 10, 8, 1);
    var rows = List.of(DiscoveryEventSummary.from(event(), 8));
    var repo =
        new Repository() {
          public DiscoveryHistoryPage page(
              DiscoveryHistoryFilter filter,
              Optional<DiscoveryHistoryCursor> position,
              int size,
              CancellationToken token) {
            return new DiscoveryHistoryPage(filter, rows, Optional.empty());
          }
        };
    try (var service =
        new DefaultDiscoveryHistoryService(
            (p, r, t) -> {
              throw new AssertionError();
            },
            repo,
            Runnable::run)) {
      assertCode(
          DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
          service.history(FILTER, Optional.of(cursor), 1));
      var wrong = new DiscoveryHistoryCursor(FILTER.identity(), 10, 8, 2);
      assertCode(
          DiscoveryHistoryException.Code.INVALID_REQUEST,
          service.history(FILTER, Optional.of(wrong), 1));
    }
  }

  @Test
  void selectedReadMustMatchRequestedEventEvenWhenSnapshotIdentityIsTheSame() {
    var requested = event();
    var repo = new Repository();
    repo.stored = event();
    try (var service =
        new DefaultDiscoveryHistoryService(
            (p, r, t) -> {
              throw new AssertionError();
            },
            repo,
            Runnable::run)) {
      var row = requested.builds().getFirst();
      assertCode(
          DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
          service.select(
              new DiscoveryObservationSelection(
                  requested.eventId(), row.build().snapshotId(), row.versionsRowOrdinal())));
    }
  }

  static void await(CountDownLatch release) {
    try {
      assertTrue(release.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failed);
    }
  }

  static final class Queue implements Executor {
    final java.util.concurrent.BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    public void execute(Runnable work) {
      tasks.add(work);
    }

    void run() {
      Objects.requireNonNull(tasks.poll()).run();
    }
  }

  @FunctionalInterface
  interface Append {
    DiscoveryEvent run(DiscoveryEvent value, CancellationToken token);
  }

  static class Repository implements DiscoveryHistoryRepository {
    int appends;
    DiscoveryEvent stored;
    Append appendAction = (value, token) -> value;

    public DiscoveryEvent append(
        DiscoveryEvent value, DiscoveryHistoryLimits limits, CancellationToken token) {
      appends++;
      return appendAction.run(value, token);
    }

    public DiscoveryHistoryPage page(
        DiscoveryHistoryFilter filter,
        Optional<DiscoveryHistoryCursor> cursor,
        int size,
        CancellationToken token) {
      return new DiscoveryHistoryPage(filter, List.of(), Optional.empty());
    }

    public Optional<DiscoveryEvent> read(UUID id, CancellationToken token) {
      return Optional.ofNullable(stored);
    }
  }

  static DiscoveryHistoryException failure(CompletionStage<?> result) {
    return assertInstanceOf(
        DiscoveryHistoryException.class,
        assertThrows(CompletionException.class, () -> result.toCompletableFuture().join())
            .getCause());
  }

  static void assertCode(DiscoveryHistoryException.Code code, CompletionStage<?> result) {
    assertEquals(code, failure(result).code());
  }

  static void assertCancelled(CompletionStage<?> result) {
    var failure = assertThrows(RuntimeException.class, () -> result.toCompletableFuture().join());
    assertTrue(
        failure instanceof CancellationException
            || failure.getCause() instanceof CancellationException);
  }
}
