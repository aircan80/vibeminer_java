package io.vibeminer.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildretention.BuildRetention;
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

class ContentRuntimeQueuedModeLifecycleTest {
  @TempDir Path directory;

  @Test
  void interruptsRunningLegacyLeasesAndDrainsQueuedModeActionsBeforeStoreClose() throws Exception {
    var build =
        new BuildSnapshot(
            "wow",
            "Fixture",
            "Published",
            "us",
            "1",
            "a".repeat(32),
            "b".repeat(32),
            Instant.EPOCH,
            true);
    byte[] bytes = {1, 2, 3};
    StoredObject object;
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      object =
          store.publish(
              new ByteArrayInputStream(bytes),
              new ExpectedObjectChecksum(
                  ChecksumAlgorithm.SHA256,
                  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                  bytes.length),
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
    var entered = new CountDownLatch(2);
    var interrupted = new CountDownLatch(2);
    var releasedLeases = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var runtime =
        ContentRuntime.open(
            directory,
            RetentionRuntimeTest.ForbiddenHttp::new,
            (s, b) -> b,
            (store, real) ->
                new MetadataReuseGateway() {
                  public MetadataDeclaration readDeclaration(
                      BuildSnapshot selected,
                      VerifiedSourceAlias config,
                      MetadataStructureAttestation.Kind kind,
                      LookupBudgets budget,
                      CancellationToken token) {
                    try (var lease = store.openRead(object.digest(), CancellationToken.none())) {
                      entered.countDown();
                      while (true) {
                        try {
                          assertTrue(release.await(5, TimeUnit.SECONDS));
                          break;
                        } catch (InterruptedException stopped) {
                          interrupted.countDown();
                        }
                      }
                      assertArrayEquals(bytes, lease.stream().readAllBytes());
                    } catch (java.io.IOException failed) {
                      throw new AssertionError(failed);
                    } finally {
                      releasedLeases.countDown();
                    }
                    throw new CancellationException("Held replay retired");
                  }

                  public void validateDeclaration(
                      MetadataDeclaration d, LookupBudgets b, CancellationToken t) {
                    throw new AssertionError();
                  }

                  public io.vibeminer.domain.content.MetadataContentProof replay(
                      MetadataReusePlan p, LookupBudgets b, CancellationToken t) {
                    throw new AssertionError();
                  }
                });
    CompletableFuture<Void> closing = null;
    try {
      var first =
          runtime
              .metadataReuse()
              .planRetained(
                  build, MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())
              .toCompletableFuture();
      var second =
          runtime
              .metadataReuse()
              .planRetained(
                  build, MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())
              .toCompletableFuture();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var target =
          new BuildRetention.Target(
              "a".repeat(64),
              "b".repeat(64),
              "c".repeat(64),
              "d".repeat(64),
              1,
              "scope-v1",
              "e".repeat(64));
      var queued = new ArrayList<CompletableFuture<?>>();
      for (int i = 0; i < 4; i++)
        queued.add(runtime.retentionMode().read(target).completion().toCompletableFuture());
      closing = CompletableFuture.runAsync(runtime::close);
      assertTrue(
          interrupted.await(2, TimeUnit.SECONDS),
          "Running legacy actions must receive shutdown interruption");
      assertTrue(first.isCancelled());
      assertTrue(second.isCancelled());
      assertFalse(closing.isDone());
      assertTrue(
          queued.stream().noneMatch(CompletableFuture::isDone),
          "Queued actual wrappers have not retired yet");
      assertThrows(
          ObjectStoreException.class, () -> new FileObjectStore(directory.resolve("content")));
      release.countDown();
      closing.get(5, TimeUnit.SECONDS);
      assertEquals(0, releasedLeases.getCount());
      for (var operation : queued) {
        assertTrue(operation.isCompletedExceptionally());
        var failure = assertThrows(CompletionException.class, operation::join);
        assertInstanceOf(CancellationException.class, failure.getCause());
      }
      try (var reopened = new FileObjectStore(directory.resolve("content"))) {
        assertNotNull(reopened);
      }
    } finally {
      release.countDown();
      if (closing != null) closing.get(5, TimeUnit.SECONDS);
      runtime.close();
    }
  }
}
