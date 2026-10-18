package io.vibeminer.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.ObjectStoreException;
import io.vibeminer.domain.ObjectDigest;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileObjectStoreSessionTest {
  @TempDir Path root;

  @Test
  void sessionRequiresExclusiveOpenAndHasOneEpochPerLifetime() {
    java.util.UUID first;
    try (var store = new FileObjectStore(root)) {
      assertSame(store.contentSession(), store.contentSession());
      first = store.contentSession().epoch();
      assertThrows(ObjectStoreException.class, () -> new FileObjectStore(root));
      assertThrows(
          IllegalStateException.class, () -> store.contentSession().beforeProducerOperation());
      store.contentSession().recoverAtStartup(() -> {});
      store.contentSession().beforeProducerOperation();
      assertEquals(first, store.contentSession().epoch());
    }
    try (var reopened = new FileObjectStore(root)) {
      assertNotEquals(first, reopened.contentSession().epoch());
      reopened.contentSession().recoverAtStartup(() -> {});
    }
  }

  @Test
  void startupIsOneShotAndRejectsCasReentry() {
    try (var store = new FileObjectStore(root)) {
      var calls = new AtomicInteger();
      store
          .contentSession()
          .recoverAtStartup(
              () -> {
                calls.incrementAndGet();
                assertThrows(IllegalStateException.class, store::publisherStarted);
                assertThrows(
                    IllegalStateException.class,
                    () -> store.verify(new ObjectDigest("a".repeat(64)), CancellationToken.none()));
                assertThrows(IllegalStateException.class, store::close);
              });
      assertThrows(
          IllegalStateException.class,
          () -> store.contentSession().recoverAtStartup(calls::incrementAndGet));
      assertEquals(1, calls.get());
      store.contentSession().beforeProducerOperation();
    }
  }

  @Test
  void failedRecoveryFailsClosedUntilRealReopen() {
    FileObjectStore.StoreSession old;
    try (var store = new FileObjectStore(root)) {
      old = store.contentSession();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              old.recoverAtStartup(
                  () -> {
                    throw new IllegalArgumentException("SQL rollback");
                  }));
      assertThrows(IllegalStateException.class, old::beforeProducerOperation);
      assertThrows(IllegalStateException.class, store::publisherStarted);
      assertThrows(IllegalStateException.class, () -> old.recoverAtStartup(() -> {}));
    }
    assertThrows(ObjectStoreException.class, old::beforeProducerOperation);
    assertThrows(ObjectStoreException.class, () -> old.recoverAtStartup(() -> {}));
    try (var reopened = new FileObjectStore(root)) {
      reopened.contentSession().recoverAtStartup(() -> {});
    }
  }

  @Test
  void completedLegacyReadStillForbidsLateRecovery() {
    try (var store = new FileObjectStore(root)) {
      assertTrue(
          store.verify(new ObjectDigest("a".repeat(64)), CancellationToken.none()).isEmpty());
      assertThrows(
          IllegalStateException.class,
          () -> store.contentSession().recoverAtStartup(() -> fail("late fence")));
    }
  }

  @Test
  void legacyNetworkProducerBlocksRecoveryBeforeAnyCasOperation() throws Exception {
    try (var store = new FileObjectStore(root);
        var worker = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var exit = new CountDownLatch(1);
      var pending =
          worker.submit(
              () -> {
                store.publisherStarted();
                entered.countDown();
                try {
                  exit.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertThrows(
            IllegalStateException.class,
            () -> store.contentSession().recoverAtStartup(() -> fail("active HTTP fence")));
      } finally {
        exit.countDown();
        pending.get(3, TimeUnit.SECONDS);
      }
      assertThrows(
          IllegalStateException.class,
          () -> store.contentSession().recoverAtStartup(() -> fail("retired producer fence")));
    }
  }

  @Test
  void readLeasesCannotBypassStartupOrFailedGateEvenForMissingObjects() {
    var digest = new ObjectDigest("a".repeat(64));
    try (var store = new FileObjectStore(root)) {
      assertThrows(
          ObjectStoreException.class, () -> store.openRead(digest, CancellationToken.none()));
      assertThrows(
          IllegalStateException.class,
          () -> store.contentSession().recoverAtStartup(() -> fail("late read fence")));
    }
    try (var store = new FileObjectStore(root)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store
                  .contentSession()
                  .recoverAtStartup(
                      () -> {
                        assertThrows(
                            IllegalStateException.class,
                            () -> store.openRead(digest, CancellationToken.none()));
                        throw new IllegalArgumentException("failed transaction");
                      }));
      assertThrows(
          IllegalStateException.class, () -> store.openRead(digest, CancellationToken.none()));
    }
  }

  @Test
  void producerCannotEnterWhileStartupTransactionIsBlocked() throws Exception {
    try (var store = new FileObjectStore(root);
        var workers = Executors.newFixedThreadPool(2)) {
      var entered = new CountDownLatch(1);
      var exit = new CountDownLatch(1);
      var producerAttempted = new CountDownLatch(1);
      var recovered =
          workers.submit(
              () ->
                  store
                      .contentSession()
                      .recoverAtStartup(
                          () -> {
                            entered.countDown();
                            try {
                              exit.await();
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                          }));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      var producer =
          workers.submit(
              () -> {
                producerAttempted.countDown();
                store.contentSession().beforeProducerOperation();
              });
      try {
        assertTrue(producerAttempted.await(3, TimeUnit.SECONDS));
        assertFalse(producer.isDone());
      } finally {
        exit.countDown();
      }
      recovered.get(3, TimeUnit.SECONDS);
      producer.get(3, TimeUnit.SECONDS);
    }
  }
}
