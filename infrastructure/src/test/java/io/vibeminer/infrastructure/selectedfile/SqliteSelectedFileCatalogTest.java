package io.vibeminer.infrastructure.selectedfile;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSelectedFileCatalogTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final String A = "a".repeat(32), B = "b".repeat(32), C = "c".repeat(32);

  static ContentSlotKey slot() {
    return new ContentSlotKey("wow", C);
  }

  static ContentRepresentation prospective(
      ContentSlotKey slot, String ekey, byte[] bytes, String host) throws Exception {
    var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var url = "https://" + host + "/fixture/" + ekey;
    var source = ContentRepresentation.sourceIdentity(ekey);
    return new ContentRepresentation(
        slot,
        new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest(sha), bytes.length),
            new SourceVerificationEvidence(source.verificationSemantics(), url, Instant.EPOCH)),
        EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS,
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            url,
            ekey,
            bytes.length,
            0,
            bytes.length,
            Instant.EPOCH,
            1,
            bytes.length,
            "1".repeat(64),
            Optional.empty()));
  }

  static void publish(ObjectStore store, byte[] bytes) throws Exception {
    var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    store.publish(
        new ByteArrayInputStream(bytes),
        new ExpectedObjectChecksum(ChecksumAlgorithm.SHA256, digest, bytes.length),
        1024,
        NONE);
  }

  @Test
  void durableReservationJoinsSameCandidateAndBlocksAnotherAcrossRestart() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var first = new SqliteSelectedFileCatalog(factory, store, UUID.randomUUID());
      var pending = first.reserve(slot(), A, 8, NONE);
      assertEquals(pending.token(), first.reserve(slot(), A, 8, NONE).token());
      assertEquals(
          SelectedFileException.Code.BUSY,
          assertThrows(SelectedFileException.class, () -> first.reserve(slot(), B, 8, NONE))
              .code());
      var restarted = new SqliteSelectedFileCatalog(factory, store, UUID.randomUUID());
      assertEquals(
          CanonicalReservation.Status.RECOVERY,
          restarted.lookup(slot(), NONE).orElseThrow().status());
      assertEquals(
          SelectedFileException.Code.BUSY,
          assertThrows(SelectedFileException.class, () -> restarted.reserve(slot(), B, 8, NONE))
              .code());
      assertEquals(
          CanonicalReservation.Status.RECOVERY, restarted.reserve(slot(), A, 8, NONE).status());
      assertEquals(
          SelectedFileException.Code.RECOVERY_REQUIRED,
          assertThrows(
                  SelectedFileException.class,
                  () -> restarted.releaseUnpublished(pending.token(), NONE))
              .code());
    }
  }

  @Test
  void onlyConclusivelyUnpublishedCurrentWorkerCanReleaseCandidate() throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      var pending = catalog.reserve(slot(), A, 8, NONE);
      catalog.markPublishing(pending.token(), NONE);
      catalog.releaseUnpublished(pending.token(), NONE);
      assertTrue(catalog.lookup(slot(), NONE).isEmpty());
      var second = catalog.reserve(slot(), B, 8, NONE);
      assertNotEquals(pending.token(), second.token());
      assertEquals(
          SelectedFileException.Code.NOT_FOUND,
          assertThrows(
                  SelectedFileException.class, () -> catalog.markPublishing(pending.token(), NONE))
              .code());
    }
  }

  @Test
  void verifiedCompletionSurvivesPromotionCatalogCrashAndKeepsActualReceipt() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    byte[] bytes = "fixture".getBytes();
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var first = new SqliteSelectedFileCatalog(factory, store);
      var reservation = first.reserve(slot(), A, bytes.length, NONE);
      first.markPublishing(reservation.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      first.recordVerifiedCompletion(reservation.token(), proof, NONE);
      assertEquals(
          SelectedFileException.Code.RECOVERY_REQUIRED,
          assertThrows(
                  SelectedFileException.class,
                  () -> first.releaseUnpublished(reservation.token(), NONE))
              .code());
      publish(store, bytes); // Crash after CAS promotion, before canonical catalog publication.
      var restarted = new SqliteSelectedFileCatalog(factory, store);
      var recovering = restarted.reserve(slot(), A, bytes.length, NONE);
      assertEquals(proof, recovering.representation().orElseThrow());
      var alternateRoute = prospective(slot(), A, bytes, "kr.cdn.blizzard.com");
      assertEquals(proof, restarted.completePublication(recovering.token(), alternateRoute, NONE));
      assertEquals(
          CanonicalReservation.Status.RETAINED,
          restarted.lookup(slot(), NONE).orElseThrow().status());
      assertEquals(
          SelectedFileException.Code.REPLAN_REQUIRED,
          assertThrows(
                  SelectedFileException.class,
                  () -> restarted.reserve(slot(), B, bytes.length, NONE))
              .code());
    }
  }

  @Test
  void noCanonicalReadyWithoutDurableProofAndPhysicalBytes() throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      assertEquals(
          SelectedFileException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  SelectedFileException.class,
                  () -> catalog.completePublication(pending.token(), proof, NONE))
              .code());
      catalog.recordVerifiedCompletion(pending.token(), proof, NONE);
      assertEquals(
          SelectedFileException.Code.MISSING_LOCAL,
          assertThrows(
                  SelectedFileException.class,
                  () -> catalog.completePublication(pending.token(), proof, NONE))
              .code());
      assertNotEquals(
          CanonicalReservation.Status.RETAINED,
          catalog.lookup(slot(), NONE).orElseThrow().status());
    }
  }

  @Test
  void concurrentDifferentCandidateReservationHasOneWinner() throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"));
        var workers = Executors.newFixedThreadPool(2)) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      var gate = new CountDownLatch(1);
      var jobs = new ArrayList<Future<Boolean>>();
      for (var candidate : List.of(A, B))
        jobs.add(
            workers.submit(
                () -> {
                  gate.await();
                  try {
                    catalog.reserve(slot(), candidate, 8, NONE);
                    return true;
                  } catch (SelectedFileException busy) {
                    assertEquals(SelectedFileException.Code.BUSY, busy.code());
                    return false;
                  }
                }));
      gate.countDown();
      int wins = 0;
      for (var job : jobs) if (job.get(5, TimeUnit.SECONDS)) wins++;
      assertEquals(1, wins);
    }
  }

  static BuildSnapshot build(String version) {
    return new BuildSnapshot("wow", "Fixture", "current", "us", version, A, B, Instant.EPOCH, true);
  }

  static VerifiedSourceAlias dependency(
      SqliteBuildObjectCatalog objects,
      ObjectStore store,
      BuildSnapshot build,
      String role,
      String text)
      throws Exception {
    byte[] bytes = text.getBytes();
    publish(store, bytes);
    var source = new ResolvedSourceIdentity("fixture.dependencies", role, "fixture-verified-v1");
    var alias =
        new VerifiedSourceAlias(
            source,
            new StoredObject(
                new ObjectDigest(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),
                bytes.length),
            new SourceVerificationEvidence(
                source.verificationSemantics(), "https://example.invalid/" + role, Instant.EPOCH));
    objects.attachVerifiedObject(
        build.snapshotId(), role, source, alias.object(), alias.evidence(), NONE);
    return alias;
  }

  static SelectedFileAssociation association(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      ContentRepresentation representation) {
    var variant =
        new FileVariantAssociation(
            build, root.source(), 7, C, 2, 3, 4, 5, 6, Optional.of("0000000000000000"));
    return new SelectedFileAssociation(
        new SelectedFileDescriptor(variant, A, ContentRepresentation.VERIFIER_SEMANTICS),
        configuration,
        root,
        new FileAssociationProof(
            root.source(),
            new ResolvedSourceIdentity("fixture.encoding", "bootstrap", "fixture-v1"),
            "authenticated-en-v1",
            "2".repeat(64),
            OptionalLong.of(1234),
            List.of(
                new EncodedCandidate(
                    A,
                    OptionalLong.of(representation.encoded().object().length()),
                    OptionalLong.of(0)),
                new EncodedCandidate(B, OptionalLong.empty(), OptionalLong.of(3)))),
        representation);
  }

  static RetentionScope scope(SelectedFileAssociation association) {
    var requirements = new ArrayList<ScopeRequirement>();
    for (var entry :
        Map.of(
                "BUILD_CONFIG",
                association.configuration(),
                "ROOT_MANIFEST",
                association.root(),
                association.role(),
                association.representation().encoded())
            .entrySet())
      requirements.add(
          new ScopeRequirement(
              entry.getKey(),
              Optional.of(entry.getValue().source()),
              OptionalLong.of(entry.getValue().object().length()),
              entry.getKey().equals("BUILD_CONFIG")
                  ? OptionalLong.of(entry.getValue().object().length())
                  : OptionalLong.empty()));
    return new RetentionScope(association.snapshotId(), ScopeKind.ENCODED_FILE, requirements);
  }

  @Test
  void twoBuildsPersistTypedAssociationAndOnePhysicalObjectWithOriginalEvidence() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    SelectedFileAssociation first, second;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var one = build("one");
      var two = build("two");
      objects.registerSnapshot(one);
      objects.registerSnapshot(two);
      var config = dependency(objects, store, one, "BUILD_CONFIG", "cfg");
      var root = dependency(objects, store, one, "ROOT_MANIFEST", "root");
      dependency(objects, store, two, "BUILD_CONFIG", "cfg");
      dependency(objects, store, two, "ROOT_MANIFEST", "root");
      var catalog = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var representation = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), representation, NONE);
      publish(store, bytes);
      representation = catalog.completePublication(pending.token(), representation, NONE);
      first = association(one, config, root, representation);
      second = association(two, config, root, representation);
      assertEquals(first, catalog.attachAssociation(first, scope(first), NONE));
      assertEquals(second, catalog.attachAssociation(second, scope(second), NONE));
      assertEquals(first, catalog.attachAssociation(first, scope(first), NONE));
      assertEquals(
          6,
          catalog
              .findAssociation(one.snapshotId(), first.role(), NONE)
              .orElseThrow()
              .descriptor()
              .association()
              .rawFlagsThird());
      assertEquals(
          Optional.of("0000000000000000"),
          catalog
              .findAssociation(one.snapshotId(), first.role(), NONE)
              .orElseThrow()
              .descriptor()
              .association()
              .nameHash());
      new SqliteRetentionRepository(factory).release(scope(first).scopeId(), NONE);
      try (var files = Files.walk(directory.resolve("store/objects"))) {
        assertEquals(3, files.filter(Files::isRegularFile).count());
      }
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var restarted = new SqliteSelectedFileCatalog(factory, store);
      assertEquals(
          first, restarted.findAssociation(first.snapshotId(), first.role(), NONE).orElseThrow());
      assertEquals(
          second,
          restarted.findAssociation(second.snapshotId(), second.role(), NONE).orElseThrow());
      assertEquals(
          "https://us.cdn.blizzard.com/fixture/" + A,
          restarted
              .lookup(slot(), NONE)
              .orElseThrow()
              .representation()
              .orElseThrow()
              .sourceReceipt()
              .actualResourceUrl());
      assertFalse(
          new SqliteRetentionRepository(factory)
              .readView(scope(first).scopeId(), RetentionLimits.defaults(), NONE)
              .pinned());
    }
  }

  @Test
  void dependencyPinConflictRollsBackNewAssociationAndMembershipTogether() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var one = build("one");
      var two = build("two");
      objects.registerSnapshot(one);
      objects.registerSnapshot(two);
      var config = dependency(objects, store, one, "BUILD_CONFIG", "cfg");
      var root = dependency(objects, store, one, "ROOT_MANIFEST", "root");
      dependency(objects, store, two, "BUILD_CONFIG", "cfg");
      dependency(objects, store, two, "ROOT_MANIFEST", "root");
      var catalog = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), proof, NONE);
      publish(store, bytes);
      var canonical = catalog.completePublication(pending.token(), proof, NONE);
      var first = association(one, config, root, canonical);
      catalog.attachAssociation(first, scope(first), NONE);
      var second = association(two, config, root, canonical);
      var requirements =
          scope(second).requirements().stream()
              .map(
                  requirement ->
                      requirement.role().equals("BUILD_CONFIG")
                          ? new ScopeRequirement(
                              requirement.role(),
                              requirement.source(),
                              requirement.encodedBytes(),
                              OptionalLong.of(999))
                          : requirement)
              .toList();
      var conflicting =
          new RetentionScope(second.snapshotId(), ScopeKind.ENCODED_FILE, requirements);
      assertEquals(
          SelectedFileException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  SelectedFileException.class,
                  () -> catalog.attachAssociation(second, conflicting, NONE))
              .code());
      assertTrue(catalog.findAssociation(second.snapshotId(), second.role(), NONE).isEmpty());
      assertTrue(objects.findVerifiedObject(second.snapshotId(), second.role(), NONE).isEmpty());
      assertEquals(
          CanonicalReservation.Status.RETAINED,
          catalog.lookup(slot(), NONE).orElseThrow().status());
    }
  }

  @Test
  void schema4UpgradePreservesExistingRetentionAndSourceMemberships() throws Exception {
    var database = directory.resolve("catalog.sqlite");
    var factory = new SqliteCatalog(database);
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var build = build("one");
      objects.registerSnapshot(build);
      var config = dependency(objects, store, build, "BUILD_CONFIG", "cfg");
      var retained =
          new RetentionScope(
              build.snapshotId(),
              ScopeKind.LEGACY_RETAINED,
              List.of(
                  new ScopeRequirement(
                      "BUILD_CONFIG",
                      Optional.of(config.source()),
                      OptionalLong.of(3),
                      OptionalLong.empty())));
      new SqliteRetentionRepository(factory).pin(retained, NONE);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE selected_file_associations");
        statement.execute("DROP TABLE canonical_content_slots");
        statement.execute("DROP TABLE encoded_file_receipts");
        statement.execute("PRAGMA user_version=4");
      }
      var upgraded = new SqliteSelectedFileCatalog(new SqliteCatalog(database), store);
      assertTrue(upgraded.lookup(slot(), NONE).isEmpty());
      assertTrue(
          new SqliteRetentionRepository(factory)
              .readView(retained.scopeId(), RetentionLimits.defaults(), NONE)
              .pinned());
      assertEquals(config, objects.findVerifiedSourceAlias(config.source(), NONE).orElseThrow());
    }
  }

  @Test
  void sameEKeyAcrossDeclaredCKeysAdoptsOneOriginalReceiptWithoutAnotherPublication()
      throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      byte[] bytes = "fixture".getBytes();
      var first = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(first.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(first.token(), proof, NONE);
      publish(store, bytes);
      catalog.completePublication(first.token(), proof, NONE);
      var another = new ContentSlotKey("wow", "d".repeat(32));
      var adopted = catalog.reserve(another, A, bytes.length, NONE);
      assertEquals(CanonicalReservation.Status.RETAINED, adopted.status());
      assertEquals(another, adopted.representation().orElseThrow().slot());
      assertEquals(proof.encoded(), adopted.representation().orElseThrow().encoded());
      assertEquals(proof.sourceReceipt(), adopted.representation().orElseThrow().sourceReceipt());
      try (var files = Files.walk(directory.resolve("store/objects"))) {
        assertEquals(1, files.filter(Files::isRegularFile).count());
      }
    }
  }

  @Test
  void completionGatePersistsProspectiveProofBeforePromotionAndCancellationKeepsRecovery()
      throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      assertThrows(
          CancellationException.class,
          () ->
              store.publishVerified(
                  new ByteArrayInputStream(bytes),
                  bytes.length,
                  1024,
                  token -> {
                    catalog.recordVerifiedCompletion(pending.token(), proof, token);
                    assertTrue(store.verify(proof.encoded().object().digest(), NONE).isEmpty());
                    cancelled.set(true);
                  },
                  cancelled::get));
      assertTrue(store.verify(proof.encoded().object().digest(), NONE).isEmpty());
      assertEquals(
          proof, catalog.lookup(slot(), NONE).orElseThrow().representation().orElseThrow());
      assertEquals(
          SelectedFileException.Code.RECOVERY_REQUIRED,
          assertThrows(
                  SelectedFileException.class,
                  () -> catalog.releaseUnpublished(pending.token(), NONE))
              .code());
      assertEquals(
          SelectedFileException.Code.BUSY,
          assertThrows(
                  SelectedFileException.class, () -> catalog.reserve(slot(), B, bytes.length, NONE))
              .code());
    }
  }

  @Test
  void failedDurableCompletionGatePreventsCasPublicationAndCanReleaseAfterWorkerReturns()
      throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      assertThrows(
          CancellationException.class,
          () ->
              store.publishVerified(
                  new ByteArrayInputStream(bytes),
                  bytes.length,
                  1024,
                  token -> catalog.recordVerifiedCompletion(pending.token(), proof, () -> true),
                  NONE));
      assertTrue(store.verify(proof.encoded().object().digest(), NONE).isEmpty());
      assertTrue(catalog.lookup(slot(), NONE).orElseThrow().representation().isEmpty());
      catalog.releaseUnpublished(pending.token(), NONE);
      assertEquals(B, catalog.reserve(slot(), B, bytes.length, NONE).actualEncodingKey());
    }
  }

  @Test
  void changedCompletionCannotOverwriteRecordedPhysicalIdentity() throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), proof, NONE);
      var changed = prospective(slot(), A, "changed".getBytes(), "us.cdn.blizzard.com");
      assertEquals(
          SelectedFileException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  SelectedFileException.class,
                  () -> catalog.recordVerifiedCompletion(pending.token(), changed, NONE))
              .code());
      assertEquals(
          proof, catalog.lookup(slot(), NONE).orElseThrow().representation().orElseThrow());
    }
  }

  @Test
  void cancellationAtEveryObservedBoundaryRollsBackAssociationButKeepsDesiredPin()
      throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var build = build("one");
      objects.registerSnapshot(build);
      var config = dependency(objects, store, build, "BUILD_CONFIG", "cfg");
      var root = dependency(objects, store, build, "ROOT_MANIFEST", "root");
      var catalog = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var proof = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), proof, NONE);
      publish(store, bytes);
      var canonical = catalog.completePublication(pending.token(), proof, NONE);
      var association = association(build, config, root, canonical);
      var desired = scope(association);
      var retention = new SqliteRetentionRepository(factory);
      retention.pin(desired, NONE);
      int canceled = 0;
      boolean completed = false;
      for (int boundary = 1; boundary <= 64; boundary++) {
        int cancelAt = boundary;
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try {
          catalog.attachAssociation(
              association, desired, () -> checks.incrementAndGet() == cancelAt);
          completed = true;
          break;
        } catch (CancellationException expected) {
          canceled++;
          assertTrue(
              catalog.findAssociation(build.snapshotId(), association.role(), NONE).isEmpty());
          assertTrue(
              objects.findVerifiedObject(build.snapshotId(), association.role(), NONE).isEmpty());
          assertTrue(
              retention.readView(desired.scopeId(), RetentionLimits.defaults(), NONE).pinned());
        }
      }
      assertTrue(completed, "A cancellation-free boundary eventually commits");
      assertTrue(canceled > 4, "Exercise internal transaction as well as entry/lease checks");
      assertEquals(
          association,
          catalog.findAssociation(build.snapshotId(), association.role(), NONE).orElseThrow());
    }
  }

  @Test
  void newAssociationRejectsLegacyOrUnadvertisedActualEncodingFacts() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var build = build("one");
      objects.registerSnapshot(build);
      var config = dependency(objects, store, build, "BUILD_CONFIG", "cfg");
      var root = dependency(objects, store, build, "ROOT_MANIFEST", "root");
      var catalog = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var completed = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), completed, NONE);
      publish(store, bytes);
      var canonical = catalog.completePublication(pending.token(), completed, NONE);
      var correct = association(build, config, root, canonical);
      var proof = correct.proof();
      var invalid =
          List.of(
              new FileAssociationProof(
                  proof.rootSource(),
                  proof.encodingBootstrapSource(),
                  proof.verifierRevision(),
                  proof.proofDigest()),
              new FileAssociationProof(
                  proof.rootSource(),
                  proof.encodingBootstrapSource(),
                  proof.verifierRevision(),
                  proof.proofDigest(),
                  OptionalLong.of(1234),
                  List.of(
                      new EncodedCandidate(
                          B, OptionalLong.of(bytes.length), OptionalLong.empty()))),
              new FileAssociationProof(
                  proof.rootSource(),
                  proof.encodingBootstrapSource(),
                  proof.verifierRevision(),
                  proof.proofDigest(),
                  OptionalLong.of(1234),
                  List.of(
                      new EncodedCandidate(
                          A, OptionalLong.of(bytes.length + 1), OptionalLong.empty()))));
      for (var changed : invalid) {
        var forged =
            new SelectedFileAssociation(correct.descriptor(), config, root, changed, canonical);
        assertEquals(
            SelectedFileException.Code.INTEGRITY_FAILURE,
            assertThrows(
                    SelectedFileException.class,
                    () -> catalog.attachAssociation(forged, scope(forged), NONE))
                .code());
        assertTrue(catalog.findAssociation(build.snapshotId(), correct.role(), NONE).isEmpty());
        assertTrue(objects.findVerifiedObject(build.snapshotId(), correct.role(), NONE).isEmpty());
      }
      assertEquals(correct, catalog.attachAssociation(correct, scope(correct), NONE));
    }
  }

  @Test
  void unknownEnSizeWithKnownActualReceiptPersistsAsUnknownAcrossRestart() throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    SelectedFileAssociation retained;
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var build = build("one");
      objects.registerSnapshot(build);
      var config = dependency(objects, store, build, "BUILD_CONFIG", "cfg");
      var root = dependency(objects, store, build, "ROOT_MANIFEST", "root");
      var catalog = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var completed = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), completed, NONE);
      publish(store, bytes);
      var canonical = catalog.completePublication(pending.token(), completed, NONE);
      var known = association(build, config, root, canonical);
      var proof = known.proof();
      var unknown =
          new FileAssociationProof(
              proof.rootSource(),
              proof.encodingBootstrapSource(),
              proof.verifierRevision(),
              proof.proofDigest(),
              proof.declaredDecodedBytes(),
              List.of(new EncodedCandidate(A, OptionalLong.empty(), OptionalLong.of(0))));
      retained = new SelectedFileAssociation(known.descriptor(), config, root, unknown, canonical);
      assertEquals(retained, catalog.attachAssociation(retained, scope(retained), NONE));
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var restored =
          new SqliteSelectedFileCatalog(factory, store)
              .findAssociation(retained.snapshotId(), retained.role(), NONE)
              .orElseThrow();
      assertTrue(restored.proof().authenticatedCandidates().getFirst().encodedBytes().isEmpty());
      assertEquals(7, restored.representation().encoded().object().length());
      assertEquals(retained, restored);
    }
  }

  @Test
  void laterObservationOfSameSnapshotReusesImmutableAssociationWithoutFalseConflict()
      throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var objects = new SqliteBuildObjectCatalog(factory, store);
      var original = build("one");
      objects.registerSnapshot(original);
      var config = dependency(objects, store, original, "BUILD_CONFIG", "cfg");
      var root = dependency(objects, store, original, "ROOT_MANIFEST", "root");
      var catalog = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var completed = prospective(slot(), A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), completed, NONE);
      publish(store, bytes);
      var canonical = catalog.completePublication(pending.token(), completed, NONE);
      var first = association(original, config, root, canonical);
      catalog.attachAssociation(first, scope(first), NONE);
      var observed =
          new BuildSnapshot(
              original.productId(),
              "New display label",
              original.channel(),
              original.region(),
              original.versionLabel(),
              original.buildConfig(),
              original.cdnConfig(),
              Instant.EPOCH.plusSeconds(60),
              original.fixture());
      assertEquals(original.snapshotId(), observed.snapshotId());
      var again = association(observed, config, root, canonical);
      assertEquals(first.role(), again.role());
      assertEquals(first, catalog.attachAssociation(again, scope(again), NONE));
      assertEquals(
          original,
          catalog
              .findAssociation(first.snapshotId(), first.role(), NONE)
              .orElseThrow()
              .descriptor()
              .association()
              .build());
    }
  }

  @Test
  void existingPendingPublishingAndRecoverySlotsAdoptLaterCanonicalOriginWithSameToken()
      throws Exception {
    var factory = new SqliteCatalog(directory.resolve("catalog.sqlite"));
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteSelectedFileCatalog(factory, store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      var publishingSlot = new ContentSlotKey("wow", "d".repeat(32));
      var publishing = catalog.reserve(publishingSlot, A, bytes.length, NONE);
      catalog.markPublishing(publishing.token(), NONE);
      var earlierProof = prospective(publishingSlot, A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(publishing.token(), earlierProof, NONE);
      var recoverySlot = new ContentSlotKey("wow", "e".repeat(32));
      var recovery = catalog.reserve(recoverySlot, A, bytes.length, NONE);
      catalog.markUncertain(recovery.token(), NONE);
      var originSlot = new ContentSlotKey("wow", "f".repeat(32));
      var origin = catalog.reserve(originSlot, A, bytes.length, NONE);
      catalog.markPublishing(origin.token(), NONE);
      var proof = prospective(originSlot, A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(origin.token(), proof, NONE);
      publish(store, bytes);
      catalog.completePublication(origin.token(), proof, NONE);
      for (var reservation : List.of(pending, publishing, recovery)) {
        var adopted = catalog.reserve(reservation.slot(), A, bytes.length, NONE);
        assertEquals(reservation.token(), adopted.token());
        assertEquals(CanonicalReservation.Status.RETAINED, adopted.status());
        assertEquals(proof.encoded(), adopted.representation().orElseThrow().encoded());
        assertEquals(proof.sourceReceipt(), adopted.representation().orElseThrow().sourceReceipt());
        assertEquals(reservation.slot(), adopted.representation().orElseThrow().slot());
      }
    }
  }

  @Test
  void lateOriginAdoptionRejectsConflictingProspectiveBytesWithoutChangingPendingProof()
      throws Exception {
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog =
          new SqliteSelectedFileCatalog(
              new SqliteCatalog(directory.resolve("catalog.sqlite")), store);
      byte[] bytes = "fixture".getBytes();
      var pending = catalog.reserve(slot(), A, bytes.length, NONE);
      catalog.markPublishing(pending.token(), NONE);
      var conflicting = prospective(slot(), A, "changed".getBytes(), "kr.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(pending.token(), conflicting, NONE);
      var otherSlot = new ContentSlotKey("wow", "d".repeat(32));
      var origin = catalog.reserve(otherSlot, A, bytes.length, NONE);
      catalog.markPublishing(origin.token(), NONE);
      var proof = prospective(otherSlot, A, bytes, "us.cdn.blizzard.com");
      catalog.recordVerifiedCompletion(origin.token(), proof, NONE);
      publish(store, bytes);
      catalog.completePublication(origin.token(), proof, NONE);
      assertEquals(
          SelectedFileException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  SelectedFileException.class, () -> catalog.reserve(slot(), A, bytes.length, NONE))
              .code());
      var unchanged = catalog.lookup(slot(), NONE).orElseThrow();
      assertEquals(pending.token(), unchanged.token());
      assertEquals(conflicting, unchanged.representation().orElseThrow());
      assertEquals(CanonicalReservation.Status.PUBLISHING, unchanged.status());
    }
  }
}
