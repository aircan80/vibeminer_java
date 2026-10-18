package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.content.CanonicalContentRepository;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.bootstrap.OfficialBootstrapArtifactGateway;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.discoveryhistory.SqliteDiscoveryHistoryRepository;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitialMetadataOpeningIntegrationTest {
  @TempDir Path directory;

  @Test
  void explicitNewOpenContinuesNeverAdmittedMissingOriginalThenReopensSevenRolesOffline()
      throws Exception {
    try (var f = new OfficialBuildOpeningGatewayIntegrationTest.Fixture(directory)) {
      var interrupted = new AtomicReference<MetadataContentProof>();
      var actual = f.canonical;
      var observed =
          (CanonicalContentRepository)
              Proxy.newProxyInstance(
                  CanonicalContentRepository.class.getClassLoader(),
                  new Class<?>[] {CanonicalContentRepository.class},
                  (proxy, method, args) -> {
                    Object result;
                    try {
                      result = method.invoke(actual, args);
                    } catch (InvocationTargetException wrapped) {
                      throw wrapped.getCause();
                    }
                    if (method.getName().equals("recordVerifiedCompletion")
                        && args[1] instanceof MetadataContentProof proof
                        && interrupted.compareAndSet(null, proof))
                      throw new CancellationException(
                          "Authored cancellation after durable prospective commit");
                    return result;
                  });
      var clock = OfficialBuildOpeningGatewayIntegrationTest.CLOCK;
      var whole = new PinnedCdnTransport(f.client, clock, Duration.ofSeconds(2));
      var ranges = new PinnedCdnRangeTransport(f.client, clock, Duration.ofSeconds(2));
      var catalog = new SqliteBuildObjectCatalog(f.sql, f.store);
      var metadata =
          new OfficialBuildMetadataSourceWorker(f.store, observed, catalog, ranges, clock);
      var installs = new OfficialBootstrapArtifactGateway(f.store, whole, clock);
      try (var gateway =
          new OfficialBuildOpeningGateway(
              new SqliteDiscoveryHistoryRepository(f.sql),
              f.admissions,
              f.store,
              catalog,
              observed,
              metadata,
              installs,
              f.roots,
              whole,
              f.sourcePool,
              f.scheduler,
              BuildOpeningPolicy.initialCohort(),
              () -> Long.MAX_VALUE)) {
        assertThrows(
            RuntimeException.class,
            () -> gateway.open(f.reference, "enUS", p -> {}, CancellationToken.none()));
        var original = interrupted.get();
        assertNotNull(original);
        assertTrue(f.store.verify(original.object().digest(), CancellationToken.none()).isEmpty());
        assertTrue(
            f.admissions
                .find(
                    f.reference,
                    "enUS",
                    BuildOpeningPolicy.SCOPE_REVISION,
                    CancellationToken.none())
                .isEmpty());
        var admission = gateway.open(f.reference, "enUS", p -> {}, CancellationToken.none());
        assertEquals(7, admission.required().size());
        var canonical =
            f.canonical
                .lookup(
                    new io.vibeminer.domain.selectedfile.ContentSlotKey(
                        "wow", original.contentKey()),
                    CancellationToken.none())
                .orElseThrow();
        assertEquals(
            original.originalAlias(),
            ((MetadataContentProof) canonical.proof().orElseThrow()).originalAlias());
        f.client.reject = true;
        assertEquals(
            admission, gateway.open(f.reference, "enUS", p -> {}, CancellationToken.none()));
      }
    }
  }
}
