package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemNamesRuntimeLifecycleTest {
  @TempDir Path directory;

  @Test
  void closeWaitsForActualProjectionLeaseBeforeReleasingStore() throws Exception {
    byte[] bytes = "synthetic item worker lease".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    StoredObject object;
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      object =
          store.publish(
              new ByteArrayInputStream(bytes),
              new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, digest, bytes.length),
              bytes.length,
              CancellationToken.none());
    }
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var leaseClosed = new CountDownLatch(1);
    var runtime =
        ContentRuntime.open(
            directory,
            RetentionRuntimeTest.ForbiddenHttp::new,
            (store, item) -> item,
            (store, metadata) -> metadata,
            (store, real) ->
                (ItemNamesBackend)
                    Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ItemNamesBackend.class},
                        (proxy, method, args) -> {
                          if (!method.getName().equals("prepare"))
                            throw new AssertionError("No implicit query");
                          assertTrue(
                              Thread.currentThread().getName().startsWith("vibeminer-item-names-"));
                          try (var lease =
                              store.openRead(object.digest(), CancellationToken.none())) {
                            entered.countDown();
                            boolean done = false;
                            while (!done) {
                              try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                done = true;
                              } catch (InterruptedException cancellation) {
                                interrupted.countDown();
                              }
                            }
                            assertArrayEquals(bytes, lease.stream().readAllBytes());
                          } finally {
                            leaseClosed.countDown();
                          }
                          throw new CancellationException("Synthetic projection retired");
                        }));
    CompletableFuture<Void> closing = null;
    try {
      var request =
          runtime.itemNames().prepare(ItemNamesRuntimeSeed.request()).toCompletableFuture();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      closing = CompletableFuture.runAsync(runtime::close);
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      assertTrue(request.isCancelled());
      assertFalse(closing.isDone());
      assertThrows(
          ObjectStoreException.class, () -> new FileObjectStore(directory.resolve("content")));
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertEquals(0, leaseClosed.getCount());
      try (var reopened = new FileObjectStore(directory.resolve("content"))) {
        assertNotNull(reopened);
      }
    } finally {
      release.countDown();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      runtime.close();
    }
  }

  @Test
  void decoratorFailureClosesStoreAndDoesNotPublishRuntime() {
    assertThrows(
        IllegalStateException.class,
        () ->
            ContentRuntime.open(
                directory,
                RetentionRuntimeTest.ForbiddenHttp::new,
                (store, item) -> item,
                (store, metadata) -> metadata,
                (store, backend) -> {
                  throw new IllegalStateException("Synthetic backend construction failure");
                }));
    try (var reopened = new FileObjectStore(directory.resolve("content"))) {
      assertNotNull(reopened);
    }
    try (var runtime = ContentRuntime.open(directory, RetentionRuntimeTest.ForbiddenHttp::new)) {
      assertNotNull(runtime.itemNames());
    }
  }
}
