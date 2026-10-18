package io.vibeminer.application.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BuildRetentionPumpTest {
  static final CancellationToken NONE = CancellationToken.none();
  static final Policy POLICY = new Policy(100, 10000, 10000, 100, 2);
  static final String PROOF = "a".repeat(64);

  static Target target(char c) {
    return new Target(
        ("" + c).repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        "d".repeat(64),
        1,
        "scope-v1",
        "e".repeat(64));
  }

  static Member member(int n) {
    return new Member(
        n,
        String.format("%064x", n + 1),
        "a".repeat(32),
        "b".repeat(32),
        20,
        new Cost(20, 20, 1),
        "c".repeat(64));
  }

  static class Tasks implements Executor {
    final ArrayDeque<Runnable> queued = new ArrayDeque<>();

    public synchronized void execute(Runnable r) {
      queued.add(r);
    }

    synchronized Runnable take() {
      return queued.remove();
    }

    synchronized int size() {
      return queued.size();
    }

    void drain() {
      while (size() > 0) take().run();
    }
  }

  static class Repo implements BuildRetentionRepository {
    final Map<Target, Intent> intents = new LinkedHashMap<>();
    final Map<Target, List<Job>> jobs = new HashMap<>();
    final List<Target> claimed = new ArrayList<>();
    final List<Finish> finishes = new ArrayList<>();
    int running, highWater, fills;

    Intent add(Target t, int count) {
      var i =
          new Intent(t, Mode.DOWNLOAD_ALL, 1, POLICY, Instant.EPOCH, count, 0, new Cost(0, 0, 0));
      intents.put(t, i);
      jobs.put(t, new ArrayList<>());
      return i;
    }

    public synchronized Optional<Intent> readIntent(Target t, CancellationToken c) {
      c.throwIfCancellationRequested();
      return Optional.ofNullable(intents.get(t));
    }

    public Intent setMode(Target t, long g, Mode m, Policy p, Instant at, CancellationToken c) {
      throw new UnsupportedOperationException();
    }

    public synchronized Intent fillWindow(Target t, long g, int maximum, CancellationToken c) {
      assertEquals(128, maximum);
      c.throwIfCancellationRequested();
      fills++;
      var i = intents.get(t);
      assertEquals(g, i.generation());
      var list = jobs.get(t);
      for (long n = i.nextOrdinal(); n < Math.min(i.memberCount(), i.nextOrdinal() + maximum); n++)
        list.add(
            new Job(
                t,
                member((int) n),
                State.QUEUED,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
      var next =
          new Intent(
              t,
              i.mode(),
              g,
              i.policy(),
              i.authorizedAt(),
              i.memberCount(),
              list.size(),
              i.reserved());
      intents.put(t, next);
      return next;
    }

    public synchronized Optional<Job> claimNext(Target t, long g, CancellationToken c) {
      c.throwIfCancellationRequested();
      assertEquals(g, intents.get(t).generation());
      if (running == 2) return Optional.empty();
      var list = jobs.get(t);
      for (int n = 0; n < list.size(); n++) {
        var j = list.get(n);
        if (j.state() != State.QUEUED) continue;
        var lease =
            new Lease(t.identity(), j.member().memberId(), g, UUID.randomUUID(), UUID.randomUUID());
        var next =
            new Job(
                t,
                j.member(),
                State.RUNNING,
                j.attempts() + 1,
                Optional.of(lease),
                Optional.empty(),
                Optional.empty());
        list.set(n, next);
        claimed.add(t);
        running++;
        highWater = Math.max(highWater, running);
        return Optional.of(next);
      }
      return Optional.empty();
    }

    public synchronized Job finish(Lease lease, Finish result, CancellationToken c) {
      assertFalse(c.isCancellationRequested());
      finishes.add(result);
      running--;
      for (var list : jobs.values())
        for (int n = 0; n < list.size(); n++) {
          var j = list.get(n);
          if (j.lease().filter(lease::equals).isPresent()) {
            var next =
                new Job(
                    j.target(),
                    j.member(),
                    result.state(),
                    j.attempts(),
                    Optional.empty(),
                    result.fulfillmentProof(),
                    result.failure());
            list.set(n, next);
            return next;
          }
        }
      throw new AssertionError("Unknown lease");
    }

    public synchronized boolean hasPending(Target target, CancellationToken token) {
      token.throwIfCancellationRequested();
      return jobs.get(target).stream()
          .anyMatch(j -> j.state() == State.QUEUED || j.state() == State.RUNNING);
    }

    public Job retryFailed(Target t, long g, String m, CancellationToken c) {
      throw new UnsupportedOperationException();
    }

    public synchronized List<Job> pageJobs(Target t, long after, int max, CancellationToken c) {
      return jobs.get(t).stream().filter(j -> j.member().ordinal() > after).limit(max).toList();
    }
  }

  @Test
  void globallyBusyQueueIsBlockedAndSameGenerationCanBeExplicitlyResumed() {
    var externalBusy = new java.util.concurrent.atomic.AtomicBoolean(true);
    var r =
        new Repo() {
          @Override
          public synchronized Optional<Job> claimNext(Target t, long g, CancellationToken token) {
            if (externalBusy.get()) return Optional.empty();
            return super.claimNext(t, g, token);
          }
        };
    var tasks = new Tasks();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump = new BuildRetentionPump(r, (t, m, d, c) -> PROOF, scheduler, tasks)) {
      var empty = r.add(target('1'), 0);
      pump.resumeDownloadAll(empty);
      tasks.drain();
      assertEquals(
          BuildRetentionPump.Outcome.QUEUE_DRAINED,
          pump.completion(empty.target(), 1).orElseThrow().toCompletableFuture().join().outcome());
      var a = r.add(target('2'), 1);
      pump.resumeDownloadAll(a);
      tasks.drain();
      var first = pump.completion(a.target(), 1).orElseThrow();
      assertEquals(
          BuildRetentionPump.Outcome.BLOCKED, first.toCompletableFuture().join().outcome());
      assertEquals(0, r.finishes.size());
      externalBusy.set(false);
      pump.resumeDownloadAll(r.readIntent(a.target(), NONE).orElseThrow());
      tasks.drain();
      assertEquals(1, r.finishes.size());
      assertNotSame(first, pump.completion(a.target(), 1).orElseThrow());
      assertEquals(
          BuildRetentionPump.Outcome.QUEUE_DRAINED,
          pump.completion(a.target(), 1).orElseThrow().toCompletableFuture().join().outcome());
    }
  }

  @Test
  void modeChangedDuringFillIsRevalidatedBeforeClaim() {
    var r =
        new Repo() {
          @Override
          public synchronized Intent fillWindow(
              Target t, long generation, int maximum, CancellationToken token) {
            var filled = super.fillWindow(t, generation, maximum, token);
            intents.put(
                t,
                new Intent(
                    t,
                    Mode.ON_DEMAND,
                    generation + 1,
                    filled.policy(),
                    filled.authorizedAt(),
                    filled.memberCount(),
                    filled.nextOrdinal(),
                    filled.reserved()));
            return filled;
          }
        };
    var tasks = new Tasks();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump =
            new BuildRetentionPump(
                r,
                (t, m, d, c) -> {
                  fail("Stale mode must not retain");
                  return PROOF;
                },
                scheduler,
                tasks)) {
      var intent = r.add(target('1'), 1);
      pump.resumeDownloadAll(intent);
      tasks.drain();
      assertTrue(r.claimed.isEmpty());
      assertEquals(
          BuildRetentionPump.Outcome.WITHDRAWN,
          pump.completion(intent.target(), 1).orElseThrow().toCompletableFuture().join().outcome());
    }
  }

  @Test
  void twoHeldActualWorkersKeepThirdTargetQueuedAndCloseWaitsForRetirement() throws Exception {
    var r = new Repo();
    var tasks = new Tasks();
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump =
            new BuildRetentionPump(
                r,
                (t, m, d, c) -> {
                  entered.countDown();
                  try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                  return PROOF;
                },
                scheduler,
                tasks)) {
      var a = r.add(target('1'), 1);
      var b = r.add(target('2'), 1);
      var c = r.add(target('3'), 1);
      pump.resumeDownloadAll(a);
      pump.resumeDownloadAll(b);
      pump.resumeDownloadAll(c);
      assertEquals(2, tasks.size());
      var first = new Thread(tasks.take());
      var second = new Thread(tasks.take());
      first.start();
      second.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(2, r.highWater);
      assertEquals(2, r.claimed.size());
      assertEquals(0, tasks.size());
      var completion = pump.completion(a.target(), 1).orElseThrow().toCompletableFuture();
      completion.cancel(false);
      assertEquals(2, r.running);
      assertTrue(r.finishes.isEmpty());
      pump.close();
      assertEquals(2, r.running);
      assertFalse(pump.completion(b.target(), 1).orElseThrow().toCompletableFuture().isDone());
      release.countDown();
      first.join(5000);
      second.join(5000);
      assertFalse(first.isAlive());
      assertFalse(second.isAlive());
      assertEquals(0, r.running);
      assertEquals(2, r.finishes.size());
      assertEquals(2, r.claimed.size());
    } finally {
      release.countDown();
    }
  }

  @Test
  void activeHandleLimitIsBoundedAndCapacityFailureDoesNotWithdrawOthers() {
    var r = new Repo();
    var tasks = new Tasks();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump = new BuildRetentionPump(r, (t, m, d, c) -> PROOF, scheduler, tasks)) {
      for (int n = 0; n < 16; n++) {
        var t =
            new Target(
                String.format("%064x", n + 1),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                1,
                "scope-v1",
                "e".repeat(64));
        pump.resumeDownloadAll(r.add(t, 1));
      }
      var extra = r.add(target('f'), 1);
      assertThrows(BuildRetentionException.class, () -> pump.resumeDownloadAll(extra));
      assertEquals(2, tasks.size());
      tasks.drain();
      assertEquals(16, r.finishes.size());
      pump.resumeDownloadAll(extra);
      tasks.drain();
      assertEquals(17, r.finishes.size());
    }
  }

  @Test
  void directExecutorReentrantActivationAndMalformedProofDoNotLeakOrHotLoop() {
    var r = new Repo();
    var a = r.add(target('1'), 1);
    var b = r.add(target('2'), 1);
    var ref = new java.util.concurrent.atomic.AtomicReference<BuildRetentionPump>();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump =
            new BuildRetentionPump(
                r,
                (t, m, d, c) -> {
                  if (t.equals(a.target())) {
                    ref.get().resumeDownloadAll(b);
                    return "bad";
                  }
                  return PROOF;
                },
                scheduler,
                Runnable::run)) {
      ref.set(pump);
      pump.resumeDownloadAll(a);
      assertEquals(2, r.finishes.size());
      assertEquals(0, r.running);
      assertEquals(FailureCode.CORRUPT, r.finishes.getFirst().failure().orElseThrow().code());
      assertEquals(State.SUCCEEDED, r.finishes.getLast().state());
    }
  }

  @Test
  void sourceErrorStillFinishesLeaseAndOtherTargetRuns() {
    var r = new Repo();
    var tasks = new Tasks();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump =
            new BuildRetentionPump(
                r,
                (t, m, d, c) -> {
                  if (t.equals(target('1'))) throw new AssertionError("source failure");
                  return PROOF;
                },
                scheduler,
                tasks)) {
      var a = r.add(target('1'), 1);
      var b = r.add(target('2'), 1);
      pump.resumeDownloadAll(a);
      pump.resumeDownloadAll(b);
      tasks.drain();
      assertEquals(0, r.running);
      assertEquals(2, r.finishes.size());
      assertEquals(
          BuildRetentionPump.Outcome.BLOCKED,
          pump.completion(a.target(), 1).orElseThrow().toCompletableFuture().join().outcome());
      assertEquals(State.SUCCEEDED, r.finishes.getLast().state());
    }
  }

  @Test
  void activationRequiresPersistedExactIntentAndOnlyTwoWrappersArePrefetched() {
    var r = new Repo();
    var tasks = new Tasks();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump = new BuildRetentionPump(r, (t, m, d, c) -> PROOF, scheduler, tasks)) {
      var a = r.add(target('1'), 3);
      var forged =
          new Intent(
              a.target(),
              a.mode(),
              2,
              a.policy(),
              a.authorizedAt(),
              a.memberCount(),
              0,
              a.reserved());
      assertThrows(BuildRetentionException.class, () -> pump.resumeDownloadAll(forged));
      assertEquals(0, tasks.size());
      pump.resumeDownloadAll(a);
      assertEquals(2, tasks.size());
      assertEquals(0, r.running);
      tasks.drain();
      assertEquals(3, r.finishes.size());
      assertEquals(
          BuildRetentionPump.Outcome.QUEUE_DRAINED,
          pump.completion(a.target(), 1).orElseThrow().toCompletableFuture().join().outcome());
    }
  }

  @Test
  void withdrawnQueuedWrappersNeverClaimAndBorrowedExecutorRemainsUsable() {
    var r = new Repo();
    var tasks = new Tasks();
    var scheduler = new PrioritySourceScheduler(Runnable::run);
    var pump =
        new BuildRetentionPump(
            r,
            (t, m, d, c) -> {
              fail("withdrawn source");
              return PROOF;
            },
            scheduler,
            tasks);
    var a = r.add(target('1'), 3);
    pump.resumeDownloadAll(a);
    pump.withdraw(a.target(), 1);
    pump.close();
    tasks.drain();
    assertEquals(0, r.running);
    assertTrue(r.claimed.isEmpty());
    tasks.execute(() -> {});
    assertEquals(1, tasks.size());
    assertFalse(scheduler.newBulkOwner(a.target().snapshotId()).demand().isCancellationRequested());
    scheduler.close();
  }

  @Test
  void staleGenerationIsRevalidatedBeforeAnyClaim() {
    var r = new Repo();
    var tasks = new Tasks();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump = new BuildRetentionPump(r, (t, m, d, c) -> PROOF, scheduler, tasks)) {
      var a = r.add(target('1'), 3);
      pump.resumeDownloadAll(a);
      r.intents.put(
          a.target(),
          new Intent(
              a.target(), Mode.ON_DEMAND, 2, POLICY, Instant.EPOCH, 3, 0, new Cost(0, 0, 0)));
      tasks.drain();
      assertTrue(r.claimed.isEmpty());
    }
  }

  @Test
  void memberFailureDoesNotRetryOrPreventOtherTargetsAndRotationIsFair() {
    var r = new Repo();
    var tasks = new Tasks();
    var seen = new ArrayList<Target>();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump =
            new BuildRetentionPump(
                r,
                (t, m, d, c) -> {
                  seen.add(t);
                  if (t.equals(target('1')) && m.ordinal() == 0)
                    throw new BuildRetentionException(
                        BuildRetentionException.Code.QUOTA_EXCEEDED, "budget");
                  return PROOF;
                },
                scheduler,
                tasks)) {
      var a = r.add(target('1'), 3);
      var b = r.add(target('2'), 3);
      pump.resumeDownloadAll(a);
      pump.resumeDownloadAll(b);
      tasks.drain();
      assertEquals(
          List.of(a.target(), b.target(), a.target(), b.target(), a.target(), b.target()), seen);
      assertEquals(6, r.finishes.size());
      assertEquals(1, r.finishes.stream().filter(f -> f.state() == State.FAILED).count());
    }
  }

  @Test
  void heldSourceRetainsLedgerLeaseUntilActualReturnDespiteWithdrawal() throws Exception {
    var r = new Repo();
    var tasks = new Tasks();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var tokenSeen = new CompletableFuture<CancellationToken>();
    try (var scheduler = new PrioritySourceScheduler(Runnable::run);
        var pump =
            new BuildRetentionPump(
                r,
                (t, m, d, c) -> {
                  tokenSeen.complete(c);
                  entered.countDown();
                  try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                  return PROOF;
                },
                scheduler,
                tasks)) {
      var a = r.add(target('1'), 1);
      pump.resumeDownloadAll(a);
      var thread = new Thread(tasks.take());
      thread.start();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      pump.withdraw(a.target(), 2);
      assertFalse(tokenSeen.join().isCancellationRequested());
      pump.withdraw(a.target(), 1);
      assertTrue(tokenSeen.join().isCancellationRequested());
      assertEquals(1, r.running);
      assertTrue(r.finishes.isEmpty());
      release.countDown();
      thread.join(5000);
      assertFalse(thread.isAlive());
      assertEquals(1, r.finishes.size());
      assertEquals(State.SUCCEEDED, r.finishes.getFirst().state());
      tasks.drain();
    } finally {
      release.countDown();
    }
  }

  @Test
  void rejectingExecutorCannotLeakRunningLease() {
    for (boolean error : List.of(false, true)) {
      var r = new Repo();
      try (var scheduler = new PrioritySourceScheduler(Runnable::run);
          var pump =
              new BuildRetentionPump(
                  r,
                  (t, m, d, c) -> PROOF,
                  scheduler,
                  task -> {
                    if (error) throw new AssertionError("rejected");
                    throw new RejectedExecutionException("rejected");
                  })) {
        var a = r.add(target('1'), 1);
        pump.resumeDownloadAll(a);
        assertEquals(0, r.running);
        assertTrue(r.finishes.isEmpty());
        var result = pump.completion(a.target(), 1).orElseThrow().toCompletableFuture().join();
        assertEquals(BuildRetentionPump.Outcome.BLOCKED, result.outcome());
        assertTrue(result.failure().isPresent());
      }
    }
  }
}
