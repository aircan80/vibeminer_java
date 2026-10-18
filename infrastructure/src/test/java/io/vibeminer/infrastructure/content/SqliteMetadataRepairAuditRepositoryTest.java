package io.vibeminer.infrastructure.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteMetadataRepairAuditRepositoryTest {
  @TempDir Path directory;
  static final CancellationToken NONE = CancellationToken.none();
  static final Instant NOW = MetadataRepairAuditRecordsTest.NOW;

  static class MutableClock extends Clock {
    volatile Instant now = NOW;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  class Fixture implements AutoCloseable {
    final SqliteMetadataAssociationRepositoryTest owner =
        new SqliteMetadataAssociationRepositoryTest();
    final SqliteMetadataAssociationRepositoryTest.Fixture source;
    final SqliteMetadataRepairAuditRepository audits;
    final MutableClock clock = new MutableClock();
    final MetadataRepairPlan plan;
    final ContentWorkerPermit permit;

    Fixture() throws Exception {
      this(MetadataStructureAttestation.Kind.ROOT);
    }

    Fixture(MetadataStructureAttestation.Kind kind) throws Exception {
      owner.directory = directory;
      source = owner.new Fixture(kind);
      audits =
          new SqliteMetadataRepairAuditRepository(
              source.catalog, source.store, source.store.contentSession(), clock);
      var a = source.association;
      var original = a.actualRepresentation();
      var d = a.declaration();
      var request =
          new MetadataContentRequest(
              d.build(),
              d.configuration(),
              kind,
              d.slot(),
              original.actualEncodingKey(),
              original.encodedBytes(),
              OptionalLong.of(64));
      plan =
          new MetadataRepairPlan(
              UUID.randomUUID(),
              a.canonicalReservationToken(),
              request,
              original,
              new MetadataRepairSourceObservation(
                  MetadataRepairAuditRecordsTest.url(original.actualEncodingKey()),
                  original.actualEncodingKey(),
                  80,
                  NOW,
                  "1".repeat(64)),
              new MetadataRepairCost(80, 80, 4, 128, Duration.ofMinutes(2)),
              NOW,
              NOW.plusSeconds(300));
      permit = source.canonical.claimWorker(plan.reservationToken(), NONE);
    }

    MetadataRepairObservation observation(UUID attempt, boolean grammar) {
      var value = MetadataRepairAuditRecordsTest.observation(attempt, plan, grammar);
      var receipt = value.actualTransfer();
      return new MetadataRepairObservation(
          attempt,
          value.originalProof(),
          new VerifiedSourceAlias(
              receipt.source(),
              receipt.object(),
              new SourceVerificationEvidence(
                  receipt.evidence().verifierSemantics(),
                  receipt.evidence().sourceUrl(),
                  clock.instant())),
          value.decodedAttestation(),
          value.structuralAttestation(),
          value.payloadRequestCount(),
          value.payloadResponseBytes(),
          value.observationDigest());
    }

    public void close() {
      source.close();
    }
  }

  long count(SqliteCatalog catalog, String table) throws Exception {
    try (var c = catalog.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      return r.getLong(1);
    }
  }

  byte[] proof(SqliteCatalog catalog) throws Exception {
    try (var c = catalog.connect();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT representation FROM canonical_content_slots")) {
      return r.getBytes(1);
    }
  }

  @Test
  void rootProspectiveGrammarCompletionAppendOnlyWithoutCanonicalOrMembershipMutation()
      throws Exception {
    try (var f = new Fixture()) {
      var before = proof(f.source.catalog);
      var attempt = f.audits.begin(f.permit, f.plan, NONE);
      var decoded = f.observation(attempt, false);
      var grammar = f.observation(attempt, true);
      f.clock.now = NOW.plusSeconds(2);
      f.audits.recordProspective(f.permit, attempt, decoded, NONE);
      assertEquals(1, f.audits.find(attempt, NONE).orElseThrow().events().size());
      assertThrows(
          MetadataRepairException.class, () -> f.audits.complete(f.permit, attempt, grammar, NONE));
      f.audits.recordStructure(f.permit, attempt, grammar, NONE);
      var completed = f.audits.complete(f.permit, attempt, grammar, NONE);
      assertEquals(3, completed.events().size());
      assertEquals(MetadataRepairAuditEvent.Kind.COMPLETED, completed.events().getLast().kind());
      f.audits.fail(f.permit, attempt, MetadataAcquisitionFailure.Publication.UNCERTAIN, NONE);
      assertEquals(completed, f.audits.find(attempt, NONE).orElseThrow());
      assertArrayEquals(before, proof(f.source.catalog));
      assertEquals(1, count(f.source.catalog, "build_object_memberships"));
      assertEquals(0, count(f.source.catalog, "metadata_build_associations"));
      assertEquals(0, count(f.source.catalog, "retention_scopes"));
      assertEquals(2, count(f.source.catalog, "source_aliases"));
    }
  }

  @Test
  void realRepairGateDurablyRecordsOriginalShaBeforePromotionAndCompletesAfterSecondPass()
      throws Exception {
    try (var f = new Fixture()) {
      var attempt = f.audits.begin(f.permit, f.plan, NONE);
      var digest = f.plan.originalProof().object().digest().value();
      var path =
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2));
      Files.write(path, new byte[] {9});
      f.clock.now = NOW.plusSeconds(2);
      var decoded = f.observation(attempt, false);
      f.source.store.repairVerified(
          new ByteArrayInputStream(new byte[80]),
          f.plan.originalProof().object(),
          80,
          t -> {
            f.audits.recordProspective(f.permit, attempt, decoded, t);
            assertEquals(1, f.audits.find(attempt, NONE).orElseThrow().events().size());
            assertArrayEquals(new byte[] {9}, assertDoesNotThrow(() -> Files.readAllBytes(path)));
          },
          NONE);
      var grammar = f.observation(attempt, true);
      f.audits.recordStructure(f.permit, attempt, grammar, NONE);
      f.audits.complete(f.permit, attempt, grammar, NONE);
      assertArrayEquals(new byte[80], Files.readAllBytes(path));
      assertEquals(
          f.plan.originalProof(),
          f.source
              .canonical
              .lookup(f.plan.request().slot(), NONE)
              .orElseThrow()
              .proof()
              .orElseThrow());
    }
  }

  @Test
  void foreignOrStaleNonceCannotBeginAppendCompleteOrFail() throws Exception {
    try (var f = new Fixture()) {
      var wrong = new ContentWorkerPermit(f.permit.reservationToken(), UUID.randomUUID());
      assertThrows(ContentClaimException.class, () -> f.audits.begin(wrong, f.plan, NONE));
      var attempt = f.audits.begin(f.permit, f.plan, NONE);
      var observation = f.observation(attempt, false);
      f.clock.now = NOW.plusSeconds(2);
      assertThrows(
          ContentClaimException.class,
          () -> f.audits.recordProspective(wrong, attempt, observation, NONE));
      assertThrows(
          ContentClaimException.class,
          () ->
              f.audits.fail(
                  wrong,
                  attempt,
                  MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                  NONE));
      f.source.canonical.finishWorker(f.permit, ContentWorkerFinishOutcome.RETAINED, NONE);
      assertThrows(
          ContentClaimException.class,
          () -> f.audits.recordProspective(f.permit, attempt, observation, NONE));
      assertEquals(0, f.audits.find(attempt, NONE).orElseThrow().events().size());
    }
  }

  @Test
  void installCompletesFromInGateGrammarAndFailureAfterProspectiveStaysUncertain()
      throws Exception {
    try (var f = new Fixture(MetadataStructureAttestation.Kind.INSTALL)) {
      var attempt = f.audits.begin(f.permit, f.plan, NONE);
      var grammar = f.observation(attempt, true);
      f.clock.now = NOW.plusSeconds(2);
      f.audits.recordProspective(f.permit, attempt, grammar, NONE);
      assertEquals(2, f.audits.complete(f.permit, attempt, grammar, NONE).events().size());
      var failed = f.audits.begin(f.permit, f.plan, NONE);
      f.audits.recordProspective(f.permit, failed, f.observation(failed, true), NONE);
      f.audits.fail(
          f.permit, failed, MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, NONE);
      assertEquals(
          MetadataRepairAuditEvent.Kind.UNCERTAIN,
          f.audits.find(failed, NONE).orElseThrow().events().getLast().kind());
    }
  }

  @Test
  void changedTransferRouteAttemptOrGrammarCannotAppendAndConclusiveFailureHasNoFakeReceipt()
      throws Exception {
    try (var f = new Fixture()) {
      var attempt = f.audits.begin(f.permit, f.plan, NONE);
      f.clock.now = NOW.plusSeconds(2);
      assertThrows(
          MetadataRepairException.class,
          () -> f.audits.recordProspective(f.permit, attempt, f.observation(attempt, true), NONE));
      assertThrows(
          MetadataRepairException.class,
          () -> f.audits.recordStructure(f.permit, attempt, f.observation(attempt, true), NONE));
      assertThrows(
          MetadataRepairException.class,
          () ->
              f.audits.recordProspective(
                  f.permit, attempt, f.observation(UUID.randomUUID(), false), NONE));
      var value = f.observation(attempt, false);
      var transfer = value.actualTransfer();
      var other =
          new VerifiedSourceAlias(
              transfer.source(),
              transfer.object(),
              new SourceVerificationEvidence(
                  transfer.evidence().verifierSemantics(),
                  transfer.evidence().sourceUrl().replace("us.cdn", "kr.cdn"),
                  transfer.evidence().verifiedAt()));
      var wrongRoute =
          new MetadataRepairObservation(
              attempt,
              value.originalProof(),
              other,
              value.decodedAttestation(),
              Optional.empty(),
              value.payloadRequestCount(),
              value.payloadResponseBytes(),
              value.observationDigest());
      assertThrows(
          MetadataRepairException.class,
          () -> f.audits.recordProspective(f.permit, attempt, wrongRoute, NONE));
      assertEquals(0, f.audits.find(attempt, NONE).orElseThrow().events().size());
      f.audits.fail(
          f.permit, attempt, MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, NONE);
      var event = f.audits.find(attempt, NONE).orElseThrow().events().getFirst();
      assertEquals(MetadataRepairAuditEvent.Kind.CONCLUSIVELY_UNPUBLISHED, event.kind());
      assertTrue(event.observation().isEmpty());
      assertThrows(
          MetadataRepairException.class,
          () -> f.audits.recordProspective(f.permit, attempt, value, NONE));
    }
  }

  @Test
  void callbackRefusalLeavesDamagedOriginalAndNoPromotedObjectOrSuccessEvent() throws Exception {
    try (var f = new Fixture()) {
      var attempt = f.audits.begin(f.permit, f.plan, NONE);
      var digest = f.plan.originalProof().object().digest().value();
      var path =
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2));
      Files.write(path, new byte[] {7});
      f.clock.now = NOW.plusSeconds(2);
      try (var c = f.source.catalog.connect();
          var s = c.createStatement()) {
        s.execute(
            "CREATE TRIGGER reject_repair_audit BEFORE INSERT ON metadata_repair_events BEGIN"
                + " SELECT RAISE(ABORT,'test'); END");
      }
      assertThrows(
          ContentClaimException.class,
          () ->
              f.source.store.repairVerified(
                  new ByteArrayInputStream(new byte[80]),
                  f.plan.originalProof().object(),
                  80,
                  t ->
                      f.audits.recordProspective(
                          f.permit, attempt, f.observation(attempt, false), t),
                  NONE));
      assertArrayEquals(new byte[] {7}, Files.readAllBytes(path));
      assertEquals(0, f.audits.find(attempt, NONE).orElseThrow().events().size());
    }
  }

  @Test
  void finalCommitCancellationRollsBackCompletionAfterPhysicalVerification() throws Exception {
    try (var f = new Fixture()) {
      var first = f.audits.begin(f.permit, f.plan, NONE);
      f.clock.now = NOW.plusSeconds(2);
      var firstDecoded = f.observation(first, false);
      var firstGrammar =
          firstDecoded.withStructuralAttestation(
              new MetadataStructureAttestation(f.plan.request().kind(), "fresh-grammar-v1"));
      f.audits.recordProspective(f.permit, first, firstDecoded, NONE);
      f.audits.recordStructure(f.permit, first, firstGrammar, NONE);
      var checks = new java.util.concurrent.atomic.AtomicInteger();
      f.audits.complete(
          f.permit,
          first,
          firstGrammar,
          () -> {
            checks.incrementAndGet();
            return false;
          });
      int last = checks.get();
      assertTrue(last > 3);
      var second = f.audits.begin(f.permit, f.plan, NONE);
      var decoded = f.observation(second, false);
      var grammar =
          decoded.withStructuralAttestation(
              new MetadataStructureAttestation(f.plan.request().kind(), "fresh-grammar-v1"));
      f.audits.recordProspective(f.permit, second, decoded, NONE);
      f.audits.recordStructure(f.permit, second, grammar, NONE);
      checks.set(0);
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              f.audits.complete(f.permit, second, grammar, () -> checks.incrementAndGet() >= last));
      assertEquals(last, checks.get());
      assertEquals(2, f.audits.find(second, NONE).orElseThrow().events().size());
      assertEquals(3, f.audits.complete(f.permit, second, grammar, NONE).events().size());
    }
  }

  @Test
  void missingOrCorruptBytesPreventCompletionWithoutDeletingProspectiveHistory() throws Exception {
    try (var f = new Fixture()) {
      var attempt = f.audits.begin(f.permit, f.plan, NONE);
      f.clock.now = NOW.plusSeconds(2);
      var decoded = f.observation(attempt, false);
      var grammar =
          decoded.withStructuralAttestation(
              new MetadataStructureAttestation(f.plan.request().kind(), "fresh-grammar-v1"));
      f.audits.recordProspective(f.permit, attempt, decoded, NONE);
      f.audits.recordStructure(f.permit, attempt, grammar, NONE);
      var digest = f.plan.originalProof().object().digest().value();
      var path =
          directory
              .resolve("store/objects")
              .resolve(digest.substring(0, 2))
              .resolve(digest.substring(2));
      Files.delete(path);
      assertEquals(
          ContentClaimException.Code.MISSING_LOCAL,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.audits.complete(f.permit, attempt, grammar, NONE))
              .code());
      Files.write(path, new byte[] {8});
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertThrows(
                  ContentClaimException.class,
                  () -> f.audits.complete(f.permit, attempt, grammar, NONE))
              .code());
      assertEquals(2, f.audits.find(attempt, NONE).orElseThrow().events().size());
    }
  }

  @Test
  void newStoreEpochKeepsUncertainAuditReadableButCannotFinishOldWorkersAttempt() throws Exception {
    UUID attempt;
    MetadataRepairPlan plan;
    ContentWorkerPermit stale;
    MetadataRepairObservation decoded;
    try (var f = new Fixture()) {
      attempt = f.audits.begin(f.permit, f.plan, NONE);
      plan = f.plan;
      stale = f.permit;
      f.clock.now = NOW.plusSeconds(2);
      decoded = f.observation(attempt, false);
      f.audits.recordProspective(f.permit, attempt, decoded, NONE);
    }
    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var catalog = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(catalog, cap);
      var canonical = new SqliteCanonicalContentRepository(catalog, store, cap);
      var audit =
          new SqliteMetadataRepairAuditRepository(
              catalog, store, cap, Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));
      assertEquals(1, audit.find(attempt, NONE).orElseThrow().events().size());
      assertThrows(
          ContentClaimException.class,
          () -> audit.fail(stale, attempt, MetadataAcquisitionFailure.Publication.UNCERTAIN, NONE));
      var fresh = canonical.claimWorker(plan.reservationToken(), NONE);
      assertThrows(
          ContentClaimException.class,
          () -> audit.fail(fresh, attempt, MetadataAcquisitionFailure.Publication.UNCERTAIN, NONE));
      var newAttempt = audit.begin(fresh, plan, NONE);
      assertNotEquals(attempt, newAttempt);
      assertEquals(0, audit.find(newAttempt, NONE).orElseThrow().events().size());
      assertEquals(
          plan.originalProof(),
          canonical.lookup(plan.request().slot(), NONE).orElseThrow().proof().orElseThrow());
    }
  }

  @Test
  void admissionIsBoundedAndExpiredConsentCannotCreateAudit() throws Exception {
    try (var f = new Fixture()) {
      for (int i = 0; i < MetadataRepairAuditRepository.MAXIMUM_ATTEMPTS_PER_SLOT; i++)
        f.audits.begin(f.permit, f.plan, NONE);
      assertEquals(
          MetadataRepairException.Code.LIMIT,
          assertThrows(MetadataRepairException.class, () -> f.audits.begin(f.permit, f.plan, NONE))
              .code());
      assertEquals(128, count(f.source.catalog, "metadata_repair_attempts"));
      f.clock.now = f.plan.expiresAt();
      assertEquals(
          MetadataRepairException.Code.INVALID_PLAN,
          assertThrows(MetadataRepairException.class, () -> f.audits.begin(f.permit, f.plan, NONE))
              .code());
    }
  }

  @Test
  void migrationTenToElevenAddsEmptyAuditWithoutTouchingPriorAssociationOrSourceProofs()
      throws Exception {
    try (var f = new Fixture()) {
      f.source.associations.attach(f.source.association, f.source.scope, NONE);
      var before = proof(f.source.catalog);
      try (var c = f.source.catalog.connect();
          var s = c.createStatement()) {
        s.execute("DROP TABLE metadata_repair_events");
        s.execute("DROP TABLE metadata_repair_attempts");
        s.execute("PRAGMA user_version=10");
      }
      assertEquals(0, count(f.source.catalog, "metadata_repair_attempts"));
      assertEquals(0, count(f.source.catalog, "metadata_repair_events"));
      assertArrayEquals(before, proof(f.source.catalog));
      assertEquals(
          f.source.association,
          f.source
              .associations
              .find(f.source.scope.snapshotId(), f.source.association.declaration().kind(), NONE)
              .orElseThrow());
      assertEquals(1, count(f.source.catalog, "retention_scopes"));
      assertEquals(2, count(f.source.catalog, "build_object_memberships"));
      var id = f.audits.begin(f.permit, f.plan, NONE);
      assertNotNull(id);
    }
  }

  @Test
  void storedBaseAndEventChecksumDamageFailsTypedWithoutRepairingHistory() throws Exception {
    try (var f = new Fixture()) {
      var id = f.audits.begin(f.permit, f.plan, NONE);
      f.clock.now = NOW.plusSeconds(2);
      f.audits.recordProspective(f.permit, id, f.observation(id, false), NONE);
      try (var c = f.source.catalog.connect();
          var s = c.createStatement()) {
        s.execute("UPDATE metadata_repair_events SET record_sha256='" + "0".repeat(64) + "'");
      }
      assertEquals(
          MetadataRepairException.Code.INTEGRITY,
          assertThrows(MetadataRepairException.class, () -> f.audits.find(id, NONE)).code());
      assertEquals(1, count(f.source.catalog, "metadata_repair_events"));
    }
  }

  @Test
  void historicalMissingCapabilitiesStayMissingInCanonicalProofAfterFreshCompleteAudit()
      throws Exception {
    try (var f = new Fixture()) {
      var old = f.plan.originalProof();
      var partial =
          new MetadataContentProof(
              old.originalAlias(),
              old.actualEncodingKey(),
              old.contentKey(),
              old.declaredDecodedBytes(),
              Optional.empty(),
              Optional.empty());
      try (var c = f.source.catalog.connect();
          var q = c.prepareStatement("UPDATE canonical_content_slots SET representation=?")) {
        q.setBytes(1, CanonicalContentRecords.encode(partial));
        q.executeUpdate();
      }
      var p =
          new MetadataRepairPlan(
              f.plan.issuanceId(),
              f.plan.reservationToken(),
              f.plan.request(),
              partial,
              f.plan.sourceObservation(),
              f.plan.cost(),
              f.plan.issuedAt(),
              f.plan.expiresAt());
      var id = f.audits.begin(f.permit, p, NONE);
      var decoded = MetadataRepairAuditRecordsTest.observation(id, p, false);
      var grammar =
          decoded.withStructuralAttestation(
              new MetadataStructureAttestation(p.request().kind(), "fresh-grammar-v1"));
      f.clock.now = NOW.plusSeconds(2);
      f.audits.recordProspective(f.permit, id, decoded, NONE);
      f.audits.recordStructure(f.permit, id, grammar, NONE);
      var result = f.audits.complete(f.permit, id, grammar, NONE);
      assertEquals(partial, result.issued().originalProof());
      assertTrue(result.issued().originalProof().decodedAttestation().isEmpty());
      assertTrue(
          result
              .events()
              .getLast()
              .observation()
              .orElseThrow()
              .structuralAttestation()
              .isPresent());
      assertEquals(
          partial,
          f.source.canonical.lookup(p.request().slot(), NONE).orElseThrow().proof().orElseThrow());
    }
  }

  @Test
  void canceledFacadeCannotAdmitAnotherWorkerOrDiscardActualBlockedRepairAudit() throws Exception {
    try (var f = new Fixture();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var id = f.audits.begin(f.permit, f.plan, NONE);
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var retired = new java.util.concurrent.CountDownLatch(1);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      var future =
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                try (var input =
                    new FilterInputStream(new ByteArrayInputStream(new byte[80])) {
                      boolean first = true;

                      @Override
                      public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (first) {
                          first = false;
                          entered.countDown();
                          try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                              throw new IOException("test gate timed out");
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                          }
                        }
                        return super.read(bytes, offset, length);
                      }
                    }) {
                  f.clock.now = NOW.plusSeconds(2);
                  var decoded = f.observation(id, false);
                  f.source.store.repairVerified(
                      input,
                      f.plan.originalProof().object(),
                      80,
                      t -> f.audits.recordProspective(f.permit, id, decoded, t),
                      NONE);
                  var grammar =
                      decoded.withStructuralAttestation(
                          new MetadataStructureAttestation(
                              f.plan.request().kind(), "fresh-grammar-v1"));
                  f.audits.recordStructure(f.permit, id, grammar, NONE);
                  f.audits.complete(f.permit, id, grammar, NONE);
                } catch (Throwable error) {
                  failure.set(error);
                } finally {
                  try {
                    f.source.canonical.finishWorker(
                        f.permit, ContentWorkerFinishOutcome.RETAINED, NONE);
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                  retired.countDown();
                }
              },
              executor);
      try {
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        assertEquals(1, retired.getCount());
        var other =
            new SqliteCanonicalContentRepository(
                f.source.catalog, f.source.store, f.source.store.contentSession());
        assertEquals(
            ContentClaimException.Code.BUSY,
            assertThrows(
                    ContentClaimException.class,
                    () -> other.claimWorker(f.plan.reservationToken(), NONE))
                .code());
        var otherAudit =
            new SqliteMetadataRepairAuditRepository(
                f.source.catalog, f.source.store, f.source.store.contentSession(), f.clock);
        var forged = new ContentWorkerPermit(f.plan.reservationToken(), UUID.randomUUID());
        assertThrows(
            ContentClaimException.class,
            () ->
                otherAudit.fail(
                    forged,
                    id,
                    MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                    NONE));
        assertEquals(0, otherAudit.find(id, NONE).orElseThrow().events().size());
      } finally {
        release.countDown();
      }
      assertTrue(retired.await(10, java.util.concurrent.TimeUnit.SECONDS));
      assertNull(failure.get());
      assertEquals(3, f.audits.find(id, NONE).orElseThrow().events().size());
    }
  }

  @Test
  void consentExpiringWhileWaitingForWriteReservationCannotAdmitBackdatedAttempt()
      throws Exception {
    try (var f = new Fixture();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var blocker = f.source.catalog.connect()) {
      var observed = new java.util.concurrent.CountDownLatch(1);
      var waitingClock =
          new MutableClock() {
            @Override
            public Instant instant() {
              var value = super.instant();
              observed.countDown();
              return value;
            }
          };
      var repository =
          new SqliteMetadataRepairAuditRepository(
              f.source.catalog, f.source.store, f.source.store.contentSession(), waitingClock);
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("UPDATE canonical_content_slots SET status=status");
      }
      var result = executor.submit(() -> repository.begin(f.permit, f.plan, NONE));
      try {
        assertTrue(observed.await(10, java.util.concurrent.TimeUnit.SECONDS));
        waitingClock.now = f.plan.expiresAt();
      } finally {
        blocker.setAutoCommit(true);
      }
      var failure =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertInstanceOf(MetadataRepairException.class, failure.getCause());
      assertEquals(
          MetadataRepairException.Code.INVALID_PLAN,
          ((MetadataRepairException) failure.getCause()).code());
      assertEquals(0, count(f.source.catalog, "metadata_repair_attempts"));
    }
  }
}
