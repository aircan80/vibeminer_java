package io.vibeminer.application.retention;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DefaultRetentionServiceTest {
  static final String A = "a".repeat(64), B = "b".repeat(64);

  static ResolvedSourceIdentity source(String key) {
    return new ResolvedSourceIdentity("fixture", key, "verified.v1");
  }

  static ScopeRequirement requirement(String role, String key, long encoded, long logical) {
    return new ScopeRequirement(
        role,
        Optional.of(source(key)),
        encoded < 0 ? OptionalLong.empty() : OptionalLong.of(encoded),
        logical < 0 ? OptionalLong.empty() : OptionalLong.of(logical));
  }

  static RetentionScope scope(String snapshot, ScopeRequirement... roles) {
    return new RetentionScope(snapshot, ScopeKind.LEGACY_RETAINED, List.of(roles));
  }

  static class Repository implements RetentionRepository {
    final Map<String, RetentionScope> scopes = new HashMap<>();
    final Set<String> pinned = new HashSet<>();

    public RetentionScope pin(RetentionScope scope, CancellationToken token) {
      scopes.putIfAbsent(scope.scopeId(), scope);
      pinned.add(scope.scopeId());
      return scope;
    }

    public void release(String id, CancellationToken token) {
      pinned.remove(id);
    }

    public RetentionView readView(String id, RetentionLimits limits, CancellationToken token) {
      var scope = scopes.get(id);
      if (scope == null)
        throw new RetentionException(RetentionException.Code.NOT_FOUND, "No scope");
      var others = pinned.stream().filter(key -> !key.equals(id)).map(scopes::get).toList();
      return new RetentionView(scope, pinned.contains(id), others);
    }
  }

  static class Catalog implements BuildObjectCatalog {
    final Map<String, BuildObjectReference> refs = new HashMap<>();
    final Map<ResolvedSourceIdentity, VerifiedSourceAlias> aliases = new HashMap<>();

    public void registerSnapshot(BuildSnapshot build) {
      throw new AssertionError();
    }

    public BuildObjectReference attachVerifiedObject(
        String snapshot,
        String role,
        ResolvedSourceIdentity source,
        StoredObject object,
        SourceVerificationEvidence evidence,
        CancellationToken token) {
      throw new AssertionError();
    }

    public List<BuildObjectReference> listReferences(String snapshot) {
      return refs.values().stream().filter(ref -> ref.snapshotId().equals(snapshot)).toList();
    }

    public Optional<BuildObjectReference> findVerifiedObject(
        String id, String role, CancellationToken token) {
      throw new AssertionError();
    }

    public Optional<VerifiedSourceAlias> findVerifiedSourceAlias(
        ResolvedSourceIdentity source, CancellationToken token) {
      return Optional.ofNullable(aliases.get(source));
    }

    StoredObject add(String snapshot, String role, String key, int physical, long length) {
      var identity = source(key);
      var object = new StoredObject(new ObjectDigest(String.format("%064x", physical)), length);
      var evidence =
          new SourceVerificationEvidence(
              identity.verificationSemantics(), "https://example.invalid/fixture", Instant.EPOCH);
      refs.put(
          snapshot + role, new BuildObjectReference(snapshot, role, identity, object, evidence));
      aliases.put(identity, new VerifiedSourceAlias(identity, object, evidence));
      return object;
    }
  }

  static class Store implements ObjectStore {
    final Map<ObjectDigest, StoredObject> objects = new HashMap<>();
    final Set<ObjectDigest> corrupt = new HashSet<>();
    int leases, closedLeases;

    public StoredObject publish(
        InputStream s, ExpectedObjectChecksum e, long max, CancellationToken t) {
      throw new AssertionError();
    }

    public StoredObject publishVerified(
        InputStream s, long len, long max, CompletionVerification v, CancellationToken t) {
      throw new AssertionError();
    }

    public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) {
      throw new AssertionError("Accounting uses leases");
    }

    public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
      if (corrupt.contains(digest))
        throw new ObjectStoreException(ObjectStoreException.Code.INTEGRITY_FAILURE, "Corrupt");
      var object = objects.get(digest);
      if (object == null)
        throw new ObjectStoreException(ObjectStoreException.Code.MISSING_OBJECT, "Missing");
      leases++;
      return new ObjectReadLease() {
        public StoredObject object() {
          return object;
        }

        public InputStream stream() {
          throw new AssertionError();
        }

        public void close() {
          closedLeases++;
        }
      };
    }

    public void close() {
      throw new AssertionError("Caller-owned");
    }
  }

  static RetentionException failure(CompletionStage<?> stage) {
    return (RetentionException)
        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join())
            .getCause();
  }

  @Test
  void shaSetAccountingReleaseAndUnknownLogicalSizes() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    for (var object :
        List.of(
            catalog.add(A, "x", "x", 1, 10),
            catalog.add(A, "y", "y", 2, 20),
            catalog.add(B, "y", "y", 2, 20),
            catalog.add(B, "z", "z", 3, 30))) store.objects.put(object.digest(), object);
    var a = scope(A, requirement("x", "x", 10, 100), requirement("y", "y", 20, -1));
    var b = scope(B, requirement("y", "y", 20, 200), requirement("z", "z", 30, 300));
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      service.pin(a).toCompletableFuture().join();
      service.pin(b).toCompletableFuture().join();
      var measurement =
          service.measure(a.scopeId(), RetentionLimits.defaults()).toCompletableFuture().join();
      assertEquals(new ByteEstimate(100, 1), measurement.logicalDecodedBytes());
      assertEquals(new ByteEstimate(0, 0), measurement.additionalEncodedBytes());
      assertEquals(30, measurement.presentUniqueBytes());
      assertEquals(20, measurement.sharedPresentBytes());
      assertEquals(10, measurement.losesProtectionBytes());
      service.release(a.scopeId()).toCompletableFuture().join();
      assertEquals(
          0,
          service
              .measure(a.scopeId(), RetentionLimits.defaults())
              .toCompletableFuture()
              .join()
              .losesProtectionBytes());
      assertEquals(3, store.objects.size());
      assertTrue(repo.pinned.contains(b.scopeId()));
      assertEquals(store.leases, store.closedLeases);
    }
  }

  @Test
  void duplicateAliasesAndRolesCountPhysicalOnceButLogicalPerRole() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    var object = catalog.add(A, "first", "one", 1, 8);
    catalog.add(A, "second", "two", 1, 8);
    store.objects.put(object.digest(), object);
    var scope = scope(A, requirement("first", "one", 8, 10), requirement("second", "two", 8, 20));
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      service.pin(scope).toCompletableFuture().join();
      var m =
          service.measure(scope.scopeId(), RetentionLimits.defaults()).toCompletableFuture().join();
      assertEquals(8, m.presentUniqueBytes());
      assertEquals(new ByteEstimate(30, 0), m.logicalDecodedBytes());
      assertEquals(1, store.leases);
    }
  }

  @Test
  void missingUnknownAndUnattachedLocalAliasRemainDistinct() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    var object = catalog.add(B, "elsewhere", "local", 1, 8);
    store.objects.put(object.digest(), object);
    var scope =
        scope(
            A,
            requirement("local", "local", 8, 10),
            requirement("missing", "missing", 12, 20),
            new ScopeRequirement(
                "unknown", Optional.empty(), OptionalLong.empty(), OptionalLong.empty()));
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      service.pin(scope).toCompletableFuture().join();
      var m =
          service.measure(scope.scopeId(), RetentionLimits.defaults()).toCompletableFuture().join();
      assertEquals(new ByteEstimate(12, 1), m.additionalEncodedBytes());
      assertEquals(1, m.missingRequirements());
      assertEquals(2, m.unresolvedRequirements());
      assertEquals(8, m.presentUniqueBytes());
      assertEquals(0, m.corruptRequirements());
    }
  }

  @Test
  void exactMembershipBindsUnknownSourceAndSizeButNeverAnotherBuildRole() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    var local = catalog.add(A, "bound", "bound", 1, 8);
    store.objects.put(local.digest(), local);
    catalog.add(B, "unknown", "other", 2, 99);
    var scope =
        scope(
            A,
            new ScopeRequirement(
                "bound", Optional.empty(), OptionalLong.empty(), OptionalLong.empty()),
            new ScopeRequirement(
                "unknown", Optional.empty(), OptionalLong.empty(), OptionalLong.empty()));
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      service.pin(scope).toCompletableFuture().join();
      var m =
          service.measure(scope.scopeId(), RetentionLimits.defaults()).toCompletableFuture().join();
      assertEquals(8, m.presentUniqueBytes());
      assertEquals(new ByteEstimate(0, 1), m.additionalEncodedBytes());
      assertEquals(1, m.unresolvedRequirements());
    }
  }

  @Test
  void corruptionIsNotMissingAndLengthConflictFailsIntegrity() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    var object = catalog.add(A, "x", "x", 1, 8);
    store.objects.put(object.digest(), object);
    var scope = scope(A, requirement("x", "x", 8, 10));
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      service.pin(scope).toCompletableFuture().join();
      store.corrupt.add(object.digest());
      var m =
          service.measure(scope.scopeId(), RetentionLimits.defaults()).toCompletableFuture().join();
      assertEquals(1, m.corruptRequirements());
      assertEquals(0, m.missingRequirements());
      assertEquals(0, m.presentUniqueBytes());
      assertEquals(
          RetentionException.Code.INTEGRITY_FAILURE,
          failure(service.pin(scope(A, requirement("x", "x", 9, 10)))).code());
    }
  }

  @Test
  void conflictsOverflowAndCapacityNeverReturnPartialTotals() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      var scope = scope(A, requirement("x", "x", 10, Long.MAX_VALUE), requirement("y", "y", 20, 1));
      service.pin(scope).toCompletableFuture().join();
      assertEquals(
          RetentionException.Code.INTEGRITY_FAILURE,
          failure(service.measure(scope.scopeId(), RetentionLimits.defaults())).code());
      assertEquals(
          RetentionException.Code.CAPACITY_EXCEEDED,
          failure(service.measure(scope.scopeId(), new RetentionLimits(1, 1))).code());
    }
  }

  @Test
  void scopeIdentityIsOrderIndependentButIncludesUnknownsAndSizes() {
    var x = requirement("x", "x", 0, -1);
    var y = requirement("y", "y", 2, 3);
    assertEquals(scope(A, x, y).scopeId(), scope(A, y, x).scopeId());
    assertNotEquals(scope(A, x).scopeId(), scope(A, requirement("x", "x", 0, 0)).scopeId());
  }

  @Test
  void unknownFactsCannotHideConflictingLaterKnownLogicalFacts() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    var unknown =
        scope(
            A,
            new ScopeRequirement(
                "x", Optional.empty(), OptionalLong.empty(), OptionalLong.empty()));
    var first = scope(A, requirement("x", "x", 1, 10));
    var second = scope(A, requirement("x", "x", 1, 20));
    repo.pin(unknown, CancellationToken.none());
    repo.pin(first, CancellationToken.none());
    repo.pin(second, CancellationToken.none());
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      assertEquals(
          RetentionException.Code.INTEGRITY_FAILURE,
          failure(service.measure(unknown.scopeId(), RetentionLimits.defaults())).code());
    }
  }

  @Test
  void logicalFactsForOneExactSourceMustAgreeAcrossBuilds() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    var one = scope(A, requirement("x", "same", 1, 10));
    var two = scope(B, requirement("y", "same", 1, 20));
    repo.pin(one, CancellationToken.none());
    repo.pin(two, CancellationToken.none());
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      assertEquals(
          RetentionException.Code.INTEGRITY_FAILURE,
          failure(service.measure(one.scopeId(), RetentionLimits.defaults())).code());
    }
  }

  @Test
  void queuedJobsCancelWithoutPortsAndCapacityWaitsForRetirement() {
    var pending = new ArrayDeque<Runnable>();
    Executor queue = pending::add;
    var repository = new Repository();
    var scope = scope(A, requirement("x", "x", 1, 2));
    try (var service = new DefaultRetentionService(repository, new Catalog(), new Store(), queue)) {
      for (int i = 0; i < 4; i++) assertTrue(service.pin(scope).toCompletableFuture().cancel(true));
      assertEquals(RetentionException.Code.CAPACITY_EXCEEDED, failure(service.pin(scope)).code());
      while (!pending.isEmpty()) pending.remove().run();
      assertTrue(repository.scopes.isEmpty());
      var last = service.pin(scope).toCompletableFuture();
      service.close();
      pending.remove().run();
      assertTrue(last.isCancelled());
      assertEquals(RetentionException.Code.CLOSED, failure(service.pin(scope)).code());
    }
  }

  @Test
  void resolvedUnknownSourcesCannotHideConflictingDecodedFactsAcrossBuilds() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    var object = catalog.add(A, "x", "same", 1, 8);
    catalog.add(B, "y", "same", 1, 8);
    store.objects.put(object.digest(), object);
    var one =
        scope(
            A,
            new ScopeRequirement("x", Optional.empty(), OptionalLong.of(8), OptionalLong.of(10)));
    var two =
        scope(
            B,
            new ScopeRequirement("y", Optional.empty(), OptionalLong.of(8), OptionalLong.of(20)));
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      service.pin(one).toCompletableFuture().join();
      service.pin(two).toCompletableFuture().join();
      assertEquals(
          RetentionException.Code.INTEGRITY_FAILURE,
          failure(service.measure(one.scopeId(), RetentionLimits.defaults())).code());
    }
  }

  @Test
  void alreadyProvenAliasesOfOneMissingPhysicalObjectNeedOneRestore() {
    var repo = new Repository();
    var catalog = new Catalog();
    var store = new Store();
    catalog.add(A, "x", "first", 1, 8);
    catalog.add(A, "y", "second", 1, 8);
    var scope = scope(A, requirement("x", "first", 8, 10), requirement("y", "second", 8, 10));
    try (var service = new DefaultRetentionService(repo, catalog, store, Runnable::run)) {
      service.pin(scope).toCompletableFuture().join();
      var measured =
          service.measure(scope.scopeId(), RetentionLimits.defaults()).toCompletableFuture().join();
      assertEquals(new ByteEstimate(8, 0), measured.additionalEncodedBytes());
      assertEquals(2, measured.missingRequirements());
    }
  }
}
