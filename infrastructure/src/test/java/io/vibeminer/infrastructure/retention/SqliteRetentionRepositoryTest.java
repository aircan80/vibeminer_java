package io.vibeminer.infrastructure.retention;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRetentionRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();

  static BuildSnapshot build(String version) {
    return new BuildSnapshot(
        "wow",
        "Fixture",
        "current",
        "us",
        version,
        "a".repeat(32),
        "b".repeat(32),
        Instant.EPOCH,
        true);
  }

  static ResolvedSourceIdentity source(String key) {
    return new ResolvedSourceIdentity("fixture", key, "sha256.v1");
  }

  static ScopeRequirement requirement(String role, String key, long encoded, long logical) {
    return new ScopeRequirement(
        role,
        Optional.of(source(key)),
        OptionalLong.of(encoded),
        logical < 0 ? OptionalLong.empty() : OptionalLong.of(logical));
  }

  static RetentionScope scope(BuildSnapshot build, ScopeRequirement... roles) {
    return new RetentionScope(build.snapshotId(), ScopeKind.LEGACY_RETAINED, List.of(roles));
  }

  static StoredObject publish(ObjectStore store, String value) throws Exception {
    var bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    return store.publish(
        new ByteArrayInputStream(bytes),
        new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, digest, bytes.length),
        1024,
        NONE);
  }

  static void sql(Path database, String command) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute(command);
    }
  }

  @Test
  void pinReleaseRestartAndWorkspaceRemovalNeverDeleteContent() throws Exception {
    var db = directory.resolve("catalog.sqlite");
    var factory = new SqliteCatalog(db);
    var build = build("one");
    RetentionScope scope;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(factory, store);
      catalog.registerSnapshot(build);
      var object = publish(store, "shared");
      var proof =
          new SourceVerificationEvidence(
              source("x").verificationSemantics(),
              "https://example.invalid/fixture",
              Instant.EPOCH);
      catalog.attachVerifiedObject(build.snapshotId(), "x", source("x"), object, proof, NONE);
      scope = scope(build, requirement("x", "x", 6, 12));
      var repository = new SqliteRetentionRepository(factory);
      assertEquals(scope, repository.pin(scope, NONE));
      repository.pin(scope, NONE);
      var workspace = Workspace.create(build, "enUS", "Fixture", Instant.EPOCH);
      var workspaces = new SqliteWorkspaceRepository(factory);
      workspaces.save(workspace);
      workspaces.remove(workspace.id());
      assertTrue(repository.readView(scope.scopeId(), RetentionLimits.defaults(), NONE).pinned());
      repository.release(scope.scopeId(), NONE);
      repository.release(scope.scopeId(), NONE);
      assertTrue(store.verify(object.digest(), NONE).isPresent());
      assertEquals(
          proof, catalog.findVerifiedSourceAlias(source("x"), NONE).orElseThrow().evidence());
    }
    var reopened = new SqliteRetentionRepository(new SqliteCatalog(db));
    var view = reopened.readView(scope.scopeId(), RetentionLimits.defaults(), NONE);
    assertFalse(view.pinned());
    assertEquals(scope, view.selected());
  }

  @Test
  void schema3MigrationPinsEveryExistingRoleWithoutInventingLogicalBytes() throws Exception {
    var db = directory.resolve("catalog.sqlite");
    var factory = new SqliteCatalog(db);
    var one = build("one");
    var two = build("two");
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(factory, store);
      var object = publish(store, "shared");
      var proof =
          new SourceVerificationEvidence(
              source("x").verificationSemantics(),
              "https://example.invalid/original",
              Instant.EPOCH);
      for (var build : List.of(one, two)) {
        catalog.registerSnapshot(build);
        catalog.attachVerifiedObject(build.snapshotId(), "x", source("x"), object, proof, NONE);
      }
      sql(db, "DROP TABLE retention_scope_requirements");
      sql(db, "DROP TABLE retention_scopes");
      sql(db, "PRAGMA user_version=3");
      var repository = new SqliteRetentionRepository(new SqliteCatalog(db));
      var compatibility = scope(one, requirement("x", "x", 6, -1));
      var view = repository.readView(compatibility.scopeId(), RetentionLimits.defaults(), NONE);
      assertTrue(view.pinned());
      assertEquals(1, view.otherPinned().size());
      assertTrue(view.selected().requirements().getFirst().logicalDecodedBytes().isEmpty());
      assertEquals(
          proof, catalog.findVerifiedSourceAlias(source("x"), NONE).orElseThrow().evidence());
      repository.release(compatibility.scopeId(), NONE);
      assertFalse(
          new SqliteRetentionRepository(new SqliteCatalog(db))
              .readView(compatibility.scopeId(), RetentionLimits.defaults(), NONE)
              .pinned());
    }
  }

  @Test
  void conflictingKnownFactsRollbackAndUnknownFactsRemainUnknown() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var build = build("one");
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      new SqliteBuildObjectCatalog(factory, store).registerSnapshot(build);
    }
    var repository = new SqliteRetentionRepository(factory);
    var original = scope(build, requirement("x", "same", 5, -1));
    repository.pin(original, NONE);
    var changed = scope(build, requirement("y", "same", 6, 20));
    assertEquals(
        RetentionException.Code.INTEGRITY_FAILURE,
        assertThrows(RetentionException.class, () -> repository.pin(changed, NONE)).code());
    assertEquals(
        RetentionException.Code.NOT_FOUND,
        assertThrows(
                RetentionException.class,
                () -> repository.readView(changed.scopeId(), RetentionLimits.defaults(), NONE))
            .code());
    var consistent = scope(build, requirement("y", "same", 5, 20));
    repository.pin(consistent, NONE);
    assertTrue(
        repository
            .readView(original.scopeId(), RetentionLimits.defaults(), NONE)
            .selected()
            .requirements()
            .getFirst()
            .logicalDecodedBytes()
            .isEmpty());
  }

  @Test
  void boundedViewCancellationAndCorruptDefinitionFailClosed() throws Exception {
    var db = directory.resolve("catalog.sqlite");
    var factory = new SqliteCatalog(db);
    var build = build("one");
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      new SqliteBuildObjectCatalog(factory, store).registerSnapshot(build);
    }
    var repository = new SqliteRetentionRepository(factory);
    var first = scope(build, requirement("x", "x", 5, 10));
    var second = scope(build, requirement("y", "y", 6, 12));
    repository.pin(first, NONE);
    repository.pin(second, NONE);
    assertEquals(
        RetentionException.Code.CAPACITY_EXCEEDED,
        assertThrows(
                RetentionException.class,
                () -> repository.readView(first.scopeId(), new RetentionLimits(1, 1), NONE))
            .code());
    assertThrows(
        CancellationException.class, () -> repository.release(first.scopeId(), () -> true));
    assertTrue(repository.readView(first.scopeId(), RetentionLimits.defaults(), NONE).pinned());
    sql(
        db,
        "UPDATE retention_scope_requirements SET logical_decoded_bytes=99 WHERE scope_id='"
            + first.scopeId()
            + "'");
    assertEquals(
        RetentionException.Code.INTEGRITY_FAILURE,
        assertThrows(
                RetentionException.class,
                () -> repository.readView(first.scopeId(), RetentionLimits.defaults(), NONE))
            .code());
  }

  @Test
  void concurrentSchema3MigrationCreatesCompatibilityPinsOnce() throws Exception {
    var db = directory.resolve("catalog.sqlite");
    var factory = new SqliteCatalog(db);
    var build = build("one");
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(factory, store);
      catalog.registerSnapshot(build);
      var object = publish(store, "x");
      catalog.attachVerifiedObject(
          build.snapshotId(),
          "x",
          source("x"),
          object,
          new SourceVerificationEvidence(
              source("x").verificationSemantics(), "https://example.invalid/x", Instant.EPOCH),
          NONE);
    }
    sql(db, "DROP TABLE retention_scope_requirements");
    sql(db, "DROP TABLE retention_scopes");
    sql(db, "PRAGMA user_version=3");
    var compatibility = scope(build, requirement("x", "x", 1, -1));
    try (var workers = Executors.newFixedThreadPool(3)) {
      var jobs = new ArrayList<Future<RetentionView>>();
      for (int i = 0; i < 3; i++)
        jobs.add(
            workers.submit(
                () ->
                    new SqliteRetentionRepository(new SqliteCatalog(db))
                        .readView(compatibility.scopeId(), RetentionLimits.defaults(), NONE)));
      for (var job : jobs) {
        var view = job.get(5, TimeUnit.SECONDS);
        assertTrue(view.pinned());
        assertTrue(view.otherPinned().isEmpty());
      }
    }
  }

  @Test
  void realCasAccountingPersistsDesiredMissingScopeAndSharesPhysicalAcrossAliases()
      throws Exception {
    var db = directory.resolve("catalog.sqlite");
    var factory = new SqliteCatalog(db);
    var one = build("one");
    var two = build("two");
    RetentionScope first, second;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(factory, store);
      catalog.registerSnapshot(one);
      catalog.registerSnapshot(two);
      var object = publish(store, "shared");
      for (var entry : List.of(Map.entry(one, "one"), Map.entry(two, "two"))) {
        var identity = source(entry.getValue());
        catalog.attachVerifiedObject(
            entry.getKey().snapshotId(),
            "metadata",
            identity,
            object,
            new SourceVerificationEvidence(
                identity.verificationSemantics(),
                "https://example.invalid/" + entry.getValue(),
                Instant.EPOCH),
            NONE);
      }
      first =
          scope(
              one, requirement("metadata", "one", 6, 12), requirement("missing", "missing", 9, -1));
      second = scope(two, requirement("metadata", "two", 6, 12));
      try (var service =
          new DefaultRetentionService(
              new SqliteRetentionRepository(factory), catalog, store, Runnable::run)) {
        service.pin(first).toCompletableFuture().join();
        service.pin(second).toCompletableFuture().join();
        var measured =
            service
                .measure(first.scopeId(), RetentionLimits.defaults())
                .toCompletableFuture()
                .join();
        assertEquals(6, measured.presentUniqueBytes());
        assertEquals(6, measured.sharedPresentBytes());
        assertEquals(0, measured.losesProtectionBytes());
        assertEquals(new ByteEstimate(9, 0), measured.additionalEncodedBytes());
        assertEquals(new ByteEstimate(12, 1), measured.logicalDecodedBytes());
        assertEquals(1, measured.missingRequirements());
        service.release(first.scopeId()).toCompletableFuture().join();
      }
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(new SqliteCatalog(db), store);
      try (var service =
          new DefaultRetentionService(
              new SqliteRetentionRepository(new SqliteCatalog(db)),
              catalog,
              store,
              Runnable::run)) {
        var restored =
            service
                .measure(first.scopeId(), RetentionLimits.defaults())
                .toCompletableFuture()
                .join();
        assertEquals(0, restored.losesProtectionBytes());
        assertEquals(6, restored.presentUniqueBytes());
        assertEquals(1, restored.missingRequirements());
        assertEquals(
            6,
            service
                .measure(second.scopeId(), RetentionLimits.defaults())
                .toCompletableFuture()
                .join()
                .losesProtectionBytes());
      }
      try (var files = Files.walk(directory.resolve("store/objects"))) {
        assertEquals(1, files.filter(Files::isRegularFile).count());
      }
    }
  }

  static RetentionScope rootScope(BuildSnapshot build, String candidate, long bytes) {
    return new RetentionScope(
        build.snapshotId(),
        ScopeKind.ROOT_METADATA,
        List.of(
            requirement("BUILD_CONFIG", "config", 4, 4),
            requirement("ROOT_MANIFEST", candidate, bytes, 100)));
  }

  @Test
  void explicitRootCandidateCanReplaceFailedIntentWithoutRewritingHistory() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var build = build("one");
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      new SqliteBuildObjectCatalog(factory, store).registerSnapshot(build);
    }
    var repository = new SqliteRetentionRepository(factory);
    var first = rootScope(build, "A", 10);
    var second = rootScope(build, "B", 20);
    repository.replaceUnfulfilledRoot(first, NONE);
    repository.replaceUnfulfilledRoot(second, NONE);
    assertFalse(repository.readView(first.scopeId(), RetentionLimits.defaults(), NONE).pinned());
    assertTrue(repository.readView(second.scopeId(), RetentionLimits.defaults(), NONE).pinned());
    assertEquals(
        first, repository.readView(first.scopeId(), RetentionLimits.defaults(), NONE).selected());
    assertEquals(
        List.of(second),
        repository.readView(first.scopeId(), RetentionLimits.defaults(), NONE).otherPinned());
    repository.replaceUnfulfilledRoot(second, NONE);
    assertEquals(
        RetentionException.Code.INTEGRITY_FAILURE,
        assertThrows(
                RetentionException.class,
                () -> repository.replaceUnfulfilledRoot(rootScope(build, "A", 11), NONE))
            .code());
    assertTrue(repository.readView(second.scopeId(), RetentionLimits.defaults(), NONE).pinned());
  }

  @Test
  void releasedDifferentCandidateHistoryDoesNotPoisonGenericNewPin() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var build = build("one");
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      new SqliteBuildObjectCatalog(factory, store).registerSnapshot(build);
    }
    var repository = new SqliteRetentionRepository(factory);
    var first = rootScope(build, "A", 10);
    var second = rootScope(build, "B", 20);
    repository.pin(first, NONE);
    repository.release(first.scopeId(), NONE);
    repository.pin(second, NONE);
    assertTrue(repository.readView(second.scopeId(), RetentionLimits.defaults(), NONE).pinned());
  }

  @Test
  void replacementIsRootOnlyAndAttachedMembershipRemainsImmutable() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var build = build("one");
    var repository = new SqliteRetentionRepository(factory);
    var first = rootScope(build, "A", 1);
    var second = rootScope(build, "B", 20);
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(factory, store);
      catalog.registerSnapshot(build);
      repository.replaceUnfulfilledRoot(first, NONE);
      var object = publish(store, "a");
      catalog.attachVerifiedObject(
          build.snapshotId(),
          "ROOT_MANIFEST",
          source("A"),
          object,
          new SourceVerificationEvidence(
              source("A").verificationSemantics(), "https://example.invalid/rootA", Instant.EPOCH),
          NONE);
      assertEquals(
          RetentionException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  RetentionException.class, () -> repository.replaceUnfulfilledRoot(second, NONE))
              .code());
      assertTrue(repository.readView(first.scopeId(), RetentionLimits.defaults(), NONE).pinned());
      assertEquals(
          RetentionException.Code.NOT_FOUND,
          assertThrows(
                  RetentionException.class,
                  () -> repository.readView(second.scopeId(), RetentionLimits.defaults(), NONE))
              .code());
      assertThrows(
          IllegalArgumentException.class,
          () -> repository.replaceUnfulfilledRoot(scope(build, requirement("x", "x", 1, 1)), NONE));
    }
  }

  @Test
  void cancellationRollsBackReplacementIncludingPriorPinRelease() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var build = build("one");
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      new SqliteBuildObjectCatalog(factory, store).registerSnapshot(build);
    }
    var repository = new SqliteRetentionRepository(factory);
    var first = rootScope(build, "A", 10);
    var second = rootScope(build, "B", 20);
    repository.replaceUnfulfilledRoot(first, NONE);
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(
        CancellationException.class,
        () -> repository.replaceUnfulfilledRoot(second, () -> checks.incrementAndGet() >= 4));
    assertTrue(repository.readView(first.scopeId(), RetentionLimits.defaults(), NONE).pinned());
    assertEquals(
        RetentionException.Code.NOT_FOUND,
        assertThrows(
                RetentionException.class,
                () -> repository.readView(second.scopeId(), RetentionLimits.defaults(), NONE))
            .code());
  }

  @Test
  void historicalRootIntentMeasuresUnsatisfiedAfterAnotherCandidateAttaches() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var build = build("one");
    var repository = new SqliteRetentionRepository(factory);
    var first = rootScope(build, "A", 10);
    var second = rootScope(build, "B", 1);
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(factory, store);
      catalog.registerSnapshot(build);
      repository.replaceUnfulfilledRoot(first, NONE);
      repository.replaceUnfulfilledRoot(second, NONE);
      var object = publish(store, "b");
      catalog.attachVerifiedObject(
          build.snapshotId(),
          "ROOT_MANIFEST",
          source("B"),
          object,
          new SourceVerificationEvidence(
              source("B").verificationSemantics(), "https://example.invalid/rootB", Instant.EPOCH),
          NONE);
      try (var service = new DefaultRetentionService(repository, catalog, store, Runnable::run)) {
        var history =
            service
                .measure(first.scopeId(), RetentionLimits.defaults())
                .toCompletableFuture()
                .join();
        assertEquals(0, history.losesProtectionBytes());
        assertEquals(0, history.presentUniqueBytes());
        assertEquals(new ByteEstimate(14, 0), history.additionalEncodedBytes());
        assertEquals(2, history.missingRequirements());
        assertEquals(
            1,
            service
                .measure(second.scopeId(), RetentionLimits.defaults())
                .toCompletableFuture()
                .join()
                .presentUniqueBytes());
      }
    }
  }

  @Test
  void historicalCandidateBytesDoNotSatisfyAnotherAttachedRepresentation() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    var build = build("one");
    var other = build("other");
    var repository = new SqliteRetentionRepository(factory);
    var first = rootScope(build, "A", 10);
    var second = rootScope(build, "B", 1);
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteBuildObjectCatalog(factory, store);
      catalog.registerSnapshot(build);
      catalog.registerSnapshot(other);
      repository.replaceUnfulfilledRoot(first, NONE);
      repository.replaceUnfulfilledRoot(second, NONE);
      var a = publish(store, "AAAAAAAAAA");
      var b = publish(store, "b");
      for (var binding : List.of(Map.entry("A", a), Map.entry("B", b))) {
        catalog.attachVerifiedObject(
            binding.getKey().equals("A") ? other.snapshotId() : build.snapshotId(),
            "ROOT_MANIFEST",
            source(binding.getKey()),
            binding.getValue(),
            new SourceVerificationEvidence(
                source(binding.getKey()).verificationSemantics(),
                "https://example.invalid/" + binding.getKey(),
                Instant.EPOCH),
            NONE);
      }
      try (var service = new DefaultRetentionService(repository, catalog, store, Runnable::run)) {
        var history =
            service
                .measure(first.scopeId(), RetentionLimits.defaults())
                .toCompletableFuture()
                .join();
        assertEquals(10, history.presentUniqueBytes());
        assertEquals(1, history.unresolvedRequirements());
        assertEquals(1, history.missingRequirements());
        assertEquals(new ByteEstimate(4, 0), history.additionalEncodedBytes());
        assertEquals(0, history.losesProtectionBytes());
      }
    }
  }
}
