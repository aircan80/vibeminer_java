package io.vibeminer.application.content;

import static io.vibeminer.application.content.ContentContractsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DefaultMetadataRepairServiceTest {
  static final Instant NOW = MetadataRepairContractsTest.NOW;
  final List<String> events = new CopyOnWriteArrayList<>();
  final AtomicLong nanos = new AtomicLong();
  final UUID reservation = UUID.randomUUID();
  MetadataContentProof original = historical();
  ContentClaim claim;
  ContentWorkerPermit owner;
  volatile boolean healthy;
  boolean corrupt, failLocal, skipGrammar, persistThenThrow;
  RuntimeException storageFailure, configurationFailure, executionFailure;
  CountDownLatch entered, release;
  MetadataRepairPlan issued;
  UUID attempt;
  final List<MetadataRepairAuditEvent> auditEvents = new ArrayList<>();
  final LookupBudgets budgets = LookupBudgets.defaults();
  MetadataStructureAttestation.Kind kind = MetadataStructureAttestation.Kind.ROOT;
  RuntimeException publicationFailure, finishFailure, failBeforeGate;
  Runnable publicationHook;
  CountDownLatch retired;
  RetentionScope pinned;
  MetadataRepairAudit completedAudit;

  @SuppressWarnings("unchecked")
  <T> T port(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  RootSelectionPlan root() {
    var candidate = new RootCandidate(E, 80, 0);
    return new RootSelectionPlan(
        new RootPlan("fixture", build("wow"), configuration(), C, 100, List.of(candidate), budgets),
        candidate);
  }

  MetadataDeclaration declaration() {
    return new MetadataDeclaration(
        build("wow"),
        configuration(),
        kind,
        new ContentSlotKey("wow", C),
        kind == MetadataStructureAttestation.Kind.ROOT ? Optional.empty() : Optional.of(E),
        kind == MetadataStructureAttestation.Kind.ROOT ? OptionalLong.empty() : OptionalLong.of(80),
        OptionalLong.of(100));
  }

  MetadataContentProof verification() {
    return new MetadataContentProof(
        original.originalAlias(),
        E,
        C,
        original.declaredDecodedBytes(),
        Optional.of(new DecodedContentAttestation(C, 100, "fresh-decoded")),
        Optional.of(new MetadataStructureAttestation(kind, "fresh-grammar")));
  }

  DefaultMetadataRepairService service(Executor executor) {
    claim =
        new ContentClaim(
            new ContentSlotKey("wow", C),
            E,
            80,
            reservation,
            CanonicalReservation.Status.RETAINED,
            Optional.of(original));
    var builds =
        port(
            BuildObjectCatalog.class,
            (p, m, a) -> {
              assertEquals("findVerifiedObject", m.getName());
              if (configurationFailure != null) throw configurationFailure;
              var c = configuration();
              return Optional.of(
                  new BuildObjectReference(
                      build("wow").snapshotId(),
                      "BUILD_CONFIG",
                      c.source(),
                      c.object(),
                      c.evidence()));
            });
    var store =
        port(
            ObjectStore.class,
            (p, m, a) -> {
              assertEquals("verify", m.getName());
              events.add("probe");
              if (storageFailure != null) throw storageFailure;
              if (corrupt)
                throw new ObjectStoreException(
                    ObjectStoreException.Code.INTEGRITY_FAILURE, "damaged");
              return healthy ? Optional.of(original.object()) : Optional.empty();
            });
    var canonical =
        port(
            CanonicalContentRepository.class,
            (p, m, a) -> {
              synchronized (this) {
                switch (m.getName()) {
                  case "lookup":
                    return Optional.of(claim);
                  case "claimWorker":
                    if (owner != null)
                      throw new ContentClaimException(ContentClaimException.Code.BUSY, "owned");
                    events.add("claim");
                    owner = new ContentWorkerPermit((UUID) a[0], UUID.randomUUID());
                    return owner;
                  case "completePublication":
                    assertEquals(owner, a[0]);
                    if (publicationFailure != null) throw publicationFailure;
                    events.add("canonical");
                    var proof = (MetadataContentProof) a[1];
                    ContentProofPolicy.requireMonotonicUpgrade(claim.proof().orElseThrow(), proof);
                    claim =
                        new ContentClaim(
                            claim.slot(),
                            E,
                            80,
                            reservation,
                            CanonicalReservation.Status.RETAINED,
                            Optional.of(proof));
                    if (publicationHook != null) publicationHook.run();
                    return claim;
                  case "finishWorker":
                    assertEquals(owner, a[0]);
                    events.add("retire");
                    owner = null;
                    if (retired != null) retired.countDown();
                    if (finishFailure != null) throw finishFailure;
                    return null;
                  default:
                    throw new AssertionError("Unexpected canonical operation " + m);
                }
              }
            });
    var declarations =
        port(
            MetadataReuseGateway.class,
            (p, m, a) -> {
              if (m.getName().equals("readDeclaration")) return declaration();
              assertEquals("validateDeclaration", m.getName());
              events.add("declaration");
              return null;
            });
    var issuer =
        port(
            MetadataRepairGateway.class,
            (p, m, a) -> {
              if (m.getName().startsWith("prepare")) {
                events.add("HEAD");
                var request =
                    new MetadataContentRequest(
                        build("wow"),
                        configuration(),
                        kind,
                        new ContentSlotKey("wow", C),
                        E,
                        80,
                        OptionalLong.of(100));
                issued =
                    new MetadataRepairPlan(
                        UUID.randomUUID(),
                        reservation,
                        request,
                        original,
                        new MetadataRepairSourceObservation(
                            MetadataRepairContractsTest.url(E), E, 80, NOW, "1".repeat(64)),
                        new MetadataRepairCost(
                            80,
                            80,
                            kind == MetadataStructureAttestation.Kind.INSTALL ? 1 : 4,
                            200,
                            Duration.ofMinutes(2)),
                        NOW,
                        NOW.plusSeconds(300));
                return issued;
              }
              events.add("validate-issued");
              assertEquals(issued, a[1]);
              return null;
            });
    var local =
        port(
            MetadataLocalVerificationGateway.class,
            (p, m, a) -> {
              events.add("local");
              if (failLocal)
                throw new ContentClaimException(
                    ContentClaimException.Code.INTEGRITY_FAILURE, "grammar");
              return verification();
            });
    var audit =
        port(
            MetadataRepairAuditRepository.class,
            (p, m, a) -> {
              assertEquals(owner, a[0]);
              switch (m.getName()) {
                case "begin":
                  events.add("begin");
                  attempt = UUID.randomUUID();
                  auditEvents.clear();
                  return attempt;
                case "recordProspective":
                  events.add("prospective");
                  auditEvents.add(
                      new MetadataRepairAuditEvent(
                          1,
                          MetadataRepairAuditEvent.Kind.PROSPECTIVE,
                          NOW,
                          Optional.of((MetadataRepairObservation) a[2])));
                  if (persistThenThrow)
                    throw new MetadataRepairException(
                        MetadataRepairException.Code.INTEGRITY, "after commit");
                  return null;
                case "recordStructure":
                  events.add("grammar");
                  auditEvents.add(
                      new MetadataRepairAuditEvent(
                          2,
                          MetadataRepairAuditEvent.Kind.STRUCTURE,
                          NOW,
                          Optional.of((MetadataRepairObservation) a[2])));
                  return null;
                case "complete":
                  events.add("audit-complete");
                  auditEvents.add(
                      new MetadataRepairAuditEvent(
                          auditEvents.size() + 1,
                          MetadataRepairAuditEvent.Kind.COMPLETED,
                          NOW,
                          Optional.of((MetadataRepairObservation) a[2])));
                  completedAudit = new MetadataRepairAudit(attempt, issued, NOW, auditEvents);
                  return completedAudit;
                case "fail":
                  events.add("audit-fail:" + a[2]);
                  return null;
                default:
                  throw new AssertionError("Unexpected audit method " + m);
              }
            });
    var execution =
        port(
            MetadataRepairExecutionGateway.class,
            (p, m, a) -> {
              events.add("GET");
              if (executionFailure != null) throw executionFailure;
              if (entered != null) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new AssertionError("gate timed out");
              }
              if (failBeforeGate != null)
                throw new MetadataAcquisitionFailure(
                    MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
                    failBeforeGate);
              var callback = (MetadataRepairObserver) a[3];
              var token = (CancellationToken) a[4];
              var receipt =
                  new VerifiedSourceAlias(
                      original.originalAlias().source(),
                      original.object(),
                      new SourceVerificationEvidence(
                          original.originalAlias().evidence().verifierSemantics(),
                          issued.sourceObservation().resourceUrl(),
                          NOW));
              var decoded = new DecodedContentAttestation(C, 100, "fresh-decoded");
              var grammar = new MetadataStructureAttestation(kind, "fresh-grammar");
              var prospective =
                  new MetadataRepairObservation(
                      (UUID) a[2],
                      original,
                      receipt,
                      decoded,
                      kind == MetadataStructureAttestation.Kind.INSTALL
                          ? Optional.of(grammar)
                          : Optional.empty(),
                      1,
                      80,
                      "2".repeat(64));
              try {
                callback.beforePromotion(prospective, token);
              } catch (RuntimeException failure) {
                throw new MetadataAcquisitionFailure(
                    MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, failure);
              }
              healthy = true;
              corrupt = false;
              var full = prospective.withStructuralAttestation(grammar);
              if (!skipGrammar) callback.afterStructureValidation(full, token);
              return full;
            });
    var associations =
        port(
            MetadataAssociationRepository.class,
            (p, m, a) -> {
              assertEquals("attach", m.getName());
              assertNull(owner, "Consumer attachment must follow actual retirement");
              events.add("attach");
              pinned = (RetentionScope) a[1];
              return a[0];
            });
    return new DefaultMetadataRepairService(
        issuer,
        execution,
        local,
        declarations,
        builds,
        canonical,
        audit,
        associations,
        store,
        executor,
        Clock.fixed(NOW, ZoneOffset.UTC),
        nanos::get);
  }

  MetadataRepairPlan prepare(DefaultMetadataRepairService s) {
    return s.prepareRoot(root()).toCompletableFuture().join();
  }

  Throwable failure(CompletionStage<?> stage) {
    return assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join())
        .getCause();
  }

  @Test
  void sourceAuditCompletesBeforeCanonicalAndRetirementBeforeConsumerAttachment() {
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      var response = s.execute(p).toCompletableFuture().join();
      var result = response.association();
      assertSame(completedAudit, response.completedAudit().orElseThrow());
      assertEquals(original.originalAlias(), result.actualRepresentation().originalAlias());
      assertTrue(events.indexOf("prospective") < events.indexOf("grammar"));
      assertTrue(events.indexOf("audit-complete") < events.indexOf("canonical"));
      assertTrue(events.indexOf("canonical") < events.indexOf("retire"));
      assertTrue(events.indexOf("retire") < events.indexOf("attach"));
      assertTrue(result.declaration().preferredEncodingKey().isEmpty());
    }
  }

  @Test
  void initialHealthyNeedsLocalReuseWithZeroHeadOrPayload() {
    healthy = true;
    try (var s = service(Runnable::run)) {
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertInstanceOf(ContentClaimException.class, failure(s.prepareRoot(root()))).code());
      assertFalse(events.contains("HEAD"));
      assertFalse(events.contains("GET"));
      assertFalse(events.contains("claim"));
    }
  }

  @Test
  void restoredExecutionRaceReplaysLocallyWithoutAuditOrPayloadAndAddsMissingOnly() {
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      healthy = true;
      var response = s.execute(p).toCompletableFuture().join();
      var result = response.association();
      assertTrue(response.completedAudit().isEmpty());
      assertTrue(events.contains("local"));
      assertFalse(events.contains("GET"));
      assertFalse(events.contains("begin"));
      assertTrue(result.actualRepresentation().structuralAttestation().isPresent());
    }
  }

  @Test
  void persistedProspectiveThenThrowStaysUncertainAndDoesNotAttach() {
    persistThenThrow = true;
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      var outer = assertInstanceOf(MetadataAcquisitionFailure.class, failure(s.execute(p)));
      assertInstanceOf(MetadataRepairException.class, outer.failure());
      assertTrue(events.contains("audit-fail:UNCERTAIN"));
      assertTrue(events.contains("retire"));
      assertFalse(events.contains("canonical"));
      assertFalse(events.contains("attach"));
    }
  }

  @Test
  void cancelledQueuedFacadeHoldsCapacityUntilActualRunnableRetires() {
    var queue = new ArrayDeque<Runnable>();
    try (var s = service(queue::add)) {
      var first = s.prepareRoot(root()).toCompletableFuture();
      first.cancel(false);
      for (int i = 0; i < 3; i++) s.prepareRoot(root());
      assertEquals(
          ContentClaimException.Code.BUSY,
          assertInstanceOf(ContentClaimException.class, failure(s.prepareRoot(root()))).code());
      queue.remove().run();
      s.prepareRoot(root());
      assertEquals(4, queue.size());
    }
    while (!queue.isEmpty()) queue.remove().run();
  }

  @Test
  void existingSomeCapabilitiesRemainExactDespiteFreshDifferentVerifierRevisions() {
    original =
        original.withAttestations(
            Optional.of(new DecodedContentAttestation(C, 100, "old-decoded")),
            Optional.of(new MetadataStructureAttestation(kind, "old-grammar")));
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      var response = s.execute(p).toCompletableFuture().join();
      var result = response.association();
      assertEquals(original, result.actualRepresentation());
      assertNotEquals(
          original.decodedAttestation().orElseThrow(),
          auditEvents.getLast().observation().orElseThrow().decodedAttestation());
    }
  }

  @Test
  void healthyGrammarFailureDoesNotTriggerDownloadOrAudit() {
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      healthy = true;
      failLocal = true;
      assertInstanceOf(ContentClaimException.class, failure(s.execute(p)));
      assertFalse(events.contains("GET"));
      assertFalse(events.contains("begin"));
      assertFalse(events.contains("attach"));
      assertTrue(events.contains("retire"));
    }
  }

  @Test
  void physicalIoFailureIsNotRepairPermission() {
    storageFailure = new ObjectStoreException(ObjectStoreException.Code.IO_FAILURE, "disk failure");
    try (var s = service(Runnable::run)) {
      var typed = assertInstanceOf(ContentClaimException.class, failure(s.prepareRoot(root())));
      assertEquals(ContentClaimException.Code.STORAGE_UNAVAILABLE, typed.code());
      assertSame(storageFailure, typed.getCause());
      assertFalse(events.contains("HEAD"));
    }
  }

  @Test
  void directConfigurationCatalogFailuresBecomeTypedLocalFailures() {
    for (var code : CatalogException.Code.values()) {
      configurationFailure = new CatalogException(code, "config failure");
      try (var s = service(Runnable::run)) {
        var mapped = assertInstanceOf(ContentClaimException.class, failure(s.prepareRoot(root())));
        var expected =
            switch (code) {
              case MISSING_OBJECT, UNKNOWN_SNAPSHOT -> ContentClaimException.Code.MISSING_LOCAL;
              case INTEGRITY_FAILURE, CORRUPT_METADATA, INVALID_EVIDENCE ->
                  ContentClaimException.Code.INTEGRITY_FAILURE;
              case IMMUTABLE_CONFLICT -> ContentClaimException.Code.REPLAN_REQUIRED;
              case STORAGE_UNAVAILABLE -> ContentClaimException.Code.STORAGE_UNAVAILABLE;
            };
        assertEquals(expected, mapped.code());
        assertSame(configurationFailure, mapped.getCause());
        assertFalse(events.contains("HEAD"));
      }
    }
  }

  @Test
  void directApplicationAndCancellationFailuresRemainExact() {
    for (RuntimeException exact :
        List.of(
            new ContentClaimException(ContentClaimException.Code.BUSY, "local admission"),
            new MetadataRepairException(
                MetadataRepairException.Code.UNAVAILABLE, "HEAD unavailable"),
            new CancellationException("cancelled"))) {
      configurationFailure = exact;
      try (var s = service(Runnable::run)) {
        assertSame(
            exact,
            s.prepareRoot(root()).handle((value, failed) -> failed).toCompletableFuture().join());
        assertFalse(events.contains("HEAD"));
      }
    }
  }

  @Test
  void remotePublicationFailuresKeepExactOuterCertaintyAndRawCause() {
    for (var publication : MetadataAcquisitionFailure.Publication.values()) {
      var remote =
          new FileLookupException(
              FileLookupException.Code.INTEGRITY_FAILURE, "remote encoded mismatch");
      var outer = new MetadataAcquisitionFailure(publication, remote);
      executionFailure = outer;
      try (var s = service(Runnable::run)) {
        var p = prepare(s);
        assertSame(outer, failure(s.execute(p)));
        assertSame(remote, outer.failure());
        assertEquals(publication, outer.publication());
        assertTrue(events.contains("audit-fail:" + publication));
        assertFalse(events.contains("attach"));
      }
      events.clear();
    }
  }

  @Test
  void forgedExpiredAndEvictedPlansFailBeforeWorkerOrPayload() {
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      var forged =
          new MetadataRepairPlan(
              UUID.randomUUID(),
              p.reservationToken(),
              p.request(),
              p.originalProof(),
              p.sourceObservation(),
              p.cost(),
              p.issuedAt(),
              p.expiresAt());
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertInstanceOf(ContentClaimException.class, failure(s.execute(forged))).code());
      nanos.set(Duration.ofMinutes(5).toNanos());
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertInstanceOf(ContentClaimException.class, failure(s.execute(p))).code());
      nanos.set(0);
      for (int i = 0; i < 16; i++) prepare(s);
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertInstanceOf(ContentClaimException.class, failure(s.execute(p))).code());
      assertFalse(events.contains("claim"));
      assertFalse(events.contains("GET"));
    }
  }

  @Test
  void changedCanonicalAuthorityStopsBeforeWorkerAdmission() {
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      claim =
          new ContentClaim(claim.slot(), E, 80, UUID.randomUUID(), claim.status(), claim.proof());
      assertEquals(
          ContentClaimException.Code.REPLAN_REQUIRED,
          assertInstanceOf(ContentClaimException.class, failure(s.execute(p))).code());
      assertFalse(events.contains("claim"));
      assertFalse(events.contains("GET"));
    }
  }

  @Test
  void skippedGrammarDoesNotCompleteAuditOrCanonicalOrAttach() {
    skipGrammar = true;
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      assertEquals(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          assertInstanceOf(ContentClaimException.class, failure(s.execute(p))).code());
      assertTrue(events.contains("audit-fail:UNCERTAIN"));
      assertFalse(events.contains("audit-complete"));
      assertFalse(events.contains("canonical"));
      assertFalse(events.contains("attach"));
      assertTrue(events.contains("retire"));
    }
  }

  @Test
  void conclusiveFailureBeforeProspectiveKeepsCertaintyWhilePreservingOriginal() {
    failBeforeGate = new MetadataRepairException(MetadataRepairException.Code.UNAVAILABLE, "404");
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      var outer = assertInstanceOf(MetadataAcquisitionFailure.class, failure(s.execute(p)));
      assertSame(failBeforeGate, outer.failure());
      assertEquals(
          MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED, outer.publication());
      assertTrue(events.contains("audit-fail:CONCLUSIVELY_UNPUBLISHED"));
      assertFalse(events.contains("prospective"));
      assertFalse(events.contains("attach"));
      assertEquals(original, claim.proof().orElseThrow());
    }
  }

  @Test
  void canonicalFailureAfterAuditDoesNotDowngradeCompletedAudit() {
    publicationFailure =
        new ContentClaimException(ContentClaimException.Code.STORAGE_UNAVAILABLE, "commit refused");
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      assertSame(publicationFailure, failure(s.execute(p)));
      assertTrue(events.contains("audit-complete"));
      assertFalse(events.stream().anyMatch(e -> e.startsWith("audit-fail:")));
      assertFalse(events.contains("attach"));
      assertTrue(events.contains("retire"));
    }
  }

  @Test
  void cleanupFailureIsSuppressedOnOriginalTypedFailure() {
    persistThenThrow = true;
    finishFailure =
        new ContentClaimException(ContentClaimException.Code.STORAGE_UNAVAILABLE, "cleanup");
    try (var s = service(Runnable::run)) {
      var p = prepare(s);
      var failed = failure(s.execute(p));
      var outer = assertInstanceOf(MetadataAcquisitionFailure.class, failed);
      assertInstanceOf(MetadataRepairException.class, outer.failure());
      assertEquals(1, failed.getSuppressed().length);
      assertSame(finishFailure, failed.getSuppressed()[0]);
    }
  }

  @Test
  void cancelledActiveFacadeCannotRetireIgnoredIoWorkerOrAttachConsumer() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var s = service(executor)) {
      var p = prepare(s);
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
      retired = new CountDownLatch(1);
      var future = s.execute(p).toCompletableFuture();
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertNotNull(owner);
        future.cancel(true);
        assertNotNull(owner);
        assertFalse(events.contains("retire"));
      } finally {
        release.countDown();
      }
      assertTrue(retired.await(10, TimeUnit.SECONDS));
      assertFalse(events.contains("attach"));
      assertFalse(events.contains("canonical"));
    }
  }

  @Test
  void closeCannotRetireIgnoredIoWorkerAndDoesNotCloseDependencies() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      var s = service(executor);
      var p = prepare(s);
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
      retired = new CountDownLatch(1);
      var future = s.execute(p).toCompletableFuture();
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        s.close();
        assertTrue(future.isCancelled());
        assertNotNull(owner);
        assertFalse(events.contains("retire"));
      } finally {
        release.countDown();
      }
      assertTrue(retired.await(10, TimeUnit.SECONDS));
      assertFalse(events.contains("attach"));
      assertEquals(
          ContentClaimException.Code.INVALID_STATE,
          assertInstanceOf(ContentClaimException.class, failure(s.prepareRoot(root()))).code());
    }
  }

  @Test
  void installRepeatsInGateGrammarWithoutRootOnlyEvent() {
    kind = MetadataStructureAttestation.Kind.INSTALL;
    var old = original.originalAlias();
    var identity =
        new ResolvedSourceIdentity(
            "tact.install.v1", old.source().immutableSourceKey(), "install-fixture");
    original =
        new MetadataContentProof(
            new VerifiedSourceAlias(
                identity,
                old.object(),
                new SourceVerificationEvidence(
                    identity.verificationSemantics(),
                    old.evidence().sourceUrl(),
                    old.evidence().verifiedAt())),
            E,
            C,
            OptionalLong.of(100),
            Optional.empty(),
            Optional.empty());
    try (var s = service(Runnable::run)) {
      var ordinary =
          new InstallPlan(
              build("wow"),
              new SourceDocument(
                  configuration().evidence().sourceUrl(), Instant.EPOCH, new byte[] {1}),
              C,
              E,
              100,
              80);
      var p = s.prepareInstall(ordinary, budgets).toCompletableFuture().join();
      var response = s.execute(p).toCompletableFuture().join();
      var result = response.association();
      assertFalse(events.contains("grammar"));
      assertEquals(2, auditEvents.size());
      assertEquals(E, result.declaration().preferredEncodingKey().orElseThrow());
      assertEquals(ScopeKind.INSTALL_METADATA, pinned.kind());
    }
  }

  @Test
  void cancellationAfterCanonicalCommitStillRetiresWorkerButSkipsConsumerAttach() throws Exception {
    try (var executor = Executors.newSingleThreadExecutor();
        var service = service(executor)) {
      var plan = prepare(service);
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
      retired = new CountDownLatch(1);
      var result = service.execute(plan).toCompletableFuture();
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        publicationHook = () -> result.cancel(false);
      } finally {
        release.countDown();
      }
      assertTrue(retired.await(10, TimeUnit.SECONDS));
      assertTrue(result.isCancelled());
      assertTrue(events.contains("canonical"));
      assertTrue(events.contains("audit-complete"));
      assertFalse(events.contains("attach"));
      assertTrue(
          claim
              .proof()
              .map(p -> ((MetadataContentProof) p).structuralAttestation().isPresent())
              .orElse(false));
    }
  }
}
