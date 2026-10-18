package io.vibeminer.application.buildreadiness;

import static io.vibeminer.application.buildoverview.BuildOverview.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.discoveryhistory.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class BuildReadinessServicesTest {
  static BuildLibraryReference reference() {
    var key =
        new BuildReferenceGroupKey(
            "wow", "Published", "authored", "a".repeat(32), "b".repeat(32), "", "", Map.of());
    var versions =
        new DiscoveryEventSummary.DocumentReceipt(
            "https://us.version.battle.net/v2/products/wow/versions",
            Instant.EPOCH,
            "a".repeat(64),
            1);
    var cdns =
        new DiscoveryEventSummary.DocumentReceipt(
            "https://us.version.battle.net/v2/products/wow/cdns", Instant.EPOCH, "b".repeat(64), 1);
    return new BuildLibraryReference(
        key,
        UUID.randomUUID(),
        1,
        key.snapshotIdFor("us"),
        0,
        "us",
        Instant.EPOCH,
        versions,
        cdns,
        new DiscoveryEvent.CdnRow(
            0, Map.of("Name", "us", "Path", "tpr/wow", "Hosts", "authored.invalid")));
  }

  static BuildReadiness.Context context() {
    return new BuildReadiness.Context(reference(), "enUS", "metadata-v1", "inventory-v1");
  }

  static Request request() {
    var c = context();
    return new Request(
        c.reference(),
        c.locale(),
        c.metadataScopeRevision(),
        new Limits(100, 10000, 128, 100000, Duration.ofSeconds(30)));
  }

  static Snapshot partial(Request r) {
    return new Snapshot(
        r.reference(),
        r.locale(),
        r.scopeRevision(),
        "a".repeat(64),
        Instant.EPOCH,
        MetadataState.READY,
        InventoryState.UNKNOWN,
        Readiness.PARTIAL,
        r.target(),
        Measurements.unknown(),
        Optional.empty(),
        List.of());
  }

  static Snapshot finish(Request r, BuildOverviewBackend.ProgressSink sink) {
    var value = partial(r);
    sink.update(new Progress(Stage.COMPLETE, 0, OptionalLong.empty(), 0, Optional.of(value)));
    return value;
  }

  static final class QueueExecutor implements Executor {
    final ArrayDeque<Runnable> jobs = new ArrayDeque<>();

    public void execute(Runnable job) {
      jobs.add(job);
    }

    void runOne() {
      jobs.remove().run();
    }
  }

  static class Backend implements BuildReadinessBackend {
    int reads, records;
    CompletedAssessment saved;
    boolean failRecord;

    public List<BuildReadiness.Status> read(
        List<BuildReadiness.Context> values, CancellationToken token) {
      reads++;
      return values.stream()
          .map(
              c ->
                  new BuildReadiness.Status(
                      c, BuildReadiness.AdmissionPresence.PRESENT, Optional.empty()))
          .toList();
    }

    public void record(CompletedAssessment value, CancellationToken token) {
      records++;
      token.throwIfCancellationRequested();
      if (failRecord) throw new IllegalStateException("Authored SQL failure");
      saved = value;
    }
  }

  @Test
  void overlayIsExplicitBoundedSqlReadAndRejectsDuplicatesBeforeIo() {
    var backend = new Backend();
    try (var service = new DefaultBuildReadinessService(backend, Runnable::run)) {
      var c = context();
      var result = service.read(List.of(c)).completion().toCompletableFuture().join();
      assertEquals(1, backend.reads);
      assertEquals(c, result.getFirst().context());
      assertTrue(result.getFirst().lastChecked().isEmpty());
      assertThrows(
          CompletionException.class,
          () -> service.read(List.of(c, c)).completion().toCompletableFuture().join());
      assertEquals(1, backend.reads);
      assertThrows(
          CompletionException.class,
          () ->
              service.read(Collections.nCopies(101, c)).completion().toCompletableFuture().join());
      assertEquals(1, backend.reads);
    }
  }

  @Test
  void queuedCancellationHoldsActualFourSlotsUntilRunnableRetires() {
    var backend = new Backend();
    var executor = new QueueExecutor();
    try (var service = new DefaultBuildReadinessService(backend, executor)) {
      var operations = new ArrayList<BuildReadinessService.Operation>();
      for (int i = 0; i < 4; i++) operations.add(service.read(List.of(context())));
      operations.getFirst().requestCancellation();
      assertThrows(
          CompletionException.class,
          () -> service.read(List.of(context())).completion().toCompletableFuture().join());
      executor.runOne();
      assertTrue(
          operations.getFirst().completion().toCompletableFuture().isCompletedExceptionally());
      assertEquals(0, backend.reads);
      service.read(List.of(context()));
      assertEquals(4, executor.jobs.size());
      while (!executor.jobs.isEmpty()) executor.runOne();
    }
  }

  @Test
  void overlayRejectsSubstitutedContextAndFatalExecutorDoesNotLeakCapacity() {
    var backend =
        new Backend() {
          public List<BuildReadiness.Status> read(
              List<BuildReadiness.Context> v, CancellationToken t) {
            return super.read(List.of(context()), t);
          }
        };
    try (var service = new DefaultBuildReadinessService(backend, Runnable::run)) {
      assertThrows(
          CompletionException.class,
          () -> service.read(List.of(context())).completion().toCompletableFuture().join());
    }
    var calls = new AtomicInteger();
    Executor executor =
        job -> {
          if (calls.getAndIncrement() == 0) throw new AssertionError("Authored fatal admission");
          job.run();
        };
    try (var service = new DefaultBuildReadinessService(new Backend(), executor)) {
      assertThrows(
          CompletionException.class,
          () -> service.read(List.of(context())).completion().toCompletableFuture().join());
      for (int i = 0; i < 8; i++)
        service.read(List.of(context())).completion().toCompletableFuture().join();
    }
  }

  @Test
  void recorderWaitsForActualSuccessfulOverviewAndItsOwnSqlRetirement() {
    var backend = new Backend();
    var delegates = new QueueExecutor();
    var recordings = new QueueExecutor();
    try (var actual = new DefaultBuildOverviewService((r, p, t) -> finish(r, p), delegates);
        var wrapper =
            new RecordingBuildOverviewService(actual, backend, recordings, "inventory-v1")) {
      var request = request();
      var operation = wrapper.read(request);
      assertEquals(0, backend.records);
      delegates.runOne();
      assertEquals(1, recordings.jobs.size());
      assertFalse(operation.completion().toCompletableFuture().isDone());
      assertNotEquals(Stage.COMPLETE, operation.progress().stage());
      assertEquals(0, backend.records);
      recordings.runOne();
      assertEquals(partial(request), operation.completion().toCompletableFuture().join());
      assertEquals(1, backend.records);
      assertEquals(Stage.COMPLETE, operation.progress().stage());
      assertEquals(request.reference(), backend.saved.context().reference());
      assertEquals(request.reference().eventId(), backend.saved.observation().origin().eventId());
    }
  }

  @Test
  void lateOverviewFailureNeverMintsCompletedAssessment() {
    var backend = new Backend();
    try (var actual =
            new DefaultBuildOverviewService(
                (r, p, t) -> {
                  finish(r, p);
                  throw new IllegalStateException("Authored final cleanup failure");
                },
                Runnable::run);
        var wrapper =
            new RecordingBuildOverviewService(actual, backend, Runnable::run, "inventory-v1")) {
      assertThrows(
          CompletionException.class,
          () -> wrapper.read(request()).completion().toCompletableFuture().join());
      assertEquals(0, backend.records);
    }
  }

  @Test
  void recordingFailurePreservesActualSnapshotWithoutInventingSavedObservation() {
    var backend = new Backend();
    backend.failRecord = true;
    try (var actual = new DefaultBuildOverviewService((r, p, t) -> finish(r, p), Runnable::run);
        var wrapper =
            new RecordingBuildOverviewService(actual, backend, Runnable::run, "inventory-v1")) {
      var request = request();
      assertEquals(
          partial(request), wrapper.read(request).completion().toCompletableFuture().join());
      assertEquals(1, backend.records);
      assertNull(backend.saved);
    }
  }

  @Test
  void canceledQueuedRecordsHoldFourWrapperSlotsAndNeverPersist() {
    var backend = new Backend();
    var recordings = new QueueExecutor();
    try (var actual = new DefaultBuildOverviewService((r, p, t) -> finish(r, p), Runnable::run);
        var wrapper =
            new RecordingBuildOverviewService(actual, backend, recordings, "inventory-v1")) {
      var operations = new ArrayList<BuildOverviewService.Operation>();
      for (int i = 0; i < 4; i++) operations.add(wrapper.read(request()));
      operations.getFirst().requestCancellation();
      assertThrows(
          CompletionException.class,
          () -> wrapper.read(request()).completion().toCompletableFuture().join());
      recordings.runOne();
      assertEquals(0, backend.records);
      wrapper.read(request());
      while (!recordings.jobs.isEmpty()) recordings.runOne();
      assertTrue(
          operations.getFirst().completion().toCompletableFuture().isCompletedExceptionally());
    }
  }

  @Test
  void closingWrapperDoesNotCloseBorrowedDelegateOrExecutor() {
    var backend = new Backend();
    var executor = new QueueExecutor();
    try (var actual = new DefaultBuildOverviewService((r, p, t) -> finish(r, p), Runnable::run)) {
      var wrapper = new RecordingBuildOverviewService(actual, backend, executor, "inventory-v1");
      wrapper.close();
      var request = request();
      assertEquals(
          partial(request), actual.read(request).completion().toCompletableFuture().join());
      assertThrows(
          CompletionException.class,
          () -> wrapper.read(request).completion().toCompletableFuture().join());
      executor.execute(() -> {});
      executor.runOne();
    }
  }

  @Test
  void fatalRecorderFailureRetiresWrapperAndFailsItsFuture() {
    var backend =
        new Backend() {
          public void record(CompletedAssessment a, CancellationToken t) {
            throw new AssertionError("Authored fatal recorder");
          }
        };
    var records = new QueueExecutor();
    try (var actual = new DefaultBuildOverviewService((r, p, t) -> finish(r, p), Runnable::run);
        var wrapper = new RecordingBuildOverviewService(actual, backend, records, "inventory-v1")) {
      for (int i = 0; i < 8; i++) {
        var operation = wrapper.read(request());
        assertThrows(AssertionError.class, records::runOne);
        assertThrows(
            CompletionException.class, () -> operation.completion().toCompletableFuture().join());
        assertNotEquals(Stage.COMPLETE, operation.progress().stage());
      }
    }
  }

  @Test
  void fatalRecordingExecutorRejectionDoesNotLeakOrForgeSavedStatus() {
    var backend = new Backend();
    var admissions = new AtomicInteger();
    Executor executor =
        job -> {
          if (admissions.getAndIncrement() == 0)
            throw new AssertionError("Authored fatal recorder admission");
          job.run();
        };
    try (var actual = new DefaultBuildOverviewService((r, p, t) -> finish(r, p), Runnable::run);
        var wrapper =
            new RecordingBuildOverviewService(actual, backend, executor, "inventory-v1")) {
      assertThrows(
          CompletionException.class,
          () -> wrapper.read(request()).completion().toCompletableFuture().join());
      assertEquals(0, backend.records);
      for (int i = 0; i < 8; i++) wrapper.read(request()).completion().toCompletableFuture().join();
      assertEquals(8, backend.records);
    }
  }

  static BuildOverviewService heldDelegate(
      List<CompletableFuture<Snapshot>> futures, AtomicInteger cancelCalls, Runnable beforeReturn) {
    return new BuildOverviewService() {
      public Operation read(Request r) {
        var future = new CompletableFuture<Snapshot>();
        futures.add(future);
        beforeReturn.run();
        return new Operation() {
          public CompletionStage<Snapshot> completion() {
            return future.minimalCompletionStage();
          }

          public Progress progress() {
            return Progress.initial();
          }

          public void requestCancellation() {
            cancelCalls.incrementAndGet();
            throw new IllegalStateException("Authored cancellation failure");
          }
        };
      }

      public void close() {
        throw new AssertionError("Borrowed delegate must stay open");
      }
    };
  }

  @Test
  void cancellationThrowBeforeCallbackAttachmentCannotPrematurelyRetire() {
    var backend = new Backend();
    var futures = new ArrayList<CompletableFuture<Snapshot>>();
    var canceled = new AtomicInteger();
    var ref = new AtomicReference<RecordingBuildOverviewService>();
    var delegate = heldDelegate(futures, canceled, () -> ref.get().close());
    var wrapper =
        new RecordingBuildOverviewService(delegate, backend, Runnable::run, "inventory-v1");
    ref.set(wrapper);
    var request = request();
    var operation = wrapper.read(request);
    assertEquals(1, canceled.get());
    assertFalse(operation.completion().toCompletableFuture().isDone());
    futures.getFirst().complete(partial(request));
    assertThrows(
        CompletionException.class, () -> operation.completion().toCompletableFuture().join());
    assertEquals(0, backend.records);
  }

  @Test
  void closeAttemptsEveryCancellationEvenWhenDelegateCancelThrows() {
    var backend = new Backend();
    var futures = new ArrayList<CompletableFuture<Snapshot>>();
    var canceled = new AtomicInteger();
    var delegate = heldDelegate(futures, canceled, () -> {});
    var wrapper =
        new RecordingBuildOverviewService(delegate, backend, Runnable::run, "inventory-v1");
    var requests = new ArrayList<Request>();
    var operations = new ArrayList<BuildOverviewService.Operation>();
    for (int i = 0; i < 4; i++) {
      var request = request();
      requests.add(request);
      operations.add(wrapper.read(request));
    }
    assertDoesNotThrow(wrapper::close);
    assertEquals(4, canceled.get());
    for (var operation : operations)
      assertFalse(operation.completion().toCompletableFuture().isDone());
    for (int i = 0; i < 4; i++) futures.get(i).complete(partial(requests.get(i)));
    for (var operation : operations)
      assertThrows(
          CompletionException.class, () -> operation.completion().toCompletableFuture().join());
    assertEquals(0, backend.records);
  }

  @Test
  void ignoredActualOverviewCancellationHoldsFourWrappersThroughRealFinally() throws Exception {
    var backend = new Backend();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor();
        var actual =
            new DefaultBuildOverviewService(
                (r, p, t) -> {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                  return finish(r, p);
                },
                executor);
        var wrapper =
            new RecordingBuildOverviewService(actual, backend, Runnable::run, "inventory-v1")) {
      try {
        var operations = new ArrayList<BuildOverviewService.Operation>();
        for (int i = 0; i < 4; i++) operations.add(wrapper.read(request()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        for (var operation : operations) operation.requestCancellation();
        assertFalse(operations.getFirst().completion().toCompletableFuture().isDone());
        assertThrows(
            CompletionException.class,
            () -> wrapper.read(request()).completion().toCompletableFuture().join());
        release.countDown();
        for (var operation : operations)
          assertThrows(
              ExecutionException.class,
              () -> operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(0, backend.records);
        wrapper.read(request()).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, backend.records);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void internalReceiptHasNoPublicConstructorOrDtoFactory() {
    assertEquals(0, CompletedAssessment.class.getConstructors().length);
    assertTrue(
        Arrays.stream(CompletedAssessment.class.getMethods())
            .noneMatch(m -> m.getReturnType() == CompletedAssessment.class));
  }
}
