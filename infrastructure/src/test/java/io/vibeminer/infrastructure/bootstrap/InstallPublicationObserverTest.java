package io.vibeminer.infrastructure.bootstrap;

import static io.vibeminer.infrastructure.bootstrap.BootstrapWorkflowIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.DefaultBootstrapService;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.SqliteCanonicalContentRepository;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPublicationObserverTest {
  @TempDir Path directory;

  static class Observations implements MetadataPublicationObserver {
    final List<MetadataContentProof> before = new ArrayList<>(), after = new ArrayList<>();

    public void beforePromotion(MetadataContentProof p, CancellationToken t) {
      before.add(p);
    }

    public void afterStructureValidation(MetadataContentProof p, CancellationToken t) {
      after.add(p);
    }
  }

  @Test
  void freshGuardedApplicationRetainsGrammarAndReopensWithoutAnotherTransfer() throws Exception {
    byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    BootstrapTransfer transfer =
        request -> {
          requests.incrementAndGet();
          byte[] bytes = request.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
          return CompletableFuture.completedFuture(
              new AcquiredBytes(
                  request,
                  NOW,
                  bytes,
                  hash("SHA-256", bytes),
                  request.kind() == CdnRequest.Kind.CONFIG));
        };
    var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var selected = build(config);
    String contentKey;
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      var claims = new SqliteCanonicalContentRepository(catalog, store, store.contentSession());
      var objects = new SqliteBuildObjectCatalog(catalog, store);
      var gateway = new OfficialBootstrapArtifactGateway(store, transfer, Clock.systemUTC());
      try (var service =
          new DefaultBootstrapService(
              gateway, objects, store, Runnable::run, Runnable::run, null, claims)) {
        var plan = service.inspectConfiguration(selected).toCompletableFuture().join();
        contentKey = plan.contentKey();
        var inspection = service.download(plan).toCompletableFuture().join();
        assertFalse(inspection.entries().isEmpty());
        var claim =
            claims
                .lookup(new ContentSlotKey("wow", contentKey), CancellationToken.none())
                .orElseThrow();
        assertEquals(CanonicalReservation.Status.RETAINED, claim.status());
        var proof = assertInstanceOf(MetadataContentProof.class, claim.proof().orElseThrow());
        assertEquals(
            MetadataStructureAttestation.Kind.INSTALL,
            proof.structuralAttestation().orElseThrow().kind());
      }
      assertEquals(2, requests.get());
    }
    try (var store = new FileObjectStore(directory.resolve("content"))) {
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, store.contentSession());
      var objects = new SqliteBuildObjectCatalog(catalog, store);
      var claims = new SqliteCanonicalContentRepository(catalog, store, store.contentSession());
      var offline =
          new OfficialBootstrapArtifactGateway(
              store,
              request -> {
                throw new AssertionError("Offline reopening cannot transfer");
              },
              Clock.systemUTC());
      try (var service =
          new DefaultBootstrapService(
              offline, objects, store, Runnable::run, Runnable::run, null, claims)) {
        assertFalse(service.reopen(selected).toCompletableFuture().join().entries().isEmpty());
        assertEquals(2, requests.get());
      }
    }
  }

  @Test
  void actualParserAttestedBeforeCasAndRetainedReplayPreservesOriginalEvidence() throws Exception {
    byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
    BootstrapTransfer transfer =
        r -> {
          byte[] bytes = r.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
          return CompletableFuture.completedFuture(
              new AcquiredBytes(
                  r, NOW, bytes, hash("SHA-256", bytes), r.kind() == CdnRequest.Kind.CONFIG));
        };
    try (var store = new FileObjectStore(directory)) {
      var gateway = new OfficialBootstrapArtifactGateway(store, transfer, Clock.systemUTC());
      var plan = gateway.inspectConfiguration(build(config), CancellationToken.none());
      var observer =
          new Observations() {
            public void beforePromotion(MetadataContentProof p, CancellationToken t) {
              super.beforePromotion(p, t);
              assertTrue(store.verify(p.object().digest(), t).isEmpty());
              assertTrue(p.decodedAttestation().isPresent());
              assertEquals(
                  MetadataStructureAttestation.Kind.INSTALL,
                  p.structuralAttestation().orElseThrow().kind());
            }
          };
      var alias = gateway.acquireInstall(plan, observer, CancellationToken.none());
      assertEquals(1, observer.before.size());
      assertEquals(alias, observer.before.getFirst().originalAlias());
      var offline =
          new OfficialBootstrapArtifactGateway(
              store,
              r -> {
                throw new AssertionError("HTTP forbidden");
              },
              Clock.systemUTC());
      var replay = new Observations();
      offline.inspectRetained(plan, alias, replay, CancellationToken.none());
      assertTrue(replay.before.isEmpty());
      assertEquals(1, replay.after.size());
      assertEquals(alias, replay.after.getFirst().originalAlias());
    }
  }

  @Test
  void observerRefusalNeverPromotesAndIsConclusive() throws Exception {
    byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
    BootstrapTransfer transfer =
        r -> {
          byte[] bytes = r.kind() == CdnRequest.Kind.CONFIG ? config : encoded;
          return CompletableFuture.completedFuture(
              new AcquiredBytes(
                  r, NOW, bytes, hash("SHA-256", bytes), r.kind() == CdnRequest.Kind.CONFIG));
        };
    try (var store = new FileObjectStore(directory)) {
      var gateway = new OfficialBootstrapArtifactGateway(store, transfer, Clock.systemUTC());
      var plan = gateway.inspectConfiguration(build(config), CancellationToken.none());
      var observer =
          new Observations() {
            public void beforePromotion(MetadataContentProof p, CancellationToken t) {
              super.beforePromotion(p, t);
              throw new IllegalStateException("refused");
            }
          };
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () -> gateway.acquireInstall(plan, observer, CancellationToken.none()));
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertTrue(
          store
              .verify(observer.before.getFirst().object().digest(), CancellationToken.none())
              .isEmpty());
    }
  }

  @Test
  void wrongContentBytesNeverEmitMetadataCapability() throws Exception {
    byte[] decoded = manifest(), encoded = encode(decoded), config = config(decoded, encoded);
    byte[] corrupt = encoded.clone();
    corrupt[corrupt.length - 1] ^= 1;
    BootstrapTransfer transfer =
        r -> {
          byte[] bytes = r.kind() == CdnRequest.Kind.CONFIG ? config : corrupt;
          return CompletableFuture.completedFuture(
              new AcquiredBytes(
                  r, NOW, bytes, hash("SHA-256", bytes), r.kind() == CdnRequest.Kind.CONFIG));
        };
    try (var store = new FileObjectStore(directory)) {
      var gateway = new OfficialBootstrapArtifactGateway(store, transfer, Clock.systemUTC());
      var plan = gateway.inspectConfiguration(build(config), CancellationToken.none());
      var observer = new Observations();
      var failure =
          assertThrows(
              MetadataAcquisitionFailure.class,
              () -> gateway.acquireInstall(plan, observer, CancellationToken.none()));
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure.publication());
      assertTrue(observer.before.isEmpty());
      assertTrue(observer.after.isEmpty());
    }
  }
}
