package io.vibeminer.application.buildlibrary;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.discoveryhistory.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

class DefaultBuildLibraryServiceTest {
  static final Instant NOW = BuildLibraryPolicyTest.NOW;
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final BuildLibraryQuery QUERY =
      new BuildLibraryQuery("wow", BuildLibraryProjection.Filter.ALL);

  @Test
  void oneProductCaptureFollowsDurableAdmissionAndPreservesEachActualRegionalRegistration() {
    var fake = new Fake();
    var calls = new AtomicInteger();
    var batch = batch("us", "eu");
    try (var service =
        service(
            (product, token) -> {
              assertEquals(List.of("begin-product", "known-scopes"), fake.log);
              calls.incrementAndGet();
              return batch;
            },
            fake,
            Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertEquals(1, calls.get());
      assertEquals(2, result.registrations().size());
      assertTrue(result.failures().isEmpty());
      assertEquals(
          List.of("us", "eu"),
          result.registrations().stream()
              .map(r -> r.check().orElseThrow().scope().region())
              .toList());
      for (var registration : result.registrations())
        assertEquals(result.ticket().ordinal(), registration.check().orElseThrow().ordinal());
      assertEquals(2, fake.history.size());
      assertFalse(result.cancellationRequested());
    }
  }

  @Test
  void libraryFailureAfterHistoryCommitIsPreciseAndDoesNotEraseEarlierRegion() {
    var fake = new Fake();
    var cause =
        new BuildLibraryException(
            BuildLibraryException.Code.STORAGE_UNAVAILABLE, "library unavailable");
    fake.registerFailure.put("eu", cause);
    try (var service = service((p, t) -> batch("us", "eu"), fake, Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertEquals(1, result.registrations().size());
      assertEquals("us", result.registrations().getFirst().check().orElseThrow().scope().region());
      assertEquals(2, fake.history.size());
      assertEquals(
          BuildLibraryRefreshResult.Phase.LIBRARY_REGISTRATION,
          result.failures().getFirst().phase());
      assertSame(cause, result.failures().getFirst().cause());
      assertEquals(List.of("eu:FAILED"), fake.failedScopes);
    }
  }

  @Test
  void cancellationAfterFirstRegistrationPreservesCommitAndNeverStartsSecondAppend() {
    var fake = new Fake();
    var queue = new Queue();
    var operation = new AtomicReference<BuildLibraryService.RefreshOperation>();
    fake.afterRegistration = registration -> operation.get().requestCancellation();
    try (var service = service((p, t) -> batch("us", "eu"), fake, queue)) {
      var refresh = service.refreshProduct("wow");
      operation.set(refresh);
      refresh.completion().toCompletableFuture().cancel(true);
      queue.run();
      var result = refresh.completion().toCompletableFuture().join();
      assertEquals(1, result.registrations().size());
      assertEquals(1, fake.history.size());
      assertTrue(result.cancellationRequested());
      assertEquals(List.of("eu:CANCELLED"), fake.failedScopes);
    }
  }

  @Test
  void cancellationAfterHistoryCommitStillProjectsThatRegionBeforeRetirement() {
    var operation = new AtomicReference<BuildLibraryService.RefreshOperation>();
    var fake =
        new Fake() {
          @Override
          public DiscoveryEvent append(
              DiscoveryEvent event, DiscoveryHistoryLimits limits, CancellationToken token) {
            var committed = super.append(event, limits, token);
            operation.get().requestCancellation();
            return committed;
          }
        };
    var queue = new Queue();
    try (var service = service((p, t) -> batch("us", "eu"), fake, queue)) {
      var refresh = service.refreshProduct("wow");
      operation.set(refresh);
      queue.run();
      var result = refresh.completion().toCompletableFuture().join();
      assertEquals(1, result.registrations().size());
      assertEquals("us", result.registrations().getFirst().check().orElseThrow().scope().region());
      assertEquals(1, fake.history.size());
      assertTrue(result.cancellationRequested());
      assertEquals(List.of("eu:CANCELLED"), fake.failedScopes);
    }
  }

  @Test
  void wholeCaptureFailureMarksOnlyDurableKnownScopesAtActualRetirement() throws Exception {
    var fake = new Fake();
    fake.known = List.of(new BuildLibraryScope("wow", "us"));
    var sourceFailure = new CompletionException(new java.io.IOException("remote retrieval failed"));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var queue = new Queue();
    var service =
        service(
            (p, t) -> {
              entered.countDown();
              await(release);
              throw sourceFailure;
            },
            fake,
            queue);
    var refresh = service.refreshProduct("wow");
    var actual = new Thread(queue::run);
    actual.start();
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      refresh.requestCancellation();
      assertTrue(fake.failedScopes.isEmpty());
      assertFalse(refresh.completion().toCompletableFuture().isDone());
      release.countDown();
      actual.join(2000);
      assertFalse(actual.isAlive());
      var failed =
          assertThrows(
              CompletionException.class, () -> refresh.completion().toCompletableFuture().join());
      assertSame(sourceFailure.getCause(), failed.getCause());
      assertEquals(List.of("us:CANCELLED"), fake.failedScopes);
    } finally {
      release.countDown();
      actual.join(2000);
      service.close();
    }
  }

  @Test
  void missingPriorRegionAndActualEmptyBatchNeverClaimPublicationAbsence() {
    var fake = new Fake();
    fake.known = List.of(new BuildLibraryScope("wow", "kr"));
    try (var service = service((p, t) -> batch("us"), fake, Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertEquals(1, result.registrations().size());
      assertEquals(List.of("kr:FAILED"), fake.failedScopes);
      assertEquals(
          BuildLibraryRefreshResult.Phase.CAPTURE_INTERPRETATION,
          result.failures().getFirst().phase());
    }
    var empty = new Fake();
    empty.known = List.of(new BuildLibraryScope("wow", "us"));
    try (var service = service((p, t) -> batch(), empty, Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertTrue(result.registrations().isEmpty());
      assertFalse(result.failures().isEmpty());
      assertEquals(List.of("us:FAILED"), empty.failedScopes);
    }
  }

  @Test
  void pageAndSelectionAreOfflineAndStrictlyContextual() {
    var fake = new Fake();
    try (var service =
        service(
            (p, t) -> {
              throw new AssertionError("Reads cannot retrieve product");
            },
            fake,
            Runnable::run)) {
      assertTrue(
          service
              .page(QUERY, Optional.empty(), 25)
              .toCompletableFuture()
              .join()
              .entries()
              .isEmpty());
      var selected = BuildLibraryReadContractsTest.entry(1);
      fake.selected = selected;
      assertSame(
          selected,
          service.select(selected.row().group().key().identity()).toCompletableFuture().join());
      assertCode(BuildLibraryException.Code.CORRUPT_LIBRARY, service.select("f".repeat(64)));
    }
  }

  @Test
  void fourQueuedJobsKeepCapacityAndCallbacksRunAfterActualSlotRetirement() throws Exception {
    var fake = new Fake();
    var queue = new Queue();
    try (var service = service((p, t) -> batch("us"), fake, queue)) {
      var first = service.refreshProduct("wow");
      for (int i = 0; i < 3; i++) service.refreshProduct("wow");
      assertCode(BuildLibraryException.Code.BUSY, service.refreshProduct("wow").completion());
      var callback = new CompletableFuture<Void>();
      first
          .completion()
          .whenComplete(
              (value, failure) -> {
                try {
                  var next =
                      CompletableFuture.supplyAsync(() -> service.page(QUERY, Optional.empty(), 25))
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
  void historyFailureDoesNotInventRegistrationAndCheckRecordingFailureKeepsBothCauses() {
    var historyFailure =
        new DiscoveryHistoryException(
            DiscoveryHistoryException.Code.QUOTA_EXCEEDED, "history quota");
    var checkFailure =
        new BuildLibraryException(
            BuildLibraryException.Code.STORAGE_UNAVAILABLE, "check write failed");
    var fake =
        new Fake() {
          public DiscoveryEvent append(
              DiscoveryEvent event, DiscoveryHistoryLimits limits, CancellationToken token) {
            if (event.region().equals("eu")) throw historyFailure;
            return super.append(event, limits, token);
          }

          public BuildLibraryRegionalState recordFailedScope(
              BuildLibraryCheckTicket ticket,
              Failure failure,
              Instant at,
              CancellationToken token) {
            throw checkFailure;
          }
        };
    try (var service = service((p, t) -> batch("us", "eu"), fake, Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertEquals(1, result.registrations().size());
      assertEquals(1, fake.history.size());
      assertEquals(
          List.of(
              BuildLibraryRefreshResult.Phase.HISTORY_APPEND,
              BuildLibraryRefreshResult.Phase.CHECK_RECORDING),
          result.failures().stream().map(BuildLibraryRefreshResult.ScopeFailure::phase).toList());
      assertSame(historyFailure, result.failures().getFirst().cause());
      assertSame(checkFailure, result.failures().getLast().cause());
    }
  }

  @Test
  void regionalInterpretationFailureAndNewerSuccessfulCheckAreNotRewritten() {
    var fake =
        new Fake() {
          public BuildLibraryRegionalState recordFailedScope(
              BuildLibraryCheckTicket ticket,
              Failure failure,
              Instant at,
              CancellationToken token) {
            failedScopes.add(ticket.scope().region() + ":" + failure);
            return new BuildLibraryRegionalState(
                ticket.scope(),
                BuildLibraryRegionalState.CheckState.SUCCESS,
                Optional.of(UUID.randomUUID()),
                Set.of("a".repeat(64)),
                Optional.of(at));
          }
        };
    var valid = batch("us");
    var partial =
        new BuildLibraryProductObservation(
            valid.productId(),
            valid.completedAt(),
            valid.parserRevision(),
            valid.versions(),
            valid.cdns(),
            valid.events(),
            List.of(
                new BuildLibraryProductObservation.RegionalFailure(
                    new BuildLibraryScope("wow", "eu"),
                    DiscoveryHistoryException.Code.UNSUPPORTED_INTERPRETATION,
                    "Unsupported regional row")),
            Optional.of("Regional interpretation incomplete"));
    try (var service = service((p, t) -> partial, fake, Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertEquals(1, result.registrations().size());
      assertEquals(1, result.failures().size());
      assertEquals(
          BuildLibraryRefreshResult.Phase.CAPTURE_INTERPRETATION,
          result.failures().getFirst().phase());
      assertEquals(
          DiscoveryHistoryException.Code.UNSUPPORTED_INTERPRETATION,
          assertInstanceOf(DiscoveryHistoryException.class, result.failures().getFirst().cause())
              .code());
      assertEquals(partial.diagnostic(), result.observation().diagnostic());
    }
  }

  @Test
  void wrongChildAdmissionOrderNeverReachesHistoryAndActualClosedCommitIsPreserved() {
    var fake =
        new Fake() {
          public BuildLibraryCheckTicket deriveRegionalCheck(
              BuildLibraryProductCheckTicket parent, String region, CancellationToken token) {
            return new BuildLibraryCheckTicket(
                UUID.randomUUID(),
                new BuildLibraryScope(parent.productId(), region),
                parent.ordinal() + 1,
                parent.requestedAt());
          }
        };
    try (var service = service((p, t) -> batch("us"), fake, Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertTrue(fake.history.isEmpty());
      assertTrue(result.registrations().isEmpty());
      assertEquals(
          BuildLibraryRefreshResult.Phase.CHECK_RECORDING, result.failures().getFirst().phase());
    }
    var committed = new Fake();
    var owner = new AtomicReference<DefaultBuildLibraryService>();
    committed.afterRegistration = r -> owner.get().close();
    var service = service((p, t) -> batch("us"), committed, Runnable::run);
    owner.set(service);
    var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
    assertEquals(1, result.registrations().size());
    assertTrue(result.cancellationRequested());
    assertTrue(committed.failedScopes.isEmpty());
  }

  @Test
  void cancelledQueuedWorkAndRejectedOrInvalidAdmissionPerformNoProductCheck() {
    var fake = new Fake();
    var queue = new Queue();
    var service =
        service(
            (p, t) -> {
              throw new AssertionError();
            },
            fake,
            queue);
    var first = service.refreshProduct("wow");
    first.requestCancellation();
    service.close();
    queue.run();
    assertThrows(CompletionException.class, () -> first.completion().toCompletableFuture().join());
    assertTrue(fake.log.isEmpty());
    assertCode(BuildLibraryException.Code.CLOSED, service.refreshProduct("wow").completion());
    try (var rejected =
        service(
            (p, t) -> {
              throw new AssertionError();
            },
            fake,
            job -> {
              throw new RejectedExecutionException();
            })) {
      assertCode(
          BuildLibraryException.Code.INVALID_REQUEST,
          rejected.refreshProduct("../wow").completion());
      for (int i = 0; i < 6; i++)
        assertCode(BuildLibraryException.Code.BUSY, rejected.refreshProduct("wow").completion());
      assertTrue(fake.log.isEmpty());
    }
  }

  @Test
  void terminalReadPageCannotIgnoreCursorPositionOrRevision() {
    var entry = BuildLibraryReadContractsTest.entry(5);
    var cursor =
        new BuildLibraryCursor(QUERY.identity(), 1, 5, entry.row().group().key().identity(), 25);
    var fake =
        new Fake() {
          public BuildLibraryPage page(
              BuildLibraryQuery query,
              Optional<BuildLibraryCursor> position,
              int size,
              CancellationToken token) {
            return new BuildLibraryPage(query, size, 1, List.of(entry), Optional.empty());
          }
        };
    try (var service =
        service(
            (p, t) -> {
              throw new AssertionError();
            },
            fake,
            Runnable::run)) {
      assertCode(
          BuildLibraryException.Code.CORRUPT_LIBRARY, service.page(QUERY, Optional.of(cursor), 25));
      var stale =
          new BuildLibraryCursor(QUERY.identity(), 2, 6, entry.row().group().key().identity(), 25);
      assertCode(
          BuildLibraryException.Code.STALE_CURSOR, service.page(QUERY, Optional.of(stale), 25));
    }
  }

  @Test
  void sameEventIdWithChangedReferenceFactsCannotMasqueradeAsCommittedRegistration() {
    var fake =
        new Fake() {
          public BuildLibraryRegistration recordObservation(
              UUID eventId,
              BuildLibraryRegistration.Kind kind,
              Optional<BuildLibraryCheckTicket> check,
              Instant at,
              CancellationToken token) {
            var changed = BuildLibraryPolicyTest.event("us", "c".repeat(32), "changed", NOW);
            changed =
                new DiscoveryEvent(
                    eventId,
                    changed.productId(),
                    changed.region(),
                    changed.completedAt(),
                    changed.parserRevision(),
                    changed.versions(),
                    changed.cdns(),
                    changed.selectedCdn(),
                    changed.builds());
            return new BuildLibraryRegistration(
                eventId, kind, check, at, BuildLibraryReference.from(changed, 1));
          }
        };
    try (var service = service((p, t) -> batch("us"), fake, Runnable::run)) {
      var result = service.refreshProduct("wow").completion().toCompletableFuture().join();
      assertEquals(1, fake.history.size());
      assertTrue(result.registrations().isEmpty());
      assertEquals(
          BuildLibraryRefreshResult.Phase.LIBRARY_REGISTRATION,
          result.failures().getFirst().phase());
      assertEquals(
          BuildLibraryException.Code.CORRUPT_LIBRARY,
          assertInstanceOf(BuildLibraryException.class, result.failures().getFirst().cause())
              .code());
    }
  }

  @Test
  void wholeCaptureFailurePreservesPrimaryCauseAndSuppressedFailedCheckWrite() {
    var sourceFailure = new IllegalStateException("source retrieval failed");
    var cleanupFailure =
        new BuildLibraryException(
            BuildLibraryException.Code.STORAGE_UNAVAILABLE, "failed-check write failed");
    var fake =
        new Fake() {
          public BuildLibraryRegionalState recordFailedScope(
              BuildLibraryCheckTicket check, Failure failure, Instant at, CancellationToken token) {
            throw cleanupFailure;
          }
        };
    fake.known = List.of(new BuildLibraryScope("wow", "us"));
    try (var service =
        service(
            (p, t) -> {
              throw sourceFailure;
            },
            fake,
            Runnable::run)) {
      var failed =
          assertThrows(
              CompletionException.class,
              () -> service.refreshProduct("wow").completion().toCompletableFuture().join());
      assertSame(sourceFailure, failed.getCause());
      assertArrayEquals(new Throwable[] {cleanupFailure}, sourceFailure.getSuppressed());
      assertTrue(fake.history.isEmpty());
    }
  }

  @Test
  void oversizedPriorAndCapturedScopeUnionIsRefusedBeforeAnyPartialAppend() {
    var regions = new String[64];
    for (int i = 0; i < 64; i++) regions[i] = "" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
    var fake = new Fake();
    fake.known = List.of(new BuildLibraryScope("wow", "zz"));
    var calls = new AtomicInteger();
    try (var service =
        service(
            (p, t) -> {
              calls.incrementAndGet();
              return batch(regions);
            },
            fake,
            Runnable::run)) {
      var failure =
          assertThrows(
              CompletionException.class,
              () -> service.refreshProduct("wow").completion().toCompletableFuture().join());
      var limit = assertInstanceOf(BuildLibraryException.class, failure.getCause());
      assertEquals(BuildLibraryException.Code.LIMIT_EXCEEDED, limit.code());
      var evidence =
          assertInstanceOf(
              DefaultBuildLibraryService.CapturedObservationEvidence.class, limit.getCause());
      assertEquals(64, evidence.observation().events().size());
      assertEquals("wow", evidence.observation().productId());
      assertEquals(1, calls.get());
      assertTrue(fake.history.isEmpty());
      assertEquals(List.of("zz:FAILED"), fake.failedScopes);
    }
  }

  static DefaultBuildLibraryService service(
      BuildLibraryProductGateway gateway, Fake fake, Executor executor) {
    return new DefaultBuildLibraryService(gateway, fake, fake, fake, fake, executor, CLOCK);
  }

  static BuildLibraryProductObservation batch(String... regions) {
    var events =
        Arrays.stream(regions)
            .map(region -> BuildLibraryPolicyTest.event(region, "a".repeat(32), "", NOW))
            .toList();
    var source =
        events.isEmpty()
            ? BuildLibraryPolicyTest.event("us", "a".repeat(32), "", NOW)
            : events.getFirst();
    return new BuildLibraryProductObservation(
        "wow",
        NOW,
        source.parserRevision(),
        source.versions(),
        source.cdns(),
        events,
        List.of(),
        Optional.empty());
  }

  static final class Queue implements Executor {
    final java.util.Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    public void execute(Runnable work) {
      tasks.add(work);
    }

    void run() {
      Objects.requireNonNull(tasks.poll()).run();
    }
  }

  static void await(CountDownLatch release) {
    try {
      assertTrue(release.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException failed) {
      throw new AssertionError(failed);
    }
  }

  static void assertCode(BuildLibraryException.Code code, CompletionStage<?> stage) {
    var failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertEquals(code, assertInstanceOf(BuildLibraryException.class, failure.getCause()).code());
  }

  static class Fake
      implements DiscoveryHistoryRepository,
          BuildLibraryRepository,
          BuildLibraryProductChecks,
          BuildLibraryReader {
    final Map<UUID, DiscoveryEvent> history = new LinkedHashMap<>();
    final List<String> log = Collections.synchronizedList(new ArrayList<>()),
        failedScopes = Collections.synchronizedList(new ArrayList<>());
    final Map<String, RuntimeException> registerFailure = new HashMap<>();
    List<BuildLibraryScope> known = List.of();
    Consumer<BuildLibraryRegistration> afterRegistration = registration -> {};
    long ordinal;
    BuildLibraryPage.Entry selected;

    public BuildLibraryProductCheckTicket beginProductCheck(
        String product, Instant requestedAt, CancellationToken token) {
      token.throwIfCancellationRequested();
      log.add("begin-product");
      return new BuildLibraryProductCheckTicket(UUID.randomUUID(), product, ++ordinal, requestedAt);
    }

    public List<BuildLibraryScope> knownScopes(String product, CancellationToken token) {
      log.add("known-scopes");
      return known;
    }

    public BuildLibraryCheckTicket deriveRegionalCheck(
        BuildLibraryProductCheckTicket parent, String region, CancellationToken token) {
      return new BuildLibraryCheckTicket(
          UUID.randomUUID(),
          new BuildLibraryScope(parent.productId(), region),
          parent.ordinal(),
          parent.requestedAt());
    }

    public DiscoveryEvent append(
        DiscoveryEvent event, DiscoveryHistoryLimits limits, CancellationToken token) {
      token.throwIfCancellationRequested();
      history.put(event.eventId(), event);
      return event;
    }

    public BuildLibraryRegistration recordObservation(
        UUID eventId,
        BuildLibraryRegistration.Kind kind,
        Optional<BuildLibraryCheckTicket> check,
        Instant recordedAt,
        CancellationToken token) {
      token.throwIfCancellationRequested();
      var event = history.get(eventId);
      var failure = registerFailure.get(event.region());
      if (failure != null) throw failure;
      var result =
          new BuildLibraryRegistration(
              eventId, kind, check, recordedAt, BuildLibraryReference.from(event, history.size()));
      afterRegistration.accept(result);
      return result;
    }

    public BuildLibraryRegionalState recordFailedScope(
        BuildLibraryCheckTicket check, Failure failure, Instant at, CancellationToken token) {
      assertFalse(token.isCancellationRequested());
      failedScopes.add(check.scope().region() + ":" + failure);
      return new BuildLibraryRegionalState(
          check.scope(),
          failure == Failure.CANCELLED
              ? BuildLibraryRegionalState.CheckState.CANCELLED
              : BuildLibraryRegionalState.CheckState.FAILED,
          Optional.empty(),
          Set.of(),
          Optional.of(at));
    }

    public BuildLibraryPage page(
        BuildLibraryQuery query,
        Optional<BuildLibraryCursor> cursor,
        int size,
        CancellationToken token) {
      return new BuildLibraryPage(query, size, 1, List.of(), Optional.empty());
    }

    public BuildLibraryPage.Entry select(String id, CancellationToken token) {
      return selected;
    }

    public Optional<DiscoveryEvent> read(UUID id, CancellationToken token) {
      return Optional.ofNullable(history.get(id));
    }

    public DiscoveryHistoryPage page(
        DiscoveryHistoryFilter filter,
        Optional<DiscoveryHistoryCursor> cursor,
        int size,
        CancellationToken token) {
      throw new AssertionError();
    }

    public BuildLibraryCheckTicket beginCheck(
        BuildLibraryScope scope, Instant requestedAt, CancellationToken token) {
      throw new AssertionError();
    }

    public Optional<BuildLibrarySeenState> findSeen(String group, CancellationToken token) {
      throw new AssertionError();
    }

    public BuildLibrarySeenState acknowledgeOpen(
        BuildLibraryReference ref, Instant at, CancellationToken token) {
      throw new AssertionError();
    }

    public Optional<BuildLibraryRegionalBaseline> baseline(
        BuildLibraryScope scope, CancellationToken token) {
      throw new AssertionError();
    }

    public BuildLibraryRegionalState regionalState(
        BuildLibraryScope scope, CancellationToken token) {
      throw new AssertionError();
    }
  }
}
