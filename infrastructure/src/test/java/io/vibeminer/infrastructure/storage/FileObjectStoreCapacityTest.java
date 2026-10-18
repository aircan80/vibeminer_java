package io.vibeminer.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileObjectStoreCapacityTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  @Test
  void thresholdAndUnknownCapacityRefuseBeforeStageAllocationOrSourceRead() throws Exception {
    for (int scenario = 0; scenario < 3; scenario++) {
      int selected = scenario;
      LongSupplier probe =
          () -> {
            if (selected == 2)
              throw new UncheckedIOException(new IOException("authored probe failure"));
            return selected == 1 ? -1 : 17;
          };
      Path root = directory.resolve("case" + scenario);
      try (var store = new FileObjectStore(root, probe, 10)) {
        var reads = new AtomicInteger();
        InputStream source =
            new ByteArrayInputStream(new byte[8]) {
              @Override
              public int read(byte[] bytes, int offset, int count) {
                reads.incrementAndGet();
                return super.read(bytes, offset, count);
              }
            };
        var failed =
            assertThrows(
                ObjectStoreException.class,
                () -> store.publishVerified(source, 8, 1024, t -> {}, NONE));
        assertEquals(
            scenario == 0
                ? ObjectStoreException.Code.LIMIT_EXCEEDED
                : ObjectStoreException.Code.IO_FAILURE,
            failed.code());
        assertEquals(0, reads.get());
        try (var files = Files.list(root.resolve("work"))) {
          assertEquals(0, files.count());
        }
      }
    }
  }

  @Test
  void exactKnownLengthRatherThanMaximumIsReservedAndRecheckedAtPromotion() throws Exception {
    var probes = new AtomicInteger();
    var available = new AtomicLong(18);
    try (var store =
        new FileObjectStore(
            directory,
            () -> {
              probes.incrementAndGet();
              return available.get();
            },
            10)) {
      var object =
          store.publishVerified(new ByteArrayInputStream(new byte[8]), 8, 1_000_000, t -> {}, NONE);
      assertEquals(8, object.length());
      assertEquals(2, probes.get());
      assertTrue(store.verify(object.digest(), NONE).isPresent());
      available.set(19);
      assertEquals(object, publish(store, 8));
      assertEquals(4, probes.get());
    }
  }

  @Test
  void pressureAtPromotionFailsClosedThenHealthyRetryUsesReleasedReservation() throws Exception {
    var free = new AtomicLong(18);
    try (var store = new FileObjectStore(directory, free::get, 10)) {
      var failed =
          assertThrows(
              ObjectStoreException.class,
              () ->
                  store.publishVerified(
                      new ByteArrayInputStream(new byte[8]), 8, 8, t -> free.set(17), NONE));
      assertEquals(ObjectStoreException.Code.LIMIT_EXCEEDED, failed.code());
      try (var files = Files.list(directory.resolve("work"))) {
        assertEquals(0, files.count());
      }
      try (var files = Files.walk(directory.resolve("objects"))) {
        assertEquals(0, files.filter(Files::isRegularFile).count());
      }
      free.set(18);
      assertEquals(
          8,
          store
              .publishVerified(new ByteArrayInputStream(new byte[8]), 8, 8, t -> {}, NONE)
              .length());
    }
  }

  @Test
  void ignoredCancellationDoesNotReleaseAnotherActualWritersReservation() throws Exception {
    var canceled = new AtomicBoolean();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var worker = Executors.newSingleThreadExecutor();
    var store = new FileObjectStore(directory, () -> 30, 10);
    try {
      var first =
          worker.submit(
              () ->
                  store.publishVerified(
                      held(10, entered, release), 10, 10, t -> {}, canceled::get));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals(
          ObjectStoreException.Code.LIMIT_EXCEEDED,
          assertThrows(ObjectStoreException.class, () -> publish(store, 11)).code());
      canceled.set(true);
      assertFalse(first.isDone());
      assertEquals(
          ObjectStoreException.Code.BUSY,
          assertThrows(ObjectStoreException.class, store::close).code());
      assertEquals(
          ObjectStoreException.Code.LIMIT_EXCEEDED,
          assertThrows(ObjectStoreException.class, () -> publish(store, 11)).code());
      release.countDown();
      assertInstanceOf(
          CancellationException.class,
          assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS)).getCause());
      assertEquals(11, publish(store, 11).length());
    } finally {
      release.countDown();
      worker.shutdown();
      assertTrue(
          worker.awaitTermination(5, TimeUnit.SECONDS), "Actual blocked source still owns store");
      store.close();
    }
  }

  @Test
  void reservationAndHeadroomOverflowAreRefusedWithoutReadingOrLeaking() throws Exception {
    try (var store = new FileObjectStore(directory, () -> Long.MAX_VALUE, 1)) {
      var reads = new AtomicInteger();
      var source =
          new InputStream() {
            public int read() {
              reads.incrementAndGet();
              return -1;
            }
          };
      assertEquals(
          ObjectStoreException.Code.LIMIT_EXCEEDED,
          assertThrows(
                  ObjectStoreException.class,
                  () ->
                      store.publishVerified(source, Long.MAX_VALUE, Long.MAX_VALUE, t -> {}, NONE))
              .code());
      assertEquals(0, reads.get());
      assertEquals(1, publish(store, 1).length());
    }
  }

  @Test
  void concurrentOverflowKeepsTheOriginalReservationUntilActualFinally() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var canceled = new AtomicBoolean();
    var worker = Executors.newSingleThreadExecutor();
    var store = new FileObjectStore(directory, () -> Long.MAX_VALUE, 1);
    try {
      var first =
          worker.submit(
              () ->
                  store.publishVerified(
                      held(1, entered, release),
                      Long.MAX_VALUE - 1,
                      Long.MAX_VALUE - 1,
                      t -> {},
                      canceled::get));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals(
          ObjectStoreException.Code.LIMIT_EXCEEDED,
          assertThrows(ObjectStoreException.class, () -> publish(store, 2)).code());
      canceled.set(true);
      release.countDown();
      assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS));
      assertEquals(2, publish(store, 2).length());
    } finally {
      release.countDown();
      worker.shutdown();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
      store.close();
    }
  }

  @Test
  void failedForeignCompletionAndAtomicMoveBothReleaseActualReservations() throws Exception {
    var rejectMove = new AtomicBoolean();
    try (var store =
        new FileObjectStore(
            directory,
            (stage, target, replace) -> {
              if (rejectMove.get()) throw new IOException("authored promotion I/O failure");
              Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            },
            () -> 18,
            10)) {
      var foreign = new IllegalStateException("authored completion failure");
      assertSame(
          foreign,
          assertThrows(
              IllegalStateException.class,
              () ->
                  store.publishVerified(
                      new ByteArrayInputStream(new byte[8]),
                      8,
                      8,
                      t -> {
                        throw foreign;
                      },
                      NONE)));
      rejectMove.set(true);
      assertEquals(
          ObjectStoreException.Code.IO_FAILURE,
          assertThrows(ObjectStoreException.class, () -> publish(store, 8)).code());
      rejectMove.set(false);
      assertEquals(8, publish(store, 8).length());
    }
  }

  @Test
  void unknownPromotionObservationFailsIoAndReleasesForHealthyRetry() throws Exception {
    var unavailable = new AtomicBoolean();
    try (var store =
        new FileObjectStore(
            directory,
            () -> {
              if (unavailable.get())
                throw new UncheckedIOException(new IOException("authored unavailable volume"));
              return 18;
            },
            10)) {
      var failed =
          assertThrows(
              ObjectStoreException.class,
              () ->
                  store.publishVerified(
                      new ByteArrayInputStream(new byte[8]),
                      8,
                      8,
                      t -> unavailable.set(true),
                      NONE));
      assertEquals(ObjectStoreException.Code.IO_FAILURE, failed.code());
      assertNotNull(failed.getCause());
      unavailable.set(false);
      assertEquals(8, publish(store, 8).length());
    }
  }

  @Test
  void repairUsesTheSameStagingGuardAndPreservesCorruptOldBytesWhenCapacityDrops()
      throws Exception {
    byte[] bytes = new byte[8];
    var free = new AtomicLong(18);
    try (var store = new FileObjectStore(directory, free::get, 10)) {
      var original = publish(store, 8);
      Path target =
          directory
              .resolve("objects")
              .resolve(original.digest().value().substring(0, 2))
              .resolve(original.digest().value().substring(2));
      Files.write(target, new byte[] {9, 9, 9});
      var failed =
          assertThrows(
              ObjectStoreException.class,
              () ->
                  store.repairVerified(
                      new ByteArrayInputStream(bytes), original, 8, t -> free.set(17), NONE));
      assertEquals(ObjectStoreException.Code.LIMIT_EXCEEDED, failed.code());
      assertArrayEquals(new byte[] {9, 9, 9}, Files.readAllBytes(target));
      free.set(18);
      assertEquals(
          original,
          store.repairVerified(new ByteArrayInputStream(bytes), original, 8, t -> {}, NONE));
      assertArrayEquals(bytes, Files.readAllBytes(target));
    }
  }

  @Test
  void checksumPublicationUsesActualExpectedLengthAndSharesTheCapacityGuard() throws Exception {
    byte[] bytes = new byte[8];
    String sha =
        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    try (var store = new FileObjectStore(directory, () -> 18, 10)) {
      assertEquals(
          8,
          store
              .publish(
                  new ByteArrayInputStream(bytes),
                  new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, sha, 8),
                  1_000_000,
                  NONE)
              .length());
    }
  }

  @Test
  void sharedConsumersUseOnlyOneActualStagingReservation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var probes = new AtomicInteger();
    var calls = new AtomicInteger();
    var worker = Executors.newFixedThreadPool(2);
    var store =
        new FileObjectStore(
            directory,
            () -> {
              probes.incrementAndGet();
              return 18;
            },
            10);
    var identity =
        new ResolvedSourceIdentity("authored-capacity", "same-source", "whole-sha256-v1");
    var shared =
        new io.vibeminer.application.acquisition.CoalescingAcquisitionService(
            store,
            (source, token) -> {
              calls.incrementAndGet();
              var object = store.publishVerified(held(8, entered, release), 8, 8, t -> {}, token);
              return new VerifiedSourceAlias(
                  identity,
                  object,
                  new SourceVerificationEvidence(
                      "whole-sha256-v1",
                      "https://fixture.example.invalid/authored",
                      java.time.Instant.EPOCH));
            },
            worker);
    try {
      var first = shared.acquire(identity);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var survivor = shared.acquire(identity);
      first.cancel();
      release.countDown();
      assertEquals(
          8,
          survivor.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).object().length());
      assertEquals(1, calls.get());
      assertEquals(2, probes.get());
      try (var objects = Files.walk(directory.resolve("objects"))) {
        assertEquals(1, objects.filter(Files::isRegularFile).count());
      }
    } finally {
      release.countDown();
      shared.close();
      worker.shutdown();
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
      store.close();
    }
  }

  static StoredObject publish(FileObjectStore store, int length) {
    return store.publishVerified(
        new ByteArrayInputStream(new byte[length]), length, length, t -> {}, NONE);
  }

  static InputStream held(int length, CountDownLatch entered, CountDownLatch release) {
    return new ByteArrayInputStream(new byte[length]) {
      boolean started;

      @Override
      public int read(byte[] output, int offset, int maximum) {
        if (!started) {
          started = true;
          entered.countDown();
          boolean interrupted = false;
          while (true)
            try {
              release.await();
              break;
            } catch (InterruptedException ignored) {
              interrupted = true;
            }
          if (interrupted) Thread.currentThread().interrupt();
        }
        return super.read(output, offset, maximum);
      }
    };
  }
}
