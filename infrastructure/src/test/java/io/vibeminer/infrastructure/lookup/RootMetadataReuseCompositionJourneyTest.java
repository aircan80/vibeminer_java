package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.Path;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Authored root fixture, real local service/gateway/CAS/SQL; no transport after initial seeding.
 */
class RootMetadataReuseCompositionJourneyTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  @Test
  void ckeyOnlyBuildReusesOriginalRootAndFreshOfflineReaderFindsAllVariants() throws Exception {
    var source = new RootMetadataReuseGatewayTest();
    source.directory = directory.resolve("store");
    var seeded = source.setup(false);
    var declaration = seeded.declaration();
    var actual = seeded.actual();
    var fixture = seeded.fixture();
    int requests = fixture.requests.size();
    seeded.close();
    var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    MetadataBuildAssociation attached;
    try (var store = new FileObjectStore(source.directory)) {
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, cap);
      var builds = new SqliteBuildObjectCatalog(sql, store);
      builds.registerSnapshot(declaration.build());
      var config = declaration.configuration();
      builds.attachVerifiedObject(
          declaration.build().snapshotId(),
          "BUILD_CONFIG",
          config.source(),
          config.object(),
          config.evidence(),
          NONE);
      var claims = new SqliteCanonicalContentRepository(sql, store, cap);
      var claim =
          claims.reserve(
              declaration.slot(), actual.actualEncodingKey(), actual.object().length(), NONE);
      var worker = claims.claimWorker(claim.token(), NONE);
      claims.markPublishing(worker, NONE);
      claims.recordVerifiedCompletion(worker, actual, NONE);
      claims.completePublication(worker, actual, NONE);
      claims.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
      var associations = new SqliteMetadataAssociationRepository(sql, store, cap);
      try (var gateway = new OfficialMetadataReuseGateway(store);
          var service =
              new DefaultMetadataReuseService(
                  gateway,
                  builds,
                  claims,
                  associations,
                  Runnable::run,
                  Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC))) {
        var plan =
            service
                .planRetained(declaration.build(), declaration.kind(), LookupBudgets.defaults())
                .toCompletableFuture()
                .join();
        assertEquals(declaration, plan.declaration());
        assertTrue(plan.declaration().preferredEncodingKey().isEmpty());
        assertTrue(plan.declaration().declaredEncodedBytes().isEmpty());
        assertTrue(plan.declaration().declaredDecodedBytes().isEmpty());
        attached = service.apply(plan).toCompletableFuture().join();
        assertEquals(actual, attached.actualRepresentation());
        var member =
            builds
                .findVerifiedObject(declaration.build().snapshotId(), "ROOT_MANIFEST", NONE)
                .orElseThrow();
        assertEquals(actual.originalAlias().source(), member.source());
        assertEquals(actual.object(), member.object());
        assertEquals(actual.originalAlias().evidence(), member.evidence());
      }
    }
    // A new store session and gateway must read retained evidence only. Fixture throws on any IO.
    try (var store = new FileObjectStore(source.directory)) {
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, cap);
      var associations = new SqliteMetadataAssociationRepository(sql, store, cap);
      assertEquals(
          attached,
          associations
              .find(declaration.build().snapshotId(), declaration.kind(), NONE)
              .orElseThrow());
      try (var c = sql.connect();
          var statement = c.createStatement();
          var rows = statement.executeQuery("SELECT kind,pinned FROM retention_scopes")) {
        assertTrue(rows.next());
        assertEquals("ROOT_METADATA", rows.getString(1));
        assertEquals(1, rows.getInt(2));
        assertFalse(rows.next());
      }
      try (var reader =
          new OfficialFileLookupGateway(
              store, fixture, Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC))) {
        var result =
            reader.lookupRetained(
                declaration.build(),
                declaration.configuration(),
                actual.originalAlias(),
                Set.of(42L, 999L),
                LookupBudgets.defaults(),
                NONE);
        assertEquals(2, result.variants().size());
        assertEquals(
            Set.of(42L),
            result.variants().stream()
                .map(v -> v.fileDataId())
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(requests, fixture.requests.size());
      }
    }
  }
}
