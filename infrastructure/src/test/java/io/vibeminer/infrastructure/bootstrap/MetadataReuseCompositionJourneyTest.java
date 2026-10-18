package io.vibeminer.infrastructure.bootstrap;

import static io.vibeminer.infrastructure.bootstrap.BootstrapWorkflowIntegrationTest.manifest;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.lookup.OfficialMetadataReuseGateway;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real local reuse composition; initial source acquisition uses authored synthetic transport only.
 * Association replay is covered here; this does not claim the ordinary install-view API supports B.
 */
class MetadataReuseCompositionJourneyTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  record Stack(
      SqliteCatalog sql,
      SqliteBuildObjectCatalog builds,
      SqliteCanonicalContentRepository claims,
      SqliteMetadataAssociationRepository associations,
      OfficialMetadataReuseGateway gateway,
      DefaultMetadataReuseService service)
      implements AutoCloseable {
    public void close() {
      service.close();
      gateway.close();
    }
  }

  Stack stack(FileObjectStore store, Path db) {
    var sql = new SqliteCatalog(db);
    var session = store.contentSession();
    SqliteCanonicalContentRepository.recoverAtStartup(sql, session);
    var claims = new SqliteCanonicalContentRepository(sql, store, session);
    var builds = new SqliteBuildObjectCatalog(sql, store);
    var associations = new SqliteMetadataAssociationRepository(sql, store, session);
    var gateway = new OfficialMetadataReuseGateway(store);
    return new Stack(
        sql,
        builds,
        claims,
        associations,
        gateway,
        new DefaultMetadataReuseService(
            gateway,
            builds,
            claims,
            associations,
            Runnable::run,
            Clock.fixed(BootstrapWorkflowIntegrationTest.NOW, ZoneOffset.UTC)));
  }

  InstallMetadataReuseGatewayTest.Setup seed(Path path) throws Exception {
    var fixture = new InstallMetadataReuseGatewayTest();
    fixture.directory = path;
    var seeded = fixture.setup(manifest());
    seeded.close();
    return new InstallMetadataReuseGatewayTest.Setup(
        new FileObjectStore(path), seeded.actual(), seeded.declaration(), seeded.requests());
  }

  void register(Stack stack, InstallMetadataReuseGatewayTest.Setup fixture) {
    var d = fixture.declaration();
    stack.builds.registerSnapshot(d.build());
    var cfg = d.configuration();
    stack.builds.attachVerifiedObject(
        d.build().snapshotId(), "BUILD_CONFIG", cfg.source(), cfg.object(), cfg.evidence(), NONE);
    var a = fixture.actual();
    var claim = stack.claims.reserve(d.slot(), a.actualEncodingKey(), a.object().length(), NONE);
    var worker = stack.claims.claimWorker(claim.token(), NONE);
    stack.claims.markPublishing(worker, NONE);
    stack.claims.recordVerifiedCompletion(worker, a, NONE);
    stack.claims.completePublication(worker, a, NONE);
    stack.claims.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
  }

  static long count(SqliteCatalog sql, String table) throws Exception {
    try (var c = sql.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(r.next());
      return r.getLong(1);
    }
  }

  static Path blob(Path store, StoredObject object) {
    var hash = object.digest().value();
    return store.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
  }

  @Test
  void alternateDeclarationAssociatesOriginalAAndFreshInstancesReplayWithoutTransfers()
      throws Exception {
    var storePath = directory.resolve("store");
    var db = directory.resolve("catalog.sqlite");
    MetadataBuildAssociation attached;
    try (var f = seed(storePath);
        var s = stack(f.store(), db)) {
      register(s, f);
      int requests = f.requests().get();
      long objects = InstallMetadataReuseGatewayTest.objects(storePath);
      var plan =
          s.service
              .planRetained(
                  f.declaration().build(), f.declaration().kind(), LookupBudgets.defaults())
              .toCompletableFuture()
              .join();
      assertEquals(f.declaration(), plan.declaration());
      assertEquals(f.actual(), plan.actualRepresentation());
      assertEquals(0, plan.additionalPayloadTransferBytes());
      assertNotEquals(
          plan.declaration().preferredEncodingKey().orElseThrow(),
          plan.actualRepresentation().actualEncodingKey());
      attached = s.service.apply(plan).toCompletableFuture().join();
      assertEquals(f.actual(), attached.actualRepresentation());
      var reference =
          s.builds
              .findVerifiedObject(f.declaration().build().snapshotId(), "INSTALL_MANIFEST", NONE)
              .orElseThrow();
      assertEquals(
          f.actual().originalAlias(),
          new VerifiedSourceAlias(reference.source(), reference.object(), reference.evidence()));
      assertEquals(2, count(s.sql, "build_object_memberships"));
      assertEquals(2, count(s.sql, "source_aliases"));
      assertEquals(1, count(s.sql, "retention_scopes"));
      assertEquals(objects, InstallMetadataReuseGatewayTest.objects(storePath));
      assertEquals(requests, f.requests().get());
    }
    try (var store = new FileObjectStore(storePath);
        var s = stack(store, db)) {
      assertEquals(
          attached,
          s.associations
              .find(
                  attached.declaration().build().snapshotId(), attached.declaration().kind(), NONE)
              .orElseThrow());
      var plan =
          s.service
              .planRetained(
                  attached.declaration().build(),
                  attached.declaration().kind(),
                  LookupBudgets.defaults())
              .toCompletableFuture()
              .join();
      assertEquals(attached, s.service.apply(plan).toCompletableFuture().join());
      assertEquals(2, count(s.sql, "source_aliases"));
      assertEquals(1, count(s.sql, "retention_scopes"));
    }
  }

  @Test
  void missingOrCorruptActualFailsWithoutAttachmentOrRepair() throws Exception {
    for (boolean missing : new boolean[] {true, false}) {
      var storePath = directory.resolve(missing ? "missing" : "corrupt");
      try (var f = seed(storePath);
          var s = stack(f.store(), storePath.resolve("catalog.sqlite"))) {
        register(s, f);
        var plan =
            s.service
                .planRetained(
                    f.declaration().build(), f.declaration().kind(), LookupBudgets.defaults())
                .toCompletableFuture()
                .join();
        var path = blob(storePath, f.actual().object());
        if (missing) Files.delete(path);
        else {
          var bytes = Files.readAllBytes(path);
          bytes[bytes.length - 1] ^= 1;
          Files.write(path, bytes);
        }
        int requests = f.requests().get();
        var failure =
            assertThrows(
                CompletionException.class,
                () -> s.service.apply(plan).toCompletableFuture().join());
        var storeFailure = assertInstanceOf(ObjectStoreException.class, failure.getCause());
        assertEquals(
            missing
                ? ObjectStoreException.Code.MISSING_OBJECT
                : ObjectStoreException.Code.INTEGRITY_FAILURE,
            storeFailure.code());
        assertTrue(
            s.associations
                .find(f.declaration().build().snapshotId(), f.declaration().kind(), NONE)
                .isEmpty());
        assertEquals(1, count(s.sql, "build_object_memberships"));
        assertEquals(0, count(s.sql, "retention_scopes"));
        assertEquals(requests, f.requests().get());
        if (missing) assertFalse(Files.exists(path));
      }
    }
  }

  @Test
  void changedConfigurationAndForgedPlanTokenCannotAttach() throws Exception {
    var storePath = directory.resolve("store");
    try (var f = seed(storePath);
        var s = stack(f.store(), directory.resolve("catalog.sqlite"))) {
      register(s, f);
      var plan =
          s.service
              .planRetained(
                  f.declaration().build(), f.declaration().kind(), LookupBudgets.defaults())
              .toCompletableFuture()
              .join();
      var forged =
          new MetadataReusePlan(
              plan.issuanceId(),
              UUID.randomUUID(),
              plan.declaration(),
              plan.actualRepresentation());
      var tokenFailure =
          assertThrows(
              CompletionException.class,
              () -> s.service.apply(forged).toCompletableFuture().join());
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertInstanceOf(ContentClaimException.class, tokenFailure.getCause()).code());
      var path = blob(storePath, f.declaration().configuration().object());
      var bytes = Files.readAllBytes(path);
      bytes[0] ^= 1;
      Files.write(path, bytes);
      int requests = f.requests().get();
      assertThrows(
          CompletionException.class, () -> s.service.apply(plan).toCompletableFuture().join());
      assertEquals(0, count(s.sql, "retention_scopes"));
      assertEquals(1, count(s.sql, "build_object_memberships"));
      assertTrue(
          s.associations
              .find(f.declaration().build().snapshotId(), f.declaration().kind(), NONE)
              .isEmpty());
      assertEquals(requests, f.requests().get());
    }
  }
}
