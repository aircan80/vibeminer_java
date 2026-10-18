package io.vibeminer.application.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DefaultBuildRetentionModeServiceTest {
  static final Target TARGET =
      new Target(
          "a".repeat(64),
          "b".repeat(64),
          "c".repeat(64),
          "d".repeat(64),
          1,
          "scope-v1",
          "e".repeat(64));
  static final Policy POLICY = new Policy(100, 1000, 1000, 100, 2);
  static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

  static Intent intent(Mode mode, long generation) {
    return new Intent(TARGET, mode, generation, POLICY, Instant.EPOCH, 1, 0, new Cost(0, 0, 0));
  }

  @Test
  void defaultReadIsOnDemandAndDoesNotCreateOrActivate() {
    var repo = new Repo();
    var pump = new Pump(repo);
    try (var service = new DefaultBuildRetentionModeService(repo, pump, Runnable::run, CLOCK)) {
      var view = service.read(TARGET).completion().toCompletableFuture().join();
      assertEquals(Mode.ON_DEMAND, view.mode());
      assertTrue(view.intent().isEmpty());
      assertEquals(0, repo.writes);
      assertTrue(pump.calls.isEmpty());
    }
  }

  @Test
  void persistPrecedesResumeAndWithdrawalUsesOldGeneration() {
    var repo = new Repo();
    var pump = new Pump(repo);
    try (var service = new DefaultBuildRetentionModeService(repo, pump, Runnable::run, CLOCK)) {
      var all =
          service
              .setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY)
              .completion()
              .toCompletableFuture()
              .join();
      assertEquals(1, all.intent().orElseThrow().generation());
      assertEquals(List.of("resume:1"), pump.calls);
      var demand =
          service
              .setMode(TARGET, 1, Mode.ON_DEMAND, POLICY)
              .completion()
              .toCompletableFuture()
              .join();
      assertEquals(2, demand.intent().orElseThrow().generation());
      assertEquals(List.of("resume:1", "withdraw:1"), pump.calls);
    }
  }

  @Test
  void activationFailureReportsCommittedPreferenceAndResumeRetriesExistingGeneration() {
    var repo = new Repo();
    var pump = new Pump(repo);
    pump.fail = true;
    try (var service = new DefaultBuildRetentionModeService(repo, pump, Runnable::run, CLOCK)) {
      var saved =
          service
              .setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY)
              .completion()
              .toCompletableFuture()
              .join();
      assertEquals(Mode.DOWNLOAD_ALL, saved.mode());
      assertTrue(saved.activationFailure().isPresent());
      assertEquals(1, repo.writes);
      pump.fail = false;
      var resumed = service.resume(TARGET).completion().toCompletableFuture().join();
      assertTrue(resumed.activationFailure().isEmpty());
      assertEquals(1, resumed.intent().orElseThrow().generation());
      assertEquals(1, repo.writes);
    }
  }

  @Test
  void cancelledQueuedMutationDoesNotCommitAndCapacityRetiresOnlyWithActualRun() {
    var repo = new Repo();
    var pump = new Pump(repo);
    var queue = new Queue();
    try (var service = new DefaultBuildRetentionModeService(repo, pump, queue, CLOCK)) {
      var one = service.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY);
      one.requestCancellation();
      service.read(TARGET);
      service.read(TARGET);
      service.read(TARGET);
      var busy =
          assertThrows(
              CompletionException.class,
              () -> service.read(TARGET).completion().toCompletableFuture().join());
      assertEquals(
          BuildRetentionException.Code.BUSY, ((BuildRetentionException) busy.getCause()).code());
      assertFalse(one.completion().toCompletableFuture().isDone());
      queue.run();
      assertThrows(CompletionException.class, () -> one.completion().toCompletableFuture().join());
      assertEquals(0, repo.writes);
      assertTrue(pump.calls.isEmpty());
    }
  }

  @Test
  void commitWinsLateCancellationAndDerivedFutureCannotCancelActualOperation() {
    var repo = new Repo();
    var pump = new Pump(repo);
    var queue = new Queue();
    try (var service = new DefaultBuildRetentionModeService(repo, pump, queue, CLOCK)) {
      var op = service.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY);
      repo.afterCommit = op::requestCancellation;
      op.completion().toCompletableFuture().cancel(true);
      queue.run();
      var saved = op.completion().toCompletableFuture().join();
      assertEquals(Mode.DOWNLOAD_ALL, saved.mode());
      assertEquals(List.of("resume:1"), pump.calls);
    }
  }

  @Test
  void repositoryRejectionDoesNotCallPumpOrForgeAuthority() {
    var repo = new Repo();
    var pump = new Pump(repo);
    repo.reject = true;
    try (var service = new DefaultBuildRetentionModeService(repo, pump, Runnable::run, CLOCK)) {
      var failure =
          assertThrows(
              CompletionException.class,
              () ->
                  service
                      .setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY)
                      .completion()
                      .toCompletableFuture()
                      .join());
      assertEquals(
          BuildRetentionException.Code.NOT_READY,
          ((BuildRetentionException) failure.getCause()).code());
      assertEquals(0, repo.writes);
      assertTrue(pump.calls.isEmpty());
    }
  }

  @Test
  void closeCancelsOnlyOwnedQueuedActionsWithoutClosingBorrowedExecutor() {
    var repo = new Repo();
    var pump = new Pump(repo);
    var queue = new Queue();
    var service = new DefaultBuildRetentionModeService(repo, pump, queue, CLOCK);
    var op = service.setMode(TARGET, 0, Mode.DOWNLOAD_ALL, POLICY);
    service.close();
    assertFalse(op.completion().toCompletableFuture().isDone());
    queue.run();
    assertEquals(0, repo.writes);
    assertThrows(CompletionException.class, () -> op.completion().toCompletableFuture().join());
    var executed = new AtomicBoolean();
    queue.execute(() -> executed.set(true));
    queue.run();
    assertTrue(executed.get());
    assertThrows(
        CompletionException.class,
        () -> service.read(TARGET).completion().toCompletableFuture().join());
  }

  @Test
  void callbacksCanReenterAfterActualCapacityRetirement() {
    var repo = new Repo();
    var pump = new Pump(repo);
    var queue = new Queue();
    try (var service = new DefaultBuildRetentionModeService(repo, pump, queue, CLOCK)) {
      var op = service.read(TARGET);
      service.read(TARGET);
      service.read(TARGET);
      service.read(TARGET);
      var next = new AtomicReference<BuildRetentionModeService.Operation>();
      op.completion().whenComplete((v, f) -> next.set(service.read(TARGET)));
      queue.run();
      assertNotNull(next.get());
      assertEquals(Mode.ON_DEMAND, next.get().completion().toCompletableFuture().join().mode());
    }
  }

  @Test
  void adapterReturningAnotherTargetIsCorruptNotUserInput() {
    var other =
        new Target(
            "f".repeat(64),
            TARGET.snapshotId(),
            TARGET.admissionId(),
            TARGET.inventoryId(),
            1,
            TARGET.scopeRevision(),
            TARGET.scopeCommitment());
    var repo = new Repo();
    repo.current =
        new Intent(other, Mode.DOWNLOAD_ALL, 1, POLICY, Instant.EPOCH, 1, 0, new Cost(0, 0, 0));
    try (var service =
        new DefaultBuildRetentionModeService(repo, new Pump(repo), Runnable::run, CLOCK)) {
      var failed =
          assertThrows(
              CompletionException.class,
              () -> service.read(TARGET).completion().toCompletableFuture().join());
      assertEquals(
          BuildRetentionException.Code.CORRUPT_STATE,
          ((BuildRetentionException) failed.getCause()).code());
    }
  }

  static class Queue implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      while (!tasks.isEmpty()) tasks.remove().run();
    }
  }

  static class Pump implements BuildRetentionActivation {
    final Repo repo;
    final List<String> calls = new ArrayList<>();
    boolean fail;

    Pump(Repo repo) {
      this.repo = repo;
    }

    public void resumeDownloadAll(Intent i) {
      assertSame(repo.current, i);
      calls.add("resume:" + i.generation());
      if (fail) throw new BuildRetentionException(BuildRetentionException.Code.BUSY, "busy");
    }

    public void withdraw(Target target, long old) {
      assertEquals(TARGET, target);
      assertEquals(Mode.ON_DEMAND, repo.current.mode());
      calls.add("withdraw:" + old);
    }
  }

  static class Repo implements BuildRetentionRepository {
    Intent current;
    int writes;
    boolean reject;
    Runnable afterCommit = () -> {};

    public Optional<Intent> readIntent(Target t, CancellationToken c) {
      c.throwIfCancellationRequested();
      return Optional.ofNullable(current);
    }

    public Intent setMode(
        Target t, long generation, Mode mode, Policy p, Instant time, CancellationToken c) {
      c.throwIfCancellationRequested();
      if (reject)
        throw new BuildRetentionException(
            BuildRetentionException.Code.NOT_READY, "Not issued/sealed");
      assertEquals(current == null ? 0 : current.generation(), generation);
      current = intent(mode, generation + 1);
      writes++;
      afterCommit.run();
      return current;
    }

    public Intent fillWindow(Target t, long g, int m, CancellationToken c) {
      throw new AssertionError();
    }

    public Optional<Job> claimNext(Target t, long g, CancellationToken c) {
      throw new AssertionError();
    }

    public Job finish(Lease l, Finish f, CancellationToken c) {
      throw new AssertionError();
    }

    public Job retryFailed(Target t, long g, String id, CancellationToken c) {
      throw new AssertionError();
    }

    public List<Job> pageJobs(Target t, long after, int max, CancellationToken c) {
      throw new AssertionError();
    }
  }
}
