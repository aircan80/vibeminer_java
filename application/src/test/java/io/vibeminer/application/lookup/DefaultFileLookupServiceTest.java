package io.vibeminer.application.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.domain.*;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DefaultFileLookupServiceTest {
  static final LookupBudgets BUDGETS = LookupBudgets.defaults();

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

  static final ResolvedSourceIdentity CONFIG =
      new ResolvedSourceIdentity("test.config", "a".repeat(32), "raw-md5.v1");
  static final ResolvedSourceIdentity ROOT =
      new ResolvedSourceIdentity("test.root", "c:d:10:8", "blte-root.v1");

  static VerifiedSourceAlias receipt(ResolvedSourceIdentity source, int digest) {
    return new VerifiedSourceAlias(
        source,
        new StoredObject(new ObjectDigest(String.format("%064x", digest)), 8),
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/fixture", Instant.EPOCH));
  }

  static final VerifiedSourceAlias CONFIG_RECEIPT = receipt(CONFIG, 1),
      ROOT_RECEIPT = receipt(ROOT, 2);

  static RootPlan plan(BuildSnapshot build) {
    return new RootPlan(
        "session",
        build,
        CONFIG_RECEIPT,
        "c".repeat(32),
        10,
        List.of(new RootCandidate("d".repeat(32), 8, 0)),
        BUDGETS);
  }

  static RootSelectionPlan select(RootPlan plan) {
    return new RootSelectionPlan(plan, plan.candidates().getFirst());
  }

  static final class Queue implements Executor {
    final Deque<Runnable> tasks = new ArrayDeque<>();

    public synchronized void execute(Runnable task) {
      tasks.add(task);
    }

    void drain() {
      while (!tasks.isEmpty()) tasks.remove().run();
    }
  }

  static class Gateway implements FileLookupGateway {
    RootPlan canonical;
    boolean expired;
    int preparations, validations, acquisitions, lookups;
    VerifiedSourceAlias acquired = ROOT_RECEIPT;

    public ResolvedSourceIdentity configurationIdentity(BuildSnapshot build) {
      return CONFIG;
    }

    public RootPlan prepareRoot(
        BuildSnapshot build,
        Optional<VerifiedSourceAlias> retained,
        LookupBudgets budgets,
        CancellationToken token) {
      preparations++;
      canonical = plan(build);
      return canonical;
    }

    public void validateSelection(RootSelectionPlan selection, CancellationToken token) {
      validations++;
      if (expired || !selection.plan().equals(canonical))
        throw new FileLookupException(FileLookupException.Code.INVALID_PLAN, "Unknown proof");
    }

    public ResolvedSourceIdentity rootIdentity(RootSelectionPlan selection) {
      return ROOT;
    }

    public VerifiedSourceAlias acquireRoot(RootSelectionPlan selection, CancellationToken token) {
      acquisitions++;
      return acquired;
    }

    public RootLookupResult lookupRetained(
        BuildSnapshot build,
        VerifiedSourceAlias configuration,
        VerifiedSourceAlias root,
        Set<Long> ids,
        LookupBudgets budgets,
        CancellationToken token) {
      lookups++;
      return new RootLookupResult(
          build,
          root,
          ids,
          List.of(
              new RootVariant(7, "e".repeat(32), 2, 3, 4, 5, 6, Optional.of("0000000000000000")),
              new RootVariant(7, "f".repeat(32), 8, 9, 10, 11, 12, Optional.empty())),
          100);
    }
  }

  static class Catalog implements BuildObjectCatalog {
    final Map<ResolvedSourceIdentity, VerifiedSourceAlias> aliases = new ConcurrentHashMap<>();
    final Map<String, BuildObjectReference> references = new ConcurrentHashMap<>();

    public void registerSnapshot(BuildSnapshot build) {}

    public Optional<VerifiedSourceAlias> findVerifiedSourceAlias(
        ResolvedSourceIdentity source, CancellationToken token) {
      return Optional.ofNullable(aliases.get(source));
    }

    public BuildObjectReference attachVerifiedObject(
        String snapshot,
        String role,
        ResolvedSourceIdentity source,
        StoredObject object,
        SourceVerificationEvidence evidence,
        CancellationToken token) {
      token.throwIfCancellationRequested();
      var receipt = new VerifiedSourceAlias(source, object, evidence);
      var reference = new BuildObjectReference(snapshot, role, source, object, evidence);
      var old = references.putIfAbsent(snapshot + role, reference);
      if (old != null && !old.source().equals(source))
        throw new CatalogException(CatalogException.Code.IMMUTABLE_CONFLICT, "Conflict");
      aliases.putIfAbsent(source, receipt);
      return reference;
    }

    public List<BuildObjectReference> listReferences(String snapshot) {
      return references.values().stream().filter(ref -> ref.snapshotId().equals(snapshot)).toList();
    }

    public Optional<BuildObjectReference> findVerifiedObject(
        String snapshot, String role, CancellationToken token) {
      return Optional.ofNullable(references.get(snapshot + role));
    }
  }

  static final ObjectStore STORE =
      new ObjectStore() {
        public StoredObject publish(
            InputStream stream,
            ExpectedObjectChecksum expected,
            long max,
            CancellationToken token) {
          throw new AssertionError();
        }

        public StoredObject publishVerified(
            InputStream stream,
            long length,
            long max,
            CompletionVerification completion,
            CancellationToken token) {
          throw new AssertionError();
        }

        public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) {
          return Optional.of(
              digest.equals(ROOT_RECEIPT.object().digest())
                  ? ROOT_RECEIPT.object()
                  : CONFIG_RECEIPT.object());
        }

        public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
          throw new AssertionError();
        }

        public void close() {
          throw new AssertionError("Caller owns store");
        }
      };

  static FileLookupException failure(CompletionStage<?> stage) {
    return (FileLookupException)
        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join())
            .getCause();
  }

  @Test
  void planningRetainsOnlyConfigurationThenExplicitRetentionAndOfflineVariants() {
    var gateway = new Gateway();
    var catalog = new Catalog();
    var build = build("one");
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build, BUDGETS).toCompletableFuture().join();
      assertEquals(0, gateway.acquisitions);
      assertEquals(1, catalog.references.size());
      var retained = service.retainRoot(select(plan)).toCompletableFuture().join();
      assertEquals(ROOT_RECEIPT, retained);
      var result =
          service.lookupOffline(build, Set.of(7L, 8L), BUDGETS).toCompletableFuture().join();
      assertEquals(Set.of(8L), result.absentIds());
      assertEquals(2, result.variants().size());
      assertEquals(6, result.variants().getFirst().rawFlagsThird());
      assertEquals(Optional.of("0000000000000000"), result.variants().getFirst().nameHash());
      assertEquals(1, gateway.preparations);
      assertEquals(1, gateway.acquisitions);
    }
  }

  @Test
  void forgedAndExpiredProofFailBeforeTransferOrRootMembership() {
    var gateway = new Gateway();
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      var forged =
          new RootPlan(
              plan.sessionId(),
              plan.build(),
              plan.configuration(),
              "0".repeat(32),
              plan.decodedBytes(),
              plan.candidates(),
              plan.budgets());
      assertEquals(
          FileLookupException.Code.INVALID_PLAN,
          failure(service.retainRoot(select(forged))).code());
      gateway.expired = true;
      assertEquals(
          FileLookupException.Code.INVALID_PLAN, failure(service.retainRoot(select(plan))).code());
      assertEquals(0, gateway.acquisitions);
      assertEquals(1, catalog.references.size());
    }
  }

  @Test
  void alteredConfigurationProofRejectedEvenWithValidGatewaySession() {
    var gateway = new Gateway();
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      catalog.aliases.put(CONFIG, receipt(CONFIG, 99));
      assertEquals(
          FileLookupException.Code.INVALID_PLAN, failure(service.retainRoot(select(plan))).code());
      assertEquals(0, gateway.acquisitions);
    }
  }

  @Test
  void wrongSourceReceiptNeverGetsMembership() {
    var gateway = new Gateway();
    var catalog = new Catalog();
    gateway.acquired = CONFIG_RECEIPT;
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      assertEquals(
          FileLookupException.Code.INTEGRITY_FAILURE,
          failure(service.retainRoot(select(plan))).code());
      assertEquals(1, catalog.references.size());
    }
  }

  @Test
  void durableReusePreservesActualEvidenceAndAttachesExactSecondBuild() {
    var gateway = new Gateway();
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var one = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      service.retainRoot(select(one)).toCompletableFuture().join();
    }
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var two = service.planRoot(build("two"), BUDGETS).toCompletableFuture().join();
      assertEquals(ROOT_RECEIPT, service.retainRoot(select(two)).toCompletableFuture().join());
      assertEquals(1, gateway.acquisitions);
      assertTrue(catalog.references.containsKey(build("two").snapshotId() + "ROOT_MANIFEST"));
    }
  }

  @Test
  void offlineMissingExactBuildNeverPlansOrDownloads() {
    var gateway = new Gateway();
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      assertEquals(
          FileLookupException.Code.MISSING_LOCAL,
          failure(service.lookupOffline(build("missing"), Set.of(7L), BUDGETS)).code());
      assertEquals(0, gateway.preparations + gateway.acquisitions + gateway.lookups);
    }
  }

  @Test
  void queueCancellationCapacityAndClosePreserveCallerResources() {
    var queue = new Queue();
    var gateway = new Gateway();
    var service = new DefaultFileLookupService(gateway, new Catalog(), STORE, queue, queue);
    var pending = new ArrayList<CompletableFuture<RootPlan>>();
    for (int i = 0; i < 4; i++)
      pending.add(service.planRoot(build("one"), BUDGETS).toCompletableFuture());
    pending.forEach(job -> job.cancel(true));
    assertEquals(
        FileLookupException.Code.CAPACITY_EXCEEDED,
        failure(service.planRoot(build("one"), BUDGETS)).code());
    queue.drain();
    assertEquals(0, gateway.preparations);
    var closing = service.planRoot(build("one"), BUDGETS).toCompletableFuture();
    service.close();
    queue.drain();
    assertTrue(closing.isCancelled());
    assertEquals(
        FileLookupException.Code.CLOSED, failure(service.planRoot(build("one"), BUDGETS)).code());
  }

  @Test
  void invalidSelectionAndIdBudgetsFailBeforePorts() {
    var gateway = new Gateway();
    try (var service =
        new DefaultFileLookupService(gateway, new Catalog(), STORE, Runnable::run, Runnable::run)) {
      assertEquals(
          FileLookupException.Code.INVALID_REQUEST,
          failure(service.lookupOffline(build("one"), Set.of(-1L), BUDGETS)).code());
      assertEquals(0, gateway.lookups);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new RootSelectionPlan(plan(build("one")), new RootCandidate("d".repeat(32), -1, -1)));
  }

  @Test
  void twoConsumersShareTransferAndOneCancellationDoesNotAbortTheOther() throws Exception {
    var gateway = new Gateway();
    var catalog = new Catalog();
    var queue = new Queue();
    var workers = Executors.newFixedThreadPool(2);
    try (var service = new DefaultFileLookupService(gateway, catalog, STORE, workers, queue)) {
      var plan =
          service.planRoot(build("one"), BUDGETS).toCompletableFuture().get(2, TimeUnit.SECONDS);
      var first = service.retainRoot(select(plan)).toCompletableFuture();
      var second = service.retainRoot(select(plan)).toCompletableFuture();
      // Both application workers must be awaiting a subscription before canceling one.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      boolean awaiting = false;
      while (System.nanoTime() < deadline) {
        long count =
            Thread.getAllStackTraces().values().stream()
                .filter(
                    stack ->
                        Arrays.stream(stack)
                            .anyMatch(
                                frame ->
                                    frame
                                            .getClassName()
                                            .equals(DefaultFileLookupService.class.getName())
                                        && frame.getMethodName().equals("await")))
                .count();
        if (count == 2) {
          awaiting = true;
          break;
        }
        Thread.onSpinWait();
      }
      assertTrue(awaiting, "Both consumers subscribed before cancellation");
      assertTrue(first.cancel(true));
      synchronized (queue) {
        queue.drain();
      }
      assertEquals(ROOT_RECEIPT, second.get(2, TimeUnit.SECONDS));
      assertEquals(1, gateway.acquisitions);
      assertTrue(first.isCancelled());
    } finally {
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void offlineProjectionForAnotherBuildIsRejectedBeforeReportingAbsence() {
    var gateway =
        new Gateway() {
          @Override
          public RootLookupResult lookupRetained(
              BuildSnapshot build,
              VerifiedSourceAlias config,
              VerifiedSourceAlias root,
              Set<Long> ids,
              LookupBudgets budgets,
              CancellationToken token) {
            return new RootLookupResult(build("other"), root, ids, List.of(), 100);
          }
        };
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      service.retainRoot(select(plan)).toCompletableFuture().join();
      assertEquals(
          FileLookupException.Code.INTEGRITY_FAILURE,
          failure(service.lookupOffline(build("one"), Set.of(7L), BUDGETS)).code());
    }
  }

  @Test
  void changedRoleAndOversizedSelectionFailBeforeRootTransfer() {
    var gateway = new Gateway();
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      catalog.attachVerifiedObject(
          plan.build().snapshotId(),
          "ROOT_MANIFEST",
          CONFIG,
          CONFIG_RECEIPT.object(),
          CONFIG_RECEIPT.evidence(),
          CancellationToken.none());
      assertEquals(
          FileLookupException.Code.INVALID_PLAN, failure(service.retainRoot(select(plan))).code());
      assertEquals(0, gateway.acquisitions);
    }
    var huge =
        new Gateway() {
          @Override
          public RootPlan prepareRoot(
              BuildSnapshot build,
              Optional<VerifiedSourceAlias> config,
              LookupBudgets budgets,
              CancellationToken token) {
            canonical =
                new RootPlan(
                    "huge",
                    build,
                    CONFIG_RECEIPT,
                    "c".repeat(32),
                    budgets.maximumRootDecodedBytes() + 1,
                    List.of(new RootCandidate("d".repeat(32), 8, 0)),
                    budgets);
            return canonical;
          }
        };
    try (var service =
        new DefaultFileLookupService(huge, new Catalog(), STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      assertEquals(
          FileLookupException.Code.BUDGET_EXCEEDED,
          failure(service.retainRoot(select(plan))).code());
      assertEquals(0, huge.acquisitions);
    }
  }

  @Test
  void typedUnsupportedAndCorruptionOutcomesSurviveAsyncBoundary() {
    var gateway =
        new Gateway() {
          @Override
          public RootLookupResult lookupRetained(
              BuildSnapshot build,
              VerifiedSourceAlias config,
              VerifiedSourceAlias root,
              Set<Long> ids,
              LookupBudgets budgets,
              CancellationToken token) {
            throw new FileLookupException(
                FileLookupException.Code.UNSUPPORTED, "Unsupported structural root variant");
          }
        };
    var catalog = new Catalog();
    try (var service =
        new DefaultFileLookupService(gateway, catalog, STORE, Runnable::run, Runnable::run)) {
      var plan = service.planRoot(build("one"), BUDGETS).toCompletableFuture().join();
      service.retainRoot(select(plan)).toCompletableFuture().join();
      assertEquals(
          FileLookupException.Code.UNSUPPORTED,
          failure(service.lookupOffline(build("one"), Set.of(7L), BUDGETS)).code());
    }
    var corrupt =
        new Catalog() {
          @Override
          public Optional<BuildObjectReference> findVerifiedObject(
              String id, String role, CancellationToken token) {
            throw new CatalogException(
                CatalogException.Code.INTEGRITY_FAILURE, "Corrupt local bytes");
          }
        };
    try (var service =
        new DefaultFileLookupService(gateway, corrupt, STORE, Runnable::run, Runnable::run)) {
      assertEquals(
          FileLookupException.Code.INTEGRITY_FAILURE,
          failure(service.lookupOffline(build("one"), Set.of(7L), BUDGETS)).code());
    }
  }
}
