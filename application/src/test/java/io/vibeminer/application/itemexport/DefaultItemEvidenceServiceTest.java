package io.vibeminer.application.itemexport;

import static io.vibeminer.application.itemexport.ItemEvidenceContractsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DefaultItemEvidenceServiceTest {
  static final String DESTINATION =
      java.nio.file.Path.of("evidence.json").toAbsolutePath().normalize().toString();
  final List<Runnable> queued = new ArrayList<>();
  final AtomicInteger writes = new AtomicInteger();
  final AtomicReference<io.vibeminer.application.CancellationToken> writeToken =
      new AtomicReference<>();
  final ItemEvidenceReceipt receipt =
      new ItemEvidenceReceipt(10, "a".repeat(64), NOW.minusSeconds(10));

  ItemEvidenceSnapshotBackend backend =
      (selection, token) -> {
        token.throwIfCancellationRequested();
        return new ItemEvidenceSnapshot(
            selection, row(), Optional.empty(), NOW, NOW, "a".repeat(64));
      };
  ItemEvidenceWriter writer =
      writer(
          (snapshot, path, token) -> {
            writes.incrementAndGet();
            writeToken.set(token);
            token.throwIfCancellationRequested();
            return receipt;
          });

  @FunctionalInterface
  interface Publication {
    ItemEvidenceReceipt publish(
        ItemEvidenceSnapshot snapshot,
        String path,
        io.vibeminer.application.CancellationToken token);
  }

  ItemEvidenceWriter writer(Publication publication) {
    return new ItemEvidenceWriter() {
      @Override
      public void validateDestination(String destination) {
        try {
          var parsed = java.nio.file.Path.of(destination);
          if (!parsed.isAbsolute()
              || parsed.getFileName() == null
              || !parsed.normalize().equals(parsed))
            throw new ItemEvidenceException(
                ItemEvidenceException.Code.INVALID_REQUEST, "Invalid lexical path");
        } catch (java.nio.file.InvalidPathException invalid) {
          throw new ItemEvidenceException(
              ItemEvidenceException.Code.INVALID_REQUEST, "Invalid path", invalid);
        }
      }

      @Override
      public ItemEvidenceReceipt publish(
          ItemEvidenceSnapshot snapshot,
          String path,
          io.vibeminer.application.CancellationToken token) {
        return publication.publish(snapshot, path, token);
      }
    };
  }

  Throwable failure(CompletionStage<?> stage) {
    return assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join())
        .getCause();
  }

  ItemEvidenceException.Code code(CompletionStage<?> stage) {
    return assertInstanceOf(ItemEvidenceException.class, failure(stage)).code();
  }

  @Test
  void directExecutorIssuesSnapshotAndSavesWithoutReverificationOrClampedCommitTime() {
    var captures = new AtomicInteger();
    try (var service =
        new DefaultItemEvidenceService(
            (selection, token) -> {
              captures.incrementAndGet();
              return backend.capture(selection, token);
            },
            writer,
            Runnable::run)) {
      var captured = service.capture(selection(false)).toCompletableFuture().join();
      assertEquals(
          receipt, service.save(captured, DESTINATION).completion().toCompletableFuture().join());
      assertEquals(1, captures.get());
      assertEquals(1, writes.get());
    }
  }

  @Test
  void captureAdmissionRevokesOldButRejectedAndBusyRequestsDoNot() {
    var reject = new AtomicBoolean();
    Executor executor =
        task -> {
          if (reject.get()) throw new RejectedExecutionException();
          queued.add(task);
        };
    try (var service = new DefaultItemEvidenceService(backend, writer, executor)) {
      var initial = service.capture(selection(false));
      queued.removeFirst().run();
      var old = initial.toCompletableFuture().join();
      reject.set(true);
      assertEquals(ItemEvidenceException.Code.BUSY, code(service.capture(selection(false))));
      reject.set(false);
      var saving = service.save(old, DESTINATION);
      queued.removeFirst().run();
      assertEquals(receipt, saving.completion().toCompletableFuture().join());
      var next = service.capture(selection(false)).toCompletableFuture();
      assertEquals(ItemEvidenceException.Code.BUSY, code(service.capture(selection(false))));
      next.cancel(false);
      queued.removeFirst().run();
      assertEquals(
          ItemEvidenceException.Code.STALE_SELECTION,
          code(service.save(old, DESTINATION).completion()));
    }
  }

  @Test
  void observationFutureCancellationCannotEraseAuthoritativeSaveOutcome() {
    try (var service = new DefaultItemEvidenceService(backend, writer, queued::add)) {
      var captured = service.capture(selection(false));
      queued.removeFirst().run();
      var saving = service.save(captured.toCompletableFuture().join(), DESTINATION);
      var observer = saving.completion().toCompletableFuture();
      observer.cancel(true);
      queued.removeFirst().run();
      assertEquals(receipt, saving.completion().toCompletableFuture().join());
      assertEquals(1, writes.get());
    }
  }

  @Test
  void cancellationAfterWriterCommitPreservesReceiptAndCapacityUntilActualRetirement()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ItemEvidenceWriter ignoring =
        writer(
            (snapshot, path, token) -> {
              writeToken.set(token);
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              return receipt;
            });
    try (var executor = Executors.newSingleThreadExecutor();
        var service = new DefaultItemEvidenceService(backend, ignoring, executor)) {
      var saving =
          service
              .capture(selection(false))
              .thenApply(snapshot -> service.save(snapshot, DESTINATION))
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      saving.requestCancellation();
      assertTrue(writeToken.get().isCancellationRequested());
      assertEquals(ItemEvidenceException.Code.BUSY, code(service.capture(selection(false))));
      assertFalse(saving.completion().toCompletableFuture().isDone());
      release.countDown();
      assertEquals(receipt, saving.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void cancellationBeforeWriterStartsDoesNotCallWriter() {
    try (var service = new DefaultItemEvidenceService(backend, writer, queued::add)) {
      var captured = service.capture(selection(false));
      queued.removeFirst().run();
      var saving = service.save(captured.toCompletableFuture().join(), DESTINATION);
      saving.requestCancellation();
      queued.removeFirst().run();
      assertInstanceOf(CancellationException.class, failure(saving.completion()));
      assertEquals(0, writes.get());
    }
  }

  @Test
  void alteredBackendSelectionNeverIssuesSnapshot() {
    ItemEvidenceSnapshotBackend changed =
        (selection, token) ->
            new ItemEvidenceSnapshot(
                new ItemEvidenceSelection(selection.item(), 26, Optional.empty()),
                new io.vibeminer.domain.item.ItemPhysicalRow(26, 0, 0, row().values()),
                Optional.empty(),
                NOW,
                NOW,
                "a".repeat(64));
    try (var service = new DefaultItemEvidenceService(changed, writer, Runnable::run)) {
      assertEquals(ItemEvidenceException.Code.CORRUPT, code(service.capture(selection(false))));
      assertEquals(
          ItemEvidenceException.Code.STALE_SELECTION,
          code(service.save(snapshot(), DESTINATION).completion()));
    }
  }

  @Test
  void admittedCaptureFailureNeverRestoresPreviousSnapshotAndInvalidInputDoesNotRevoke() {
    var fail = new AtomicBoolean();
    ItemEvidenceSnapshotBackend controlled =
        (selection, token) -> {
          if (fail.get())
            throw new ItemEvidenceException(ItemEvidenceException.Code.MISSING_LOCAL, "missing");
          return backend.capture(selection, token);
        };
    try (var service = new DefaultItemEvidenceService(controlled, writer, Runnable::run)) {
      var old = service.capture(selection(false)).toCompletableFuture().join();
      assertEquals(ItemEvidenceException.Code.INVALID_REQUEST, code(service.capture(null)));
      assertEquals(
          receipt, service.save(old, DESTINATION).completion().toCompletableFuture().join());
      fail.set(true);
      assertEquals(
          ItemEvidenceException.Code.MISSING_LOCAL, code(service.capture(selection(false))));
      assertEquals(
          ItemEvidenceException.Code.STALE_SELECTION,
          code(service.save(old, DESTINATION).completion()));
    }
  }

  @Test
  void changedSnapshotCannotBorrowIssuedAuthorization() {
    try (var service = new DefaultItemEvidenceService(backend, writer, Runnable::run)) {
      var original = service.capture(selection(false)).toCompletableFuture().join();
      var changed =
          new ItemEvidenceSnapshot(
              original.selection(),
              original.row(),
              original.name(),
              NOW,
              NOW.plusSeconds(1),
              original.generationFingerprint());
      assertEquals(
          ItemEvidenceException.Code.STALE_SELECTION,
          code(service.save(changed, DESTINATION).completion()));
      assertEquals(0, writes.get());
      assertEquals(
          receipt, service.save(original, DESTINATION).completion().toCompletableFuture().join());
    }
  }

  @Test
  void uncertainWriterOutcomeIsPreservedAfterCancellation() {
    var active = new AtomicReference<ItemEvidenceService.SaveOperation>();
    var uncertain =
        new ItemEvidenceException(ItemEvidenceException.Code.COMMIT_UNCERTAIN, "unknown");
    ItemEvidenceWriter unknown =
        writer(
            (snapshot, path, token) -> {
              active.get().requestCancellation();
              throw uncertain;
            });
    try (var service = new DefaultItemEvidenceService(backend, unknown, queued::add)) {
      var capture = service.capture(selection(false));
      queued.removeFirst().run();
      var save = service.save(capture.toCompletableFuture().join(), DESTINATION);
      active.set(save);
      queued.removeFirst().run();
      assertSame(uncertain, failure(save.completion()));
    }
  }

  @Test
  void closeDoesNotHideCommittedOutcomeOrReleaseIgnoredWriterEarly() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ItemEvidenceWriter ignoring =
        writer(
            (snapshot, path, token) -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              return receipt;
            });
    try (var executor = Executors.newSingleThreadExecutor();
        var service = new DefaultItemEvidenceService(backend, ignoring, executor)) {
      var saving =
          service
              .capture(selection(false))
              .thenApply(snapshot -> service.save(snapshot, DESTINATION))
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      service.close();
      assertFalse(saving.completion().toCompletableFuture().isDone());
      assertEquals(ItemEvidenceException.Code.CLOSED, code(service.capture(selection(false))));
      release.countDown();
      assertEquals(receipt, saving.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void portsAndCompletionCallbacksNeverRunWhileHoldingServiceMonitor() {
    var owner = new AtomicReference<DefaultItemEvidenceService>();
    ItemEvidenceSnapshotBackend reentrant =
        (selection, token) -> {
          try {
            assertEquals(
                ItemEvidenceException.Code.BUSY,
                CompletableFuture.supplyAsync(() -> code(owner.get().capture(selection)))
                    .get(10, TimeUnit.SECONDS));
          } catch (Exception failed) {
            throw new AssertionError(failed);
          }
          return backend.capture(selection, token);
        };
    try (var service = new DefaultItemEvidenceService(reentrant, writer, queued::add)) {
      owner.set(service);
      var capture = service.capture(selection(false));
      var callback =
          capture.thenRun(
              () -> {
                try {
                  CompletableFuture.runAsync(service::close).get(10, TimeUnit.SECONDS);
                } catch (Exception failed) {
                  throw new AssertionError(failed);
                }
              });
      queued.removeFirst().run();
      callback.toCompletableFuture().join();
    }
  }

  @Test
  void invalidLexicalDestinationFailsBeforeAdmissionAndPreservesSnapshot() {
    try (var service = new DefaultItemEvidenceService(backend, writer, Runnable::run)) {
      var captured = service.capture(selection(false)).toCompletableFuture().join();
      var absolute = java.nio.file.Path.of("evidence.json").toAbsolutePath().normalize();
      var parent = absolute.getParent();
      for (var invalid :
          List.of(
              "relative.json",
              parent.resolve(".").resolve("evidence.json").toString(),
              parent.resolve("nested").resolve("..").resolve("evidence.json").toString())) {
        assertEquals(
            ItemEvidenceException.Code.INVALID_REQUEST,
            code(service.save(captured, invalid).completion()),
            invalid);
        assertEquals(0, writes.get());
      }
      assertEquals(
          receipt,
          service.save(captured, absolute.toString()).completion().toCompletableFuture().join());
      assertEquals(1, writes.get());
    }
  }

  @Test
  void completedCaptureCallbackCanImmediatelyAdmitSave() {
    try (var service = new DefaultItemEvidenceService(backend, writer, queued::add)) {
      var saved =
          service
              .capture(selection(false))
              .thenCompose(snapshot -> service.save(snapshot, DESTINATION).completion());
      queued.removeFirst().run();
      assertEquals(1, queued.size(), "Capture completion must observe actual source retirement");
      queued.removeFirst().run();
      assertEquals(receipt, saved.toCompletableFuture().join());
    }
  }

  @Test
  void captureCompletionCanIssueNewSnapshotWithoutOldCallbackRevocation() {
    try (var service = new DefaultItemEvidenceService(backend, writer, queued::add)) {
      var first = service.capture(selection(false)).toCompletableFuture();
      var replacement = first.thenCompose(ignored -> service.capture(selection(false)));
      queued.removeFirst().run();
      assertEquals(1, queued.size());
      queued.removeFirst().run();
      var current = replacement.toCompletableFuture().join();
      assertFalse(first.cancel(false));
      var saved = service.save(current, DESTINATION);
      queued.removeFirst().run();
      assertEquals(receipt, saved.completion().toCompletableFuture().join());
    }
  }

  @Test
  void failedCaptureCompletionCanImmediatelyAdmitRetry() {
    var calls = new AtomicInteger();
    ItemEvidenceSnapshotBackend controlled =
        (selection, token) -> {
          if (calls.getAndIncrement() == 0)
            throw new ItemEvidenceException(
                ItemEvidenceException.Code.MISSING_LOCAL, "first attempt");
          return backend.capture(selection, token);
        };
    try (var service = new DefaultItemEvidenceService(controlled, writer, queued::add)) {
      var retried =
          service
              .capture(selection(false))
              .handle((snapshot, failure) -> service.capture(selection(false)))
              .thenCompose(stage -> stage);
      queued.removeFirst().run();
      assertEquals(1, queued.size());
      queued.removeFirst().run();
      assertEquals(selection(false), retried.toCompletableFuture().join().selection());
    }
  }
}
