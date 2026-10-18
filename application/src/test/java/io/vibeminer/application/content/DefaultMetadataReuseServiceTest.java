package io.vibeminer.application.content;

import static io.vibeminer.application.content.MetadataReuseContractsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.CanonicalReservation;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DefaultMetadataReuseServiceTest {
  final UUID token = UUID.randomUUID();
  final AtomicInteger reads = new AtomicInteger(),
      replays = new AtomicInteger(),
      attaches = new AtomicInteger();
  final AtomicLong nanos = new AtomicLong();
  boolean missing, changed, badReplay;
  RuntimeException catalogFailure;
  MetadataDeclaration suppliedDeclaration = declaration();
  MetadataContentProof suppliedProof = proof(true, true);
  Clock serviceClock = Clock.fixed(ASSOCIATED, ZoneOffset.UTC);
  RetentionScope scope;
  final MetadataReuseGateway gateway =
      new MetadataReuseGateway() {
        public MetadataDeclaration readDeclaration(
            BuildSnapshot b,
            VerifiedSourceAlias c,
            MetadataStructureAttestation.Kind k,
            LookupBudgets budgets,
            CancellationToken cancellation) {
          reads.incrementAndGet();
          return suppliedDeclaration;
        }

        public void validateDeclaration(
            MetadataDeclaration d, LookupBudgets b, CancellationToken c) {}

        public MetadataContentProof replay(
            MetadataReusePlan p, LookupBudgets b, CancellationToken c) {
          replays.incrementAndGet();
          return badReplay ? proof(true, false) : p.actualRepresentation();
        }
      };

  @SuppressWarnings("unchecked")
  <T> T port(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  DefaultMetadataReuseService service(Executor executor) {
    BuildObjectCatalog builds =
        port(
            BuildObjectCatalog.class,
            (p, m, a) -> {
              if (!m.getName().equals("findVerifiedObject"))
                throw new AssertionError("Unexpected catalog write: " + m);
              if (catalogFailure != null) throw catalogFailure;
              var c = configuration();
              return missing
                  ? Optional.empty()
                  : Optional.of(
                      new BuildObjectReference(
                          build().snapshotId(),
                          "BUILD_CONFIG",
                          c.source(),
                          c.object(),
                          c.evidence()));
            });
    CanonicalContentRepository contents =
        port(
            CanonicalContentRepository.class,
            (p, m, a) -> {
              if (!m.getName().equals("lookup"))
                throw new AssertionError("Unexpected publisher operation: " + m);
              return Optional.of(
                  new ContentClaim(
                      declaration().slot(),
                      ACTUAL,
                      80,
                      changed ? UUID.randomUUID() : token,
                      CanonicalReservation.Status.RETAINED,
                      Optional.of(suppliedProof)));
            });
    MetadataAssociationRepository associations =
        port(
            MetadataAssociationRepository.class,
            (p, m, a) -> {
              assertEquals("attach", m.getName());
              attaches.incrementAndGet();
              scope = (RetentionScope) a[1];
              return a[0];
            });
    return new DefaultMetadataReuseService(
        gateway, builds, contents, associations, executor, serviceClock, nanos::get);
  }

  @Test
  void newManifestKindsCannotEnterLegacyReuseOrReadConfiguration() {
    missing = true;
    try (var service = service(Runnable::run)) {
      for (var kind :
          List.of(
              MetadataStructureAttestation.Kind.ENCODING,
              MetadataStructureAttestation.Kind.DOWNLOAD,
              MetadataStructureAttestation.Kind.DOWNLOAD_SIZE)) {
        assertEquals(
            ContentClaimException.Code.UNSUPPORTED_REUSE,
            failure(service.planRetained(build(), kind, LookupBudgets.defaults())));
      }
    }
    assertEquals(0, reads.get());
    assertEquals(0, replays.get());
    assertEquals(0, attaches.get());
  }

  MetadataReusePlan plan(DefaultMetadataReuseService s) {
    return s.planRetained(
            build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())
        .toCompletableFuture()
        .join();
  }

  ContentClaimException.Code failure(CompletionStage<?> stage) {
    return assertInstanceOf(
            ContentClaimException.class,
            assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join())
                .getCause())
        .code();
  }

  @Test
  void localReplayPreservesActualOriginAndPinsActualCosts() {
    try (var s = service(Runnable::run)) {
      var p = plan(s);
      assertEquals(0, p.additionalPayloadTransferBytes());
      assertEquals(0, attaches.get());
      var result = s.apply(p).toCompletableFuture().join();
      assertEquals(PREFERRED, result.declaration().preferredEncodingKey().orElseThrow());
      assertEquals(ACTUAL, result.actualRepresentation().actualEncodingKey());
      assertEquals(ORIGINAL, result.actualRepresentation().originalAlias().evidence().verifiedAt());
      assertEquals(
          80,
          scope.requirements().stream()
              .filter(r -> r.role().equals("INSTALL_MANIFEST"))
              .findFirst()
              .orElseThrow()
              .encodedBytes()
              .orElseThrow());
      assertEquals(1, replays.get());
      assertEquals(1, attaches.get());
    }
  }

  @Test
  void missingConfigurationNeverInvokesGateway() {
    missing = true;
    try (var s = service(Runnable::run)) {
      assertEquals(
          ContentClaimException.Code.MISSING_LOCAL,
          failure(
              s.planRetained(
                  build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())));
      assertEquals(0, reads.get());
    }
  }

  @Test
  void changedCanonicalTokenRejectsBeforeReplayOrAttach() {
    try (var s = service(Runnable::run)) {
      var p = plan(s);
      changed = true;
      assertEquals(ContentClaimException.Code.REPLAN_REQUIRED, failure(s.apply(p)));
      assertEquals(0, replays.get());
      assertEquals(0, attaches.get());
    }
  }

  @Test
  void forgedAndExpiredPlansCannotReachReplay() {
    try (var s = service(Runnable::run)) {
      var p = plan(s);
      var forged =
          new MetadataReusePlan(
              UUID.randomUUID(),
              p.canonicalReservationToken(),
              p.declaration(),
              p.actualRepresentation());
      assertEquals(ContentClaimException.Code.REPLAN_REQUIRED, failure(s.apply(forged)));
      nanos.set(Duration.ofMinutes(10).toNanos());
      assertEquals(ContentClaimException.Code.REPLAN_REQUIRED, failure(s.apply(p)));
      assertEquals(0, replays.get());
    }
  }

  @Test
  void changedReplayCannotAttach() {
    try (var s = service(Runnable::run)) {
      var p = plan(s);
      badReplay = true;
      assertEquals(ContentClaimException.Code.INTEGRITY_FAILURE, failure(s.apply(p)));
      assertEquals(0, attaches.get());
    }
  }

  @Test
  void cancelledQueuedJobsHoldCapacityUntilActualRetirement() {
    var queue = new ArrayDeque<Runnable>();
    try (var s = service(queue::add)) {
      var first =
          s.planRetained(
                  build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())
              .toCompletableFuture();
      first.cancel(false);
      for (int i = 0; i < 3; i++)
        s.planRetained(
            build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults());
      assertEquals(
          ContentClaimException.Code.BUSY,
          failure(
              s.planRetained(
                  build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())));
      queue.remove().run();
      assertEquals(0, reads.get());
      s.planRetained(build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults());
      s.close();
      while (!queue.isEmpty()) queue.remove().run();
      assertEquals(0, reads.get());
    }
  }

  @Test
  void ledgerEvictsOldestAndCloseDoesNotOwnExecutor() {
    var queue = new ArrayDeque<Runnable>();
    var s = service(Runnable::run);
    var first = plan(s);
    for (int i = 0; i < 16; i++) plan(s);
    assertEquals(ContentClaimException.Code.REPLAN_REQUIRED, failure(s.apply(first)));
    s.close();
    assertEquals(ContentClaimException.Code.INVALID_STATE, failure(s.apply(first)));
  }

  @Test
  void incompleteHistoricalProofNeverBecomesReuseCapability() {
    suppliedProof = proof(false, false);
    try (var s = service(Runnable::run)) {
      assertEquals(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          failure(
              s.planRetained(
                  build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())));
      assertEquals(0, replays.get());
      assertEquals(0, attaches.get());
    }
  }

  @Test
  void unknownRootDeclarationsStayUnknownAfterPlanningAndReplay() {
    var original = proof(true, true);
    var rootAlias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(
                "wow.root.tsfm.v1v2",
                original.originalAlias().source().immutableSourceKey(),
                original.originalAlias().source().verificationSemantics()),
            original.object(),
            original.originalAlias().evidence());
    suppliedProof =
        new MetadataContentProof(
            rootAlias,
            ACTUAL,
            CONTENT,
            OptionalLong.of(100),
            original.decodedAttestation(),
            Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.ROOT, "root-v2-r1")));
    suppliedDeclaration =
        new MetadataDeclaration(
            build(),
            configuration(),
            MetadataStructureAttestation.Kind.ROOT,
            declaration().slot(),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty());
    try (var s = service(Runnable::run)) {
      var p =
          s.planRetained(build(), MetadataStructureAttestation.Kind.ROOT, LookupBudgets.defaults())
              .toCompletableFuture()
              .join();
      assertTrue(p.declaration().preferredEncodingKey().isEmpty());
      assertTrue(p.declaration().declaredEncodedBytes().isEmpty());
      assertTrue(p.declaration().declaredDecodedBytes().isEmpty());
      var result = s.apply(p).toCompletableFuture().join();
      assertEquals(suppliedDeclaration, result.declaration());
      assertEquals(ScopeKind.ROOT_METADATA, scope.kind());
    }
  }

  @Test
  void catalogIntegrityFailureBeforeGatewayKeepsTypedReadinessFailureAndCause() {
    for (var code :
        List.of(
            CatalogException.Code.MISSING_OBJECT,
            CatalogException.Code.INTEGRITY_FAILURE,
            CatalogException.Code.CORRUPT_METADATA)) {
      catalogFailure = null;
      try (var s = service(Runnable::run)) {
        var plan =
            s.planRetained(
                    build(), MetadataStructureAttestation.Kind.INSTALL, LookupBudgets.defaults())
                .toCompletableFuture()
                .join();
        var cause = new CatalogException(code, "local config failed");
        catalogFailure = cause;
        var failed =
            assertThrows(
                CompletionException.class, () -> s.apply(plan).toCompletableFuture().join());
        var typed = assertInstanceOf(ContentClaimException.class, failed.getCause());
        assertEquals(
            code == CatalogException.Code.MISSING_OBJECT
                ? ContentClaimException.Code.MISSING_LOCAL
                : ContentClaimException.Code.INTEGRITY_FAILURE,
            typed.code());
        assertSame(cause, typed.getCause());
        assertEquals(0, attaches.get());
      }
    }
  }
}
