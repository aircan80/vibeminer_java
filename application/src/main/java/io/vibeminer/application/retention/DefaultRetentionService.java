package io.vibeminer.application.retention;

import static io.vibeminer.application.retention.RetentionException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** Explicit pins and observational accounting; never network acquisition or physical deletion. */
public final class DefaultRetentionService implements RetentionService {
  private final RetentionRepository repository;
  private final BuildObjectCatalog catalog;
  private final ObjectStore store;
  private final Executor executor;
  private final Semaphore capacity = new Semaphore(4);
  private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Caller owns executor, catalog and store. Stop workers before closing storage. */
  public DefaultRetentionService(
      RetentionRepository repository,
      BuildObjectCatalog catalog,
      ObjectStore store,
      Executor executor) {
    this.repository = Objects.requireNonNull(repository);
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public CompletionStage<RetentionScope> pin(RetentionScope scope) {
    Objects.requireNonNull(scope);
    return submit(
        token -> {
          var references = references(scope.snapshotId());
          for (var requirement : scope.requirements()) {
            token.throwIfCancellationRequested();
            var reference = references.get(requirement.role());
            if (reference != null) validateRequirement(requirement, reference);
          }
          return repository.pin(scope, token);
        });
  }

  @Override
  public CompletionStage<Void> release(String scopeId) {
    validateId(scopeId);
    return submit(
        token -> {
          repository.release(scopeId, token);
          return null;
        });
  }

  @Override
  public CompletionStage<RetentionMeasurement> measure(String scopeId, RetentionLimits limits) {
    validateId(scopeId);
    Objects.requireNonNull(limits);
    return submit(
        token -> {
          var started = Instant.now();
          var view = repository.readView(scopeId, limits, token);
          if (!view.selected().scopeId().equals(scopeId))
            throw integrity("Repository returned a different scope");
          var accounting = new Accounting(view, limits, token);
          return accounting.measure(started);
        });
  }

  private Map<String, BuildObjectReference> references(String snapshot) {
    var result = new HashMap<String, BuildObjectReference>();
    for (var reference : catalog.listReferences(snapshot)) {
      if (!reference.snapshotId().equals(snapshot)
          || result.put(reference.role(), reference) != null)
        throw integrity("Catalog returned inconsistent exact-build membership");
    }
    return result;
  }

  private static void validateRequirement(
      ScopeRequirement requirement, BuildObjectReference reference) {
    if (requirement.source().isPresent() && !requirement.source().get().equals(reference.source()))
      throw integrity("Requirement conflicts with immutable exact-build role source");
    if (requirement.encodedBytes().isPresent()
        && requirement.encodedBytes().getAsLong() != reference.object().length())
      throw integrity("Requirement encoded length conflicts with verified source metadata");
  }

  private enum Presence {
    PRESENT,
    MISSING,
    CORRUPT
  }

  private record Observation(StoredObject object, Presence presence) {}

  private record RoleKey(String snapshot, String role) {}

  private record Resolved(
      ResolvedSourceIdentity source, StoredObject object, Presence presence, boolean attached) {}

  private final class Accounting {
    private final RetentionView view;
    private final RetentionLimits limits;
    private final CancellationToken token;
    private final Map<String, Map<String, BuildObjectReference>> references = new HashMap<>();
    private final Map<ObjectDigest, Observation> physical = new HashMap<>();
    private final Map<ResolvedSourceIdentity, Optional<VerifiedSourceAlias>> aliases =
        new HashMap<>();
    private final Set<ResolvedSourceIdentity> corruptAliases = new HashSet<>();
    private final Map<ResolvedSourceIdentity, Long> encodedFacts = new HashMap<>(),
        decodedFacts = new HashMap<>();
    private final Map<RoleKey, ScopeRequirement> roleFacts = new HashMap<>();
    private final Map<ObjectDigest, Long> selected = new HashMap<>(), others = new HashMap<>();
    private final Map<ResolvedSourceIdentity, OptionalLong> missingSources = new HashMap<>();
    private final Map<ResolvedSourceIdentity, StoredObject> sourceObjects = new HashMap<>();
    private long logical;
    private int logicalUnknown, unknownSourceTransfers, missing, unresolved, corrupt;

    Accounting(RetentionView view, RetentionLimits limits, CancellationToken token) {
      this.view = view;
      this.limits = limits;
      this.token = token;
    }

    RetentionMeasurement measure(Instant started) {
      var scopes = new ArrayList<RetentionScope>();
      scopes.add(view.selected());
      scopes.addAll(view.otherPinned());
      if (scopes.size() > limits.maximumScopes())
        throw capacity("Too many pinned scopes to measure");
      int count = 0;
      var ids = new HashSet<String>();
      for (var scope : scopes) {
        token.throwIfCancellationRequested();
        if (!ids.add(scope.scopeId())) throw integrity("Duplicate scope in accounting snapshot");
        count = Math.addExact(count, scope.requirements().size());
        if (count > limits.maximumRequirements())
          throw capacity("Too many requirements to measure");
        for (var requirement : scope.requirements()) recordFacts(scope, requirement);
      }
      for (var scope : scopes) {
        boolean isSelected = scope.scopeId().equals(view.selected().scopeId());
        for (var requirement : scope.requirements()) {
          token.throwIfCancellationRequested();
          var resolved = resolve(scope, requirement);
          if (resolved.source() != null && requirement.logicalDecodedBytes().isPresent())
            recordDecoded(resolved.source(), requirement.logicalDecodedBytes().getAsLong());
          if (resolved.presence() == Presence.PRESENT)
            (isSelected ? selected : others)
                .put(resolved.object().digest(), resolved.object().length());
          if (!isSelected) continue;
          if (requirement.logicalDecodedBytes().isPresent())
            logical = Math.addExact(logical, requirement.logicalDecodedBytes().getAsLong());
          else logicalUnknown++;
          if (resolved.presence() == Presence.CORRUPT) corrupt++;
          else if (resolved.source() == null
              || resolved.presence() == Presence.PRESENT && !resolved.attached()) unresolved++;
          else if (resolved.presence() == Presence.MISSING) missing++;
          if (resolved.presence() != Presence.PRESENT) {
            if (resolved.source() == null) unknownSourceTransfers++;
            else {
              var size = requirement.encodedBytes();
              if (size.isEmpty() && resolved.object() != null)
                size = OptionalLong.of(resolved.object().length());
              missingSources.merge(resolved.source(), size, DefaultRetentionService::mergeSize);
            }
          }
        }
      }
      long present = sum(selected.values()), shared = 0, loses = 0, additional = 0;
      int unknown = unknownSourceTransfers;
      for (var entry : selected.entrySet())
        if (others.containsKey(entry.getKey())) shared = Math.addExact(shared, entry.getValue());
      if (view.pinned()) loses = Math.subtractExact(present, shared);
      var missingPhysical = new HashMap<ObjectDigest, Long>();
      for (var entry : missingSources.entrySet()) {
        var object = sourceObjects.get(entry.getKey());
        if (object != null) missingPhysical.put(object.digest(), object.length());
        else if (entry.getValue().isPresent())
          additional = Math.addExact(additional, entry.getValue().getAsLong());
        else unknown++;
      }
      additional = Math.addExact(additional, sum(missingPhysical.values()));
      token.throwIfCancellationRequested();
      return new RetentionMeasurement(
          view.selected().scopeId(),
          started,
          Instant.now(),
          new ByteEstimate(logical, logicalUnknown),
          new ByteEstimate(additional, unknown),
          present,
          shared,
          loses,
          missing,
          unresolved,
          corrupt);
    }

    private void recordFacts(RetentionScope scope, ScopeRequirement requirement) {
      var key = new RoleKey(scope.snapshotId(), requirement.role());
      var old = roleFacts.get(key);
      var merged = requirement;
      if (old != null) {
        if (old.source().isPresent()
            && requirement.source().isPresent()
            && !old.source().equals(requirement.source()))
          throw integrity("Conflicting exact-role source facts");
        merged =
            new ScopeRequirement(
                requirement.role(),
                old.source().isPresent() ? old.source() : requirement.source(),
                mergeSize(old.encodedBytes(), requirement.encodedBytes()),
                mergeSize(old.logicalDecodedBytes(), requirement.logicalDecodedBytes()));
      }
      roleFacts.put(key, merged);
      if (merged.source().isPresent()) {
        var source = merged.source().get();
        if (merged.encodedBytes().isPresent())
          recordEncoded(source, merged.encodedBytes().getAsLong());
        if (merged.logicalDecodedBytes().isPresent()) {
          recordDecoded(source, merged.logicalDecodedBytes().getAsLong());
        }
      }
    }

    private void recordEncoded(ResolvedSourceIdentity source, long length) {
      var old = encodedFacts.putIfAbsent(source, length);
      if (old != null && old != length) throw integrity("Conflicting encoded source lengths");
    }

    private void recordDecoded(ResolvedSourceIdentity source, long length) {
      var old = decodedFacts.putIfAbsent(source, length);
      if (old != null && old != length) throw integrity("Conflicting decoded source lengths");
    }

    private void recordObject(ResolvedSourceIdentity source, StoredObject object) {
      var old = sourceObjects.putIfAbsent(source, object);
      if (old != null && !old.equals(object))
        throw integrity("One source has conflicting physical mappings");
      recordEncoded(source, object.length());
    }

    private Resolved resolve(RetentionScope scope, ScopeRequirement requirement) {
      var refs =
          references.computeIfAbsent(scope.snapshotId(), DefaultRetentionService.this::references);
      if (refs.size() > limits.maximumRequirements())
        throw capacity("Too many exact-build memberships");
      var reference = refs.get(requirement.role());
      if (reference != null) {
        validateRequirement(requirement, reference);
        recordObject(reference.source(), reference.object());
        var observation = observe(reference.object());
        return new Resolved(reference.source(), reference.object(), observation.presence(), true);
      }
      if (requirement.source().isEmpty()) return new Resolved(null, null, Presence.MISSING, false);
      var source = requirement.source().get();
      if (!aliases.containsKey(source) && !corruptAliases.contains(source)) {
        try {
          var alias = catalog.findVerifiedSourceAlias(source, token);
          if (alias.isPresent() && !alias.get().source().equals(source))
            throw integrity("Catalog returned a different source alias");
          aliases.put(source, alias);
        } catch (CatalogException failure) {
          if (failure.code() == CatalogException.Code.INTEGRITY_FAILURE) corruptAliases.add(source);
          else throw failure;
        }
      }
      if (corruptAliases.contains(source))
        return new Resolved(source, null, Presence.CORRUPT, false);
      var alias = aliases.get(source);
      if (alias.isEmpty()) return new Resolved(source, null, Presence.MISSING, false);
      var object = alias.get().object();
      if (requirement.encodedBytes().isPresent()
          && requirement.encodedBytes().getAsLong() != object.length())
        throw integrity("Alias length conflicts with requirement");
      recordObject(source, object);
      var observation = observe(object);
      return new Resolved(source, object, observation.presence(), false);
    }

    private Observation observe(StoredObject expected) {
      var cached = physical.get(expected.digest());
      if (cached != null) {
        if (!cached.object().equals(expected))
          throw integrity("One physical digest has conflicting lengths");
        return cached;
      }
      Observation observation;
      try (var lease = store.openRead(expected.digest(), token)) {
        if (!lease.object().equals(expected))
          throw integrity("Leased physical content conflicts with catalog length");
        observation = new Observation(expected, Presence.PRESENT);
      } catch (ObjectStoreException failure) {
        if (failure.code() == ObjectStoreException.Code.MISSING_OBJECT)
          observation = new Observation(expected, Presence.MISSING);
        else if (failure.code() == ObjectStoreException.Code.INTEGRITY_FAILURE)
          observation = new Observation(expected, Presence.CORRUPT);
        else throw failure;
      }
      physical.put(expected.digest(), observation);
      return observation;
    }
  }

  private static OptionalLong mergeSize(OptionalLong first, OptionalLong second) {
    if (first.isPresent() && second.isPresent() && first.getAsLong() != second.getAsLong())
      throw integrity("Conflicting known size facts");
    return first.isPresent() ? first : second;
  }

  private static long sum(Collection<Long> values) {
    long result = 0;
    for (long value : values) result = Math.addExact(result, value);
    return result;
  }

  private static void validateId(String id) {
    if (id == null || !id.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid scope identity");
  }

  private <T> CompletionStage<T> submit(Function<CancellationToken, T> work) {
    if (closed.get())
      return CompletableFuture.failedFuture(
          new RetentionException(CLOSED, "Retention service is closed"));
    if (!capacity.tryAcquire())
      return CompletableFuture.failedFuture(capacity("Too many retention jobs"));
    var result = new CompletableFuture<T>();
    var worker = new AtomicReference<Thread>();
    active.add(result);
    result.whenComplete(
        (value, error) -> {
          if (result.isCancelled()) {
            var thread = worker.get();
            if (thread != null) thread.interrupt();
          }
        });
    try {
      executor.execute(
          () -> {
            worker.set(Thread.currentThread());
            try {
              CancellationToken token =
                  () ->
                      result.isCancelled()
                          || closed.get()
                          || Thread.currentThread().isInterrupted();
              token.throwIfCancellationRequested();
              result.complete(work.apply(token));
            } catch (Throwable failure) {
              result.completeExceptionally(classify(failure));
              if (failure instanceof Error fatal) throw fatal;
            } finally {
              worker.set(null);
              active.remove(result);
              capacity.release();
            }
          });
    } catch (RuntimeException rejected) {
      active.remove(result);
      capacity.release();
      result.completeExceptionally(
          new RetentionException(CAPACITY_EXCEEDED, "Retention executor rejected work", rejected));
    }
    if (closed.get()) result.cancel(true);
    return result;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) active.forEach(future -> future.cancel(true));
  }

  private static RuntimeException classify(Throwable failure) {
    if (failure instanceof RetentionException typed) return typed;
    if (failure instanceof CancellationException cancelled) return cancelled;
    if (failure instanceof ArithmeticException)
      return new RetentionException(INTEGRITY_FAILURE, "Byte accounting overflow", failure);
    if (failure instanceof CatalogException catalog)
      return new RetentionException(
          switch (catalog.code()) {
            case INTEGRITY_FAILURE, CORRUPT_METADATA, IMMUTABLE_CONFLICT, INVALID_EVIDENCE ->
                INTEGRITY_FAILURE;
            case UNKNOWN_SNAPSHOT -> NOT_FOUND;
            default -> STORAGE_UNAVAILABLE;
          },
          catalog.getMessage(),
          catalog);
    if (failure instanceof ObjectStoreException store)
      return new RetentionException(
          store.code() == ObjectStoreException.Code.INTEGRITY_FAILURE
              ? INTEGRITY_FAILURE
              : STORAGE_UNAVAILABLE,
          store.getMessage(),
          store);
    return new RetentionException(STORAGE_UNAVAILABLE, "Retention operation failed", failure);
  }

  private static RetentionException integrity(String message) {
    return new RetentionException(INTEGRITY_FAILURE, message);
  }

  private static RetentionException capacity(String message) {
    return new RetentionException(CAPACITY_EXCEEDED, message);
  }
}
