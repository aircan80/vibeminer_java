package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataReuseRuntimeLifecycleTest {
  @TempDir Path directory;
  final BuildSnapshot build =
      new BuildSnapshot(
          "wow",
          "Fixture",
          "Published",
          "us",
          "1.0",
          "a".repeat(32),
          "b".repeat(32),
          Instant.EPOCH,
          true);

  @Test
  void closeWaitsForActualLocalReplayLeaseBeforeReleasingStore() throws Exception {
    byte[] bytes =
        "retained exact configuration fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    StoredObject object;
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      object =
          store.publish(
              new ByteArrayInputStream(bytes),
              new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, digest, bytes.length),
              bytes.length,
              CancellationToken.none());
      var builds = new SqliteBuildObjectCatalog(catalog, store);
      builds.registerSnapshot(build);
      builds.attachVerifiedObject(
          build.snapshotId(),
          "BUILD_CONFIG",
          new ResolvedSourceIdentity(
              "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1"),
          object,
          new SourceVerificationEvidence(
              "tact-config-raw-md5-v1", "https://cdn.example/config", Instant.EPOCH),
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
            (store, backend) -> backend,
            (store, real) ->
                new MetadataReuseGateway() {
                  public MetadataDeclaration readDeclaration(
                      BuildSnapshot b,
                      VerifiedSourceAlias config,
                      MetadataStructureAttestation.Kind kind,
                      LookupBudgets budget,
                      CancellationToken token) {
                    assertTrue(
                        Thread.currentThread().getName().startsWith("vibeminer-metadata-reuse-"));
                    try (var lease = store.openRead(object.digest(), CancellationToken.none())) {
                      entered.countDown();
                      boolean done = false;
                      while (!done) {
                        try {
                          assertTrue(release.await(5, TimeUnit.SECONDS));
                          done = true;
                        } catch (InterruptedException cancelled) {
                          interrupted.countDown();
                        }
                      }
                      assertArrayEquals(bytes, lease.stream().readAllBytes());
                    } catch (java.io.IOException failure) {
                      throw new AssertionError(failure);
                    } finally {
                      leaseClosed.countDown();
                    }
                    throw new CancellationException("Synthetic local replay retired");
                  }

                  public void validateDeclaration(
                      MetadataDeclaration d, LookupBudgets b, CancellationToken t) {
                    throw new AssertionError("No implicit validation");
                  }

                  public io.vibeminer.domain.content.MetadataContentProof replay(
                      MetadataReusePlan p, LookupBudgets b, CancellationToken t) {
                    throw new AssertionError("No implicit apply");
                  }
                });
    CompletableFuture<Void> closing = null;
    try {
      var request =
          runtime
              .metadataReuse()
              .planRetained(
                  build, MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())
              .toCompletableFuture();
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
  void decoratorFailureReleasesStoreAndRuntimeCanRetry() {
    assertThrows(
        IllegalStateException.class,
        () ->
            ContentRuntime.open(
                directory,
                RetentionRuntimeTest.ForbiddenHttp::new,
                (store, backend) -> backend,
                (store, gateway) -> {
                  throw new IllegalStateException("Synthetic metadata construction failure");
                }));
    try (var reopened = new FileObjectStore(directory.resolve("content"))) {
      assertNotNull(reopened);
    }
    try (var runtime = ContentRuntime.open(directory, RetentionRuntimeTest.ForbiddenHttp::new)) {
      assertNotNull(runtime.metadataReuse());
    }
  }
}
