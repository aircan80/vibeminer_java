package io.vibeminer.infrastructure.buildopening;

import static io.vibeminer.infrastructure.buildopening.OfficialBuildMetadataSourceWorkerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisition.PinnedCdnRangeTransport;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataSourceReadonlyVerificationTest {
  @TempDir Path directory;

  @Test
  void allThreeKindsReplayExactOriginalProofWithoutPublisherOrRepositoryCalls() throws Exception {
    for (var kind :
        List.of(
            MetadataStructureAttestation.Kind.ENCODING,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            MetadataStructureAttestation.Kind.DOWNLOAD_SIZE)) {
      try (var fixture = new Fixture(directory.resolve(kind.name()), kind, metadata(kind))) {
        fixture.worker.acquire(fixture.request, budgets(), NONE);
        var original =
            (MetadataContentProof)
                fixture
                    .canonical
                    .lookup(fixture.request.slot(), NONE)
                    .orElseThrow()
                    .proof()
                    .orElseThrow();
        int requests = fixture.client.requests.get();
        var repository =
            (CanonicalContentRepository)
                java.lang.reflect.Proxy.newProxyInstance(
                    CanonicalContentRepository.class.getClassLoader(),
                    new Class<?>[] {CanonicalContentRepository.class},
                    (proxy, method, args) -> {
                      throw new AssertionError("Readonly repository access: " + method.getName());
                    });
        var store =
            (ObjectStore)
                java.lang.reflect.Proxy.newProxyInstance(
                    ObjectStore.class.getClassLoader(),
                    new Class<?>[] {ObjectStore.class},
                    (proxy, method, args) -> {
                      if (method.getName().equals("publisherStarted")
                          || method.getName().startsWith("publish"))
                        throw new AssertionError("Readonly publisher activity");
                      try {
                        return method.invoke(fixture.store, args);
                      } catch (java.lang.reflect.InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                      }
                    });
        var worker =
            new OfficialBuildMetadataSourceWorker(
                store,
                repository,
                fixture.builds,
                new PinnedCdnRangeTransport(fixture.client, CLOCK, Duration.ofSeconds(2)),
                CLOCK);
        assertSame(original, worker.verifyRetained(fixture.request, original, budgets(), NONE));
        assertEquals(requests, fixture.client.requests.get());
        var weak =
            new MetadataContentProof(
                original.originalAlias(),
                original.actualEncodingKey(),
                original.contentKey(),
                original.declaredDecodedBytes(),
                Optional.empty(),
                Optional.empty());
        assertEquals(
            ContentClaimException.Code.UNSUPPORTED_REUSE,
            assertThrows(
                    ContentClaimException.class,
                    () -> worker.verifyRetained(fixture.request, weak, budgets(), NONE))
                .code());
      }
    }
  }

  @Test
  void authorityLossNeverRecreatesClaimOrFallsBackToHttpWhenBytesDisappear() throws Exception {
    try (var fixture =
        new Fixture(
            directory,
            MetadataStructureAttestation.Kind.DOWNLOAD,
            metadata(MetadataStructureAttestation.Kind.DOWNLOAD))) {
      fixture.worker.acquire(fixture.request, budgets(), NONE);
      var original =
          (MetadataContentProof)
              fixture
                  .canonical
                  .lookup(fixture.request.slot(), NONE)
                  .orElseThrow()
                  .proof()
                  .orElseThrow();
      try (var connection = fixture.sql.connect();
          var statement =
              connection.prepareStatement(
                  "DELETE FROM canonical_content_slots WHERE namespace=? AND content_key=?")) {
        statement.setString(1, fixture.request.slot().namespace());
        statement.setString(2, fixture.request.slot().contentKey());
        assertEquals(1, statement.executeUpdate());
      }
      int requests = fixture.client.requests.get();
      assertSame(
          original, fixture.worker.verifyRetained(fixture.request, original, budgets(), NONE));
      assertTrue(fixture.canonical.lookup(fixture.request.slot(), NONE).isEmpty());
      String digest = original.object().digest().value();
      Files.delete(
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2)));
      assertEquals(
          ObjectStoreException.Code.MISSING_OBJECT,
          assertThrows(
                  ObjectStoreException.class,
                  () -> fixture.worker.verifyRetained(fixture.request, original, budgets(), NONE))
              .code());
      assertEquals(requests, fixture.client.requests.get());
      assertTrue(fixture.canonical.lookup(fixture.request.slot(), NONE).isEmpty());
    }
  }
}
