package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMetadataAssociationRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  class Fixture implements AutoCloseable {
    final SqliteCatalog catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    final FileObjectStore store = new FileObjectStore(directory.resolve("store"));
    final SqliteBuildObjectCatalog objects;
    final SqliteCanonicalContentRepository canonical;
    final SqliteMetadataAssociationRepository associations;
    final MetadataBuildAssociation association;
    final RetentionScope scope;

    Fixture() throws Exception {
      this(MetadataStructureAttestation.Kind.ROOT);
    }

    Fixture(MetadataStructureAttestation.Kind kind) throws Exception {
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap);
      canonical = new SqliteCanonicalContentRepository(catalog, store, cap);
      objects = new SqliteBuildObjectCatalog(catalog, store);
      associations = new SqliteMetadataAssociationRepository(catalog, store, cap);
      var base = MetadataAssociationRecordsTest.association();
      var configObject =
          store.publishVerified(new ByteArrayInputStream(new byte[24]), 24, 24, t -> {}, NONE);
      var config =
          new VerifiedSourceAlias(
              base.declaration().configuration().source(),
              configObject,
              base.declaration().configuration().evidence());
      objects.registerSnapshot(base.declaration().build());
      objects.attachVerifiedObject(
          base.declaration().build().snapshotId(),
          "BUILD_CONFIG",
          config.source(),
          config.object(),
          config.evidence(),
          NONE);
      var actualObject =
          store.publishVerified(new ByteArrayInputStream(new byte[80]), 80, 80, t -> {}, NONE);
      var old = base.actualRepresentation();
      var actualSource =
          kind == MetadataStructureAttestation.Kind.ROOT
              ? old.originalAlias().source()
              : new ResolvedSourceIdentity(
                  "tact.install.v1",
                  old.originalAlias().source().immutableSourceKey(),
                  "fixture-install-v1");
      var actualEvidence =
          new SourceVerificationEvidence(
              actualSource.verificationSemantics(),
              old.originalAlias().evidence().sourceUrl(),
              old.originalAlias().evidence().verifiedAt());
      var actual =
          new MetadataContentProof(
              new VerifiedSourceAlias(actualSource, actualObject, actualEvidence),
              old.actualEncodingKey(),
              old.contentKey(),
              old.declaredDecodedBytes(),
              old.decodedAttestation(),
              Optional.of(new MetadataStructureAttestation(kind, "fixture-grammar-v1")));
      var claim =
          canonical.reserve(base.declaration().slot(), actual.actualEncodingKey(), 80, NONE);
      var permit = canonical.claimWorker(claim.token(), NONE);
      canonical.markPublishing(permit, NONE);
      canonical.recordVerifiedCompletion(permit, actual, NONE);
      canonical.completePublication(permit, actual, NONE);
      canonical.finishWorker(permit, ContentWorkerFinishOutcome.RETAINED, NONE);
      var d = base.declaration();
      association =
          new MetadataBuildAssociation(
              new MetadataDeclaration(
                  d.build(),
                  config,
                  kind,
                  d.slot(),
                  d.preferredEncodingKey(),
                  d.declaredEncodedBytes(),
                  d.declaredDecodedBytes()),
              claim.token(),
              actual,
              base.associatedAt());
      scope = scope(association);
    }

    public void close() {
      store.close();
    }
  }

  static RetentionScope scope(MetadataBuildAssociation a) {
    return new RetentionScope(
        a.declaration().build().snapshotId(),
        a.declaration().kind() == MetadataStructureAttestation.Kind.ROOT
            ? ScopeKind.ROOT_METADATA
            : ScopeKind.INSTALL_METADATA,
        List.of(
            new ScopeRequirement(
                "BUILD_CONFIG",
                Optional.of(a.declaration().configuration().source()),
                OptionalLong.of(a.declaration().configuration().object().length()),
                OptionalLong.of(a.declaration().configuration().object().length())),
            new ScopeRequirement(
                a.declaration().kind() == MetadataStructureAttestation.Kind.ROOT
                    ? "ROOT_MANIFEST"
                    : "INSTALL_MANIFEST",
                Optional.of(a.actualRepresentation().originalAlias().source()),
                OptionalLong.of(a.actualRepresentation().encodedBytes()),
                OptionalLong.of(
                    a.actualRepresentation()
                        .decodedAttestation()
                        .orElseThrow()
                        .actualDecodedBytes()))));
  }

  long count(SqliteCatalog c, String table) throws Exception {
    try (var x = c.connect();
        var s = x.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      return r.getLong(1);
    }
  }

  @Test
  void attachesActualAAndPinsAtomicallyWithoutBEncodingAliasAndKeepsFirstObservation()
      throws Exception {
    try (var f = new Fixture()) {
      assertEquals(f.association, f.associations.attach(f.association, f.scope, NONE));
      assertEquals(2, count(f.catalog, "build_object_memberships"));
      assertEquals(1, count(f.catalog, "metadata_build_associations"));
      assertEquals(1, count(f.catalog, "retention_scopes"));
      assertEquals(2, count(f.catalog, "source_aliases"));
      var later =
          new MetadataBuildAssociation(
              f.association.declaration(),
              f.association.canonicalReservationToken(),
              f.association.actualRepresentation(),
              Instant.now());
      assertEquals(f.association, f.associations.attach(later, f.scope, NONE));
      assertEquals(
          f.association,
          f.associations
              .find(
                  f.association.declaration().build().snapshotId(),
                  MetadataStructureAttestation.Kind.ROOT,
                  NONE)
              .orElseThrow());
      var permit = f.canonical.claimWorker(f.association.canonicalReservationToken(), NONE);
      assertEquals(f.association, f.associations.attach(later, f.scope, NONE));
      f.canonical.finishWorker(permit, ContentWorkerFinishOutcome.RETAINED, NONE);
    }
  }

  @Test
  void canceledOrFailedTransactionLeavesNoAssociationMembershipOrPin() throws Exception {
    try (var f = new Fixture()) {
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute(
            "CREATE TRIGGER reject_metadata_association BEFORE INSERT ON"
                + " metadata_build_associations BEGIN SELECT RAISE(ABORT,'test'); END");
      }
      assertThrows(
          ContentClaimException.class, () -> f.associations.attach(f.association, f.scope, NONE));
      assertEquals(1, count(f.catalog, "build_object_memberships"));
      assertEquals(0, count(f.catalog, "metadata_build_associations"));
      assertEquals(0, count(f.catalog, "retention_scopes"));
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> f.associations.attach(f.association, f.scope, () -> true));
    }
  }

  @Test
  void mismatchedTokenScopeCanonicalProofAndMissingAliasFailClosed() throws Exception {
    try (var f = new Fixture()) {
      var wrong =
          new MetadataBuildAssociation(
              f.association.declaration(),
              UUID.randomUUID(),
              f.association.actualRepresentation(),
              f.association.associatedAt());
      assertThrows(ContentClaimException.class, () -> f.associations.attach(wrong, f.scope, NONE));
      var badScope =
          new RetentionScope(
              f.scope.snapshotId(),
              ScopeKind.ROOT_METADATA,
              List.of(
                  new ScopeRequirement(
                      "BUILD_CONFIG", Optional.empty(), OptionalLong.empty(), OptionalLong.empty()),
                  f.scope.requirements().get(1)));
      assertThrows(
          ContentClaimException.class, () -> f.associations.attach(f.association, badScope, NONE));
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute(
            "UPDATE source_aliases SET source_url='https://us.cdn.blizzard.com/changed' WHERE"
                + " namespace='tact.config.raw-md5'");
      }
      assertThrows(
          ContentClaimException.class, () -> f.associations.attach(f.association, f.scope, NONE));
      assertEquals(0, count(f.catalog, "metadata_build_associations"));
    }
  }

  @Test
  void storedRecordChecksumDamageFailsTypedAfterReopen() throws Exception {
    String id;
    try (var f = new Fixture()) {
      f.associations.attach(f.association, f.scope, NONE);
      id = f.scope.snapshotId();
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute("PRAGMA user_version=9");
      }
      try (var c = f.catalog.connect();
          var s = c.createStatement();
          var r = s.executeQuery("PRAGMA user_version")) {
        assertEquals(SqliteCatalog.SCHEMA_VERSION, r.getInt(1));
      }
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute("UPDATE metadata_build_associations SET record_sha256='" + "0".repeat(64) + "'");
      }
      assertThrows(
          ContentClaimException.class,
          () -> f.associations.find(id, MetadataStructureAttestation.Kind.ROOT, NONE));
    }
  }

  @Test
  void twoBuildsShareActualObjectButKeepOwnImmutableContextAndRestartOffline() throws Exception {
    MetadataBuildAssociation first, second;
    try (var f = new Fixture()) {
      first = f.associations.attach(f.association, f.scope, NONE);
      var old = first.declaration();
      var build =
          new BuildSnapshot(
              "wow",
              "Retail",
              "live",
              "us",
              "second",
              old.build().buildConfig(),
              old.build().cdnConfig(),
              Instant.now(),
              true);
      f.objects.registerSnapshot(build);
      var declaration =
          new MetadataDeclaration(
              build,
              old.configuration(),
              old.kind(),
              old.slot(),
              old.preferredEncodingKey(),
              old.declaredEncodedBytes(),
              old.declaredDecodedBytes());
      second =
          new MetadataBuildAssociation(
              declaration,
              first.canonicalReservationToken(),
              first.actualRepresentation(),
              first.associatedAt().plusSeconds(1));
      assertEquals(second, f.associations.attach(second, scope(second), NONE));
      assertEquals(2, count(f.catalog, "catalog_objects"));
      assertEquals(2, count(f.catalog, "source_aliases"));
      assertEquals(4, count(f.catalog, "build_object_memberships"));
      assertEquals(2, count(f.catalog, "metadata_build_associations"));
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap);
      var repository = new SqliteMetadataAssociationRepository(catalog, store, cap);
      assertEquals(
          first,
          repository
              .find(first.declaration().build().snapshotId(), first.declaration().kind(), NONE)
              .orElseThrow());
      assertEquals(
          second,
          repository
              .find(second.declaration().build().snapshotId(), second.declaration().kind(), NONE)
              .orElseThrow());
      assertEquals(first, repository.attach(first, scope(first), NONE));
    }
  }

  @Test
  void conflictPreventsNewAttachButOriginalLedgerRemainsReadableWithoutReadinessClaim()
      throws Exception {
    try (var f = new Fixture()) {
      f.associations.attach(f.association, f.scope, NONE);
      try (var c = f.catalog.connect();
          var q = c.prepareStatement("INSERT INTO canonical_legacy_conflicts VALUES(?,?,?)")) {
        q.setString(1, "wow");
        q.setString(2, f.association.declaration().slot().contentKey());
        q.setBytes(3, CanonicalContentRecords.encode(f.association.actualRepresentation()));
        q.executeUpdate();
      }
      assertEquals(
          ContentClaimException.Code.LEGACY_CONFLICT,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.associations.attach(f.association, f.scope, NONE))
              .code());
      var digest = f.association.actualRepresentation().object().digest().value();
      java.nio.file.Files.delete(
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2)));
      assertEquals(
          f.association,
          f.associations
              .find(f.scope.snapshotId(), MetadataStructureAttestation.Kind.ROOT, NONE)
              .orElseThrow());
    }
  }

  @Test
  void missingPhysicalObjectAndChangedFullProofNeverAttachOrRepair() throws Exception {
    try (var f = new Fixture()) {
      var old = f.association.actualRepresentation();
      var different =
          new MetadataContentProof(
              old.originalAlias(),
              old.actualEncodingKey(),
              old.contentKey(),
              old.declaredDecodedBytes(),
              old.decodedAttestation(),
              Optional.of(
                  new MetadataStructureAttestation(
                      MetadataStructureAttestation.Kind.ROOT, "other-parser-v1")));
      var a =
          new MetadataBuildAssociation(
              f.association.declaration(),
              f.association.canonicalReservationToken(),
              different,
              f.association.associatedAt());
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(ContentClaimException.class, () -> f.associations.attach(a, f.scope, NONE))
              .code());
      var digest = old.object().digest().value();
      var file =
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2));
      java.nio.file.Files.delete(file);
      assertEquals(
          ContentClaimException.Code.MISSING_LOCAL,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.associations.attach(f.association, f.scope, NONE))
              .code());
      assertFalse(java.nio.file.Files.exists(file));
      assertEquals(0, count(f.catalog, "metadata_build_associations"));
    }
  }

  @Test
  void migrationAddsEmptyLedgerWithoutChangingCanonicalNonceProofReferencesOrPins()
      throws Exception {
    try (var f = new Fixture()) {
      f.associations.attach(f.association, f.scope, NONE);
      var worker = f.canonical.claimWorker(f.association.canonicalReservationToken(), NONE);
      byte[] before;
      try (var c = f.catalog.connect();
          var s = c.createStatement();
          var r = s.executeQuery("SELECT representation FROM canonical_content_slots")) {
        before = r.getBytes(1);
      }
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute("DROP TABLE metadata_build_associations");
        s.execute("PRAGMA user_version=9");
      }
      try (var c = f.catalog.connect();
          var s = c.createStatement();
          var r =
              s.executeQuery("SELECT representation,worker_nonce FROM canonical_content_slots")) {
        assertArrayEquals(before, r.getBytes(1));
        assertEquals(worker.workerNonce().toString(), r.getString(2));
      }
      assertEquals(0, count(f.catalog, "metadata_build_associations"));
      assertEquals(2, count(f.catalog, "build_object_memberships"));
      assertEquals(1, count(f.catalog, "retention_scopes"));
      assertTrue(
          f.associations
              .find(f.scope.snapshotId(), MetadataStructureAttestation.Kind.ROOT, NONE)
              .isEmpty());
      f.canonical.finishWorker(worker, ContentWorkerFinishOutcome.RETAINED, NONE);
      assertEquals(f.association, f.associations.attach(f.association, f.scope, NONE));
    }
  }

  @Test
  void corruptPhysicalBytesFailAndQuarantineWithoutAttaching() throws Exception {
    try (var f = new Fixture()) {
      var digest = f.association.actualRepresentation().object().digest().value();
      var file =
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2));
      java.nio.file.Files.write(file, new byte[] {1});
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.associations.attach(f.association, f.scope, NONE))
              .code());
      assertEquals(0, count(f.catalog, "metadata_build_associations"));
    }
  }

  @Test
  void installUsesItsOwnExactRoleAndClosure() throws Exception {
    try (var f = new Fixture(MetadataStructureAttestation.Kind.INSTALL)) {
      assertEquals(f.association, f.associations.attach(f.association, f.scope, NONE));
      assertEquals(
          f.association,
          f.associations
              .find(f.scope.snapshotId(), MetadataStructureAttestation.Kind.INSTALL, NONE)
              .orElseThrow());
      assertTrue(
          f.associations
              .find(f.scope.snapshotId(), MetadataStructureAttestation.Kind.ROOT, NONE)
              .isEmpty());
      assertEquals(
          f.association.actualRepresentation().originalAlias().source(),
          f.objects
              .findVerifiedObject(f.scope.snapshotId(), "INSTALL_MANIFEST", NONE)
              .orElseThrow()
              .source());
      assertTrue(
          f.objects.findVerifiedObject(f.scope.snapshotId(), "ROOT_MANIFEST", NONE).isEmpty());
    }
  }

  @Test
  void cancellationAtFinalCommitCheckRollsBackAlreadyInsertedAssociationAndPin() throws Exception {
    try (var f = new Fixture()) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      f.associations.attach(
          f.association,
          f.scope,
          () -> {
            calls.incrementAndGet();
            return false;
          });
      int finalCheck = calls.get();
      assertTrue(finalCheck > 4);
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute("DELETE FROM metadata_build_associations");
        s.execute("DELETE FROM retention_scope_requirements");
        s.execute("DELETE FROM retention_scopes");
        s.execute("DELETE FROM build_object_memberships WHERE role='ROOT_MANIFEST'");
      }
      calls.set(0);
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              f.associations.attach(
                  f.association, f.scope, () -> calls.incrementAndGet() >= finalCheck));
      assertEquals(finalCheck, calls.get());
      assertEquals(0, count(f.catalog, "metadata_build_associations"));
      assertEquals(0, count(f.catalog, "retention_scopes"));
      assertEquals(1, count(f.catalog, "build_object_memberships"));
      assertEquals(f.association, f.associations.attach(f.association, f.scope, NONE));
    }
  }

  @Test
  void unreadyCanonicalOrConflictingExistingMembershipNeverAttaches() throws Exception {
    try (var f = new Fixture()) {
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute("UPDATE canonical_content_slots SET status='RECOVERY'");
      }
      assertEquals(
          ContentClaimException.Code.RECOVERY_REQUIRED,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.associations.attach(f.association, f.scope, NONE))
              .code());
      try (var c = f.catalog.connect();
          var s = c.createStatement()) {
        s.execute("UPDATE canonical_content_slots SET status='RETAINED'");
      }
      var config = f.association.declaration().configuration();
      f.objects.attachVerifiedObject(
          f.scope.snapshotId(),
          "ROOT_MANIFEST",
          config.source(),
          config.object(),
          config.evidence(),
          NONE);
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.associations.attach(f.association, f.scope, NONE))
              .code());
      assertEquals(0, count(f.catalog, "metadata_build_associations"));
      assertEquals(0, count(f.catalog, "retention_scopes"));
      assertEquals(
          config.source(),
          f.objects.listReferences(f.scope.snapshotId()).stream()
              .filter(r -> r.role().equals("ROOT_MANIFEST"))
              .findFirst()
              .orElseThrow()
              .source());
    }
  }
}
