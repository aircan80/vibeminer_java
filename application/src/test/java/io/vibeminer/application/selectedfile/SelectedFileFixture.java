package io.vibeminer.application.selectedfile;

import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.retention.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Deterministic policy ports, deliberately containing no protocol bytes or network implementation.
 */
final class SelectedFileFixture {
  static final String A = "a".repeat(32), B = "b".repeat(32), C = "c".repeat(32);
  static final SelectedFileBudgets BUDGETS = SelectedFileBudgets.defaults();
  static final ResolvedSourceIdentity CFG =
      new ResolvedSourceIdentity("test.config", "config", "raw.v1");
  static final ResolvedSourceIdentity ROOT =
      new ResolvedSourceIdentity("test.root", "root", "root.v1");

  static VerifiedSourceAlias alias(ResolvedSourceIdentity source, int digest, long size) {
    return new VerifiedSourceAlias(
        source,
        new StoredObject(new ObjectDigest(String.format("%064x", digest)), size),
        new SourceVerificationEvidence(
            source.verificationSemantics(), "https://us.cdn.blizzard.com/fixture", Instant.EPOCH));
  }

  static final VerifiedSourceAlias CONFIG = alias(CFG, 1, 20), ROOT_BYTES = alias(ROOT, 2, 100);

  static BuildSnapshot build(String version) {
    return new BuildSnapshot(
        "wow",
        "Synthetic",
        "Published",
        "us",
        version,
        String.format("%032x", Integer.toUnsignedLong(version.hashCode())),
        B,
        Instant.EPOCH,
        true);
  }

  static ResolvedSourceIdentity rootSource(BuildSnapshot build) {
    return new ResolvedSourceIdentity("test.root", "root-" + build.versionLabel(), "root.v1");
  }

  static FileVariantAssociation variant(BuildSnapshot build, String content) {
    return new FileVariantAssociation(
        build, rootSource(build), 42, content, 2, 3, 4, 5, 6, Optional.of("0000000000000000"));
  }

  static ContentRepresentation representation(LocatedFilePlan plan) {
    String key = plan.choice().candidate().encodingKey();
    String url = plan.locator().resourceUrl();
    var object = new StoredObject(new ObjectDigest(key + key), plan.encodedBytes());
    var source = ContentRepresentation.encodedIdentity(key);
    var alias =
        new VerifiedSourceAlias(
            source,
            object,
            new SourceVerificationEvidence(source.verificationSemantics(), url, Instant.EPOCH));
    var receipt =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.LOOSE,
            url,
            key,
            plan.encodedBytes(),
            0,
            plan.encodedBytes(),
            Instant.EPOCH,
            1,
            plan.encodedBytes(),
            "f".repeat(64),
            Optional.empty());
    return new ContentRepresentation(
        plan.choice().plan().variant().slot(),
        alias,
        EncodedScope.ZERO_HEADER_WHOLE_ENCODED,
        receipt);
  }

  static ContentRepresentation rebind(ContentRepresentation value, ContentSlotKey slot) {
    return new ContentRepresentation(slot, value.encoded(), value.scope(), value.sourceReceipt());
  }

  static SelectedFileException error(SelectedFileException.Code code) {
    return new SelectedFileException(code, code.name());
  }

  static final class Builds implements BuildObjectCatalog {
    final Map<String, BuildObjectReference> refs = new ConcurrentHashMap<>();
    final Set<String> registered = ConcurrentHashMap.newKeySet();

    void add(BuildSnapshot build) {
      registerSnapshot(build);
      var configSource =
          new ResolvedSourceIdentity("test.config", "config-" + build.versionLabel(), "raw.v1");
      int base = (build.versionLabel().hashCode() & 0x3fffffff) + 10;
      var config = alias(configSource, base, 20);
      var root = alias(rootSource(build), base + 1, 100);
      String host = build.versionLabel().equals("two") ? "kr" : "us";
      config =
          new VerifiedSourceAlias(
              config.source(),
              config.object(),
              new SourceVerificationEvidence(
                  config.source().verificationSemantics(),
                  "https://" + host + ".cdn.blizzard.com/config-origin",
                  Instant.EPOCH.plusSeconds(base)));
      root =
          new VerifiedSourceAlias(
              root.source(),
              root.object(),
              new SourceVerificationEvidence(
                  root.source().verificationSemantics(),
                  "https://" + host + ".cdn.blizzard.com/root-origin",
                  Instant.EPOCH.plusSeconds(base)));
      attachVerifiedObject(
          build.snapshotId(),
          "BUILD_CONFIG",
          config.source(),
          config.object(),
          config.evidence(),
          CancellationToken.none());
      attachVerifiedObject(
          build.snapshotId(),
          "ROOT_MANIFEST",
          root.source(),
          root.object(),
          root.evidence(),
          CancellationToken.none());
    }

    public void registerSnapshot(BuildSnapshot build) {
      registered.add(build.snapshotId());
    }

    public Optional<VerifiedSourceAlias> findVerifiedSourceAlias(
        ResolvedSourceIdentity source, CancellationToken token) {
      return Optional.empty();
    }

    public BuildObjectReference attachVerifiedObject(
        String snapshot,
        String role,
        ResolvedSourceIdentity source,
        StoredObject object,
        SourceVerificationEvidence proof,
        CancellationToken token) {
      var ref = new BuildObjectReference(snapshot, role, source, object, proof);
      refs.put(snapshot + role, ref);
      return ref;
    }

    public List<BuildObjectReference> listReferences(String snapshot) {
      return refs.values().stream().filter(r -> r.snapshotId().equals(snapshot)).toList();
    }

    public Optional<BuildObjectReference> findVerifiedObject(
        String snapshot, String role, CancellationToken token) {
      token.throwIfCancellationRequested();
      return Optional.ofNullable(refs.get(snapshot + role));
    }
  }

  static final class Pins implements RetentionRepository {
    final List<RetentionScope> scopes = new CopyOnWriteArrayList<>();

    public RetentionScope pin(RetentionScope scope, CancellationToken token) {
      token.throwIfCancellationRequested();
      scopes.add(scope);
      return scope;
    }

    public void release(String id, CancellationToken token) {
      throw new AssertionError("No automatic release");
    }

    public RetentionView readView(String id, RetentionLimits limits, CancellationToken token) {
      throw new AssertionError();
    }
  }

  static final class Files implements SelectedFileCatalog {
    final Map<ContentSlotKey, CanonicalReservation> slots = new ConcurrentHashMap<>();
    final Map<String, SelectedFileAssociation> associations = new ConcurrentHashMap<>();
    final Set<ObjectDigest> physical = ConcurrentHashMap.newKeySet();
    final Map<String, RetentionScope> attachedScopes = new ConcurrentHashMap<>();
    int released, uncertain;
    java.util.function.Consumer<SelectedFileAssociation> beforeAttach = association -> {};
    Runnable afterMarkPublishing = () -> {};

    public synchronized Optional<CanonicalReservation> lookup(
        ContentSlotKey slot, CancellationToken token) {
      return Optional.ofNullable(slots.get(slot));
    }

    public synchronized CanonicalReservation reserve(
        ContentSlotKey slot, String key, long length, CancellationToken token) {
      token.throwIfCancellationRequested();
      var old = slots.get(slot);
      if (old != null) {
        if (!old.actualEncodingKey().equals(key))
          throw error(
              old.status() == CanonicalReservation.Status.RETAINED ? REPLAN_REQUIRED : BUSY);
        if (old.encodedBytes() != length) throw error(INTEGRITY_FAILURE);
        if (old.status() != CanonicalReservation.Status.RETAINED) {
          var committed =
              slots.values().stream()
                  .filter(
                      value ->
                          value.status() == CanonicalReservation.Status.RETAINED
                              && value.actualEncodingKey().equals(key))
                  .findFirst();
          if (committed.isPresent()) {
            if (committed.get().encodedBytes() != length) throw error(INTEGRITY_FAILURE);
            var adopted = rebind(committed.get().representation().orElseThrow(), slot);
            status(old, CanonicalReservation.Status.RETAINED, Optional.of(adopted));
            return slots.get(slot);
          }
        }
        return old;
      }
      var shared =
          slots.values().stream()
              .filter(
                  s ->
                      s.status() == CanonicalReservation.Status.RETAINED
                          && s.actualEncodingKey().equals(key))
              .findFirst();
      if (shared.isPresent()) {
        if (shared.get().encodedBytes() != length) throw error(INTEGRITY_FAILURE);
        var value =
            new CanonicalReservation(
                slot,
                key,
                length,
                UUID.randomUUID(),
                CanonicalReservation.Status.RETAINED,
                Optional.of(rebind(shared.get().representation().orElseThrow(), slot)));
        slots.put(slot, value);
        return value;
      }
      var value =
          new CanonicalReservation(
              slot,
              key,
              length,
              UUID.randomUUID(),
              CanonicalReservation.Status.PENDING,
              Optional.empty());
      slots.put(slot, value);
      return value;
    }

    CanonicalReservation find(UUID token) {
      return slots.values().stream()
          .filter(s -> s.token().equals(token))
          .findFirst()
          .orElseThrow(() -> error(NOT_FOUND));
    }

    void status(
        CanonicalReservation old,
        CanonicalReservation.Status status,
        Optional<ContentRepresentation> value) {
      slots.put(
          old.slot(),
          new CanonicalReservation(
              old.slot(), old.actualEncodingKey(), old.encodedBytes(), old.token(), status, value));
    }

    public void markPublishing(UUID id, CancellationToken token) {
      synchronized (this) {
        token.throwIfCancellationRequested();
        var old = find(id);
        if (old.status() != CanonicalReservation.Status.RETAINED)
          status(old, CanonicalReservation.Status.PUBLISHING, old.representation());
      }
      afterMarkPublishing.run();
    }

    public synchronized void recordVerifiedCompletion(
        UUID id, ContentRepresentation value, CancellationToken token) {
      token.throwIfCancellationRequested();
      var old = find(id);
      if (!old.slot().equals(value.slot())
          || !old.actualEncodingKey().equals(value.encoded().source().immutableSourceKey())
          || old.encodedBytes() != value.encoded().object().length())
        throw error(INTEGRITY_FAILURE);
      status(old, old.status(), old.representation().or(() -> Optional.of(value)));
    }

    public synchronized ContentRepresentation completePublication(
        UUID id, ContentRepresentation value, CancellationToken token) {
      var old = find(id);
      if (old.representation().isEmpty() || !physical.contains(value.encoded().object().digest()))
        throw error(INTEGRITY_FAILURE);
      var first = old.representation().get();
      if (!first.encoded().object().equals(value.encoded().object())
          || !first.encoded().source().equals(value.encoded().source())
          || first.scope() != value.scope()) throw error(INTEGRITY_FAILURE);
      status(old, CanonicalReservation.Status.RETAINED, Optional.of(first));
      return first;
    }

    public synchronized void releaseUnpublished(UUID id, CancellationToken token) {
      var old = find(id);
      if (old.representation().isPresent()
          || old.status() == CanonicalReservation.Status.RECOVERY
          || old.status() == CanonicalReservation.Status.RETAINED) throw error(RECOVERY_REQUIRED);
      slots.remove(old.slot());
      released++;
    }

    public synchronized void markUncertain(UUID id, CancellationToken token) {
      var old = find(id);
      if (old.status() != CanonicalReservation.Status.RETAINED)
        status(old, CanonicalReservation.Status.RECOVERY, old.representation());
      uncertain++;
    }

    public SelectedFileAssociation attachAssociation(
        SelectedFileAssociation association, RetentionScope scope, CancellationToken token) {
      beforeAttach.accept(association);
      token.throwIfCancellationRequested();
      var ready = slots.get(association.representation().slot());
      if (ready == null
          || ready.status() != CanonicalReservation.Status.RETAINED
          || !ready.representation().orElseThrow().equals(association.representation()))
        throw error(INTEGRITY_FAILURE);
      attachedScopes.put(association.snapshotId() + association.role(), scope);
      var old =
          associations.putIfAbsent(association.snapshotId() + association.role(), association);
      if (old != null) {
        var normalized =
            new SelectedFileAssociation(
                old.descriptor(),
                association.configuration(),
                association.root(),
                association.proof(),
                association.representation());
        if (!old.equals(normalized)) throw error(INTEGRITY_FAILURE);
      }
      return old == null ? association : old;
    }

    public Optional<SelectedFileAssociation> findAssociation(
        String snapshot, String role, CancellationToken token) {
      return Optional.ofNullable(associations.get(snapshot + role));
    }
  }

  static class Gateway implements SelectedFileGateway {
    final Files files;
    final Map<String, CandidatePlan> plans = new ConcurrentHashMap<>();
    final Map<String, Loose404Permit> permits = new ConcurrentHashMap<>();
    final Map<String, LocatedFilePlan> located = new ConcurrentHashMap<>();
    List<EncodedCandidate> candidates =
        List.of(
            new EncodedCandidate(A, OptionalLong.of(12), OptionalLong.of(0)),
            new EncodedCandidate(B, OptionalLong.of(12), OptionalLong.empty()));
    int preparations, probes, searches, acquisitions, inspections;
    boolean looseMissing, missingPhysical, corruptPhysical;
    SelectedFileException probeFailure;
    EncodedAcquisitionFailure acquisitionFailure;
    boolean failAfterPublication;
    Runnable enteringAcquisition = () -> {};

    Gateway(Files files) {
      this.files = files;
    }

    public CandidatePlan prepareCandidates(
        BuildSnapshot build,
        VerifiedSourceAlias config,
        VerifiedSourceAlias root,
        FileVariantAssociation variant,
        SelectedFileBudgets budgets,
        CancellationToken token) {
      preparations++;
      var plan =
          new CandidatePlan(
              UUID.randomUUID().toString(),
              variant,
              config,
              root,
              new FileAssociationProof(
                  root.source(),
                  new ResolvedSourceIdentity("test.en", "en-" + build.versionLabel(), "en.v1"),
                  "root-en.v2",
                  String.format(
                      "%064x",
                      Integer.toUnsignedLong(
                          Objects.hash(build.snapshotId(), variant.contentKey()))),
                  OptionalLong.of(30),
                  candidates),
              candidates,
              budgets);
      plans.put(plan.sessionId(), plan);
      return plan;
    }

    public void validateCandidatePlan(CandidatePlan plan, CancellationToken token) {
      token.throwIfCancellationRequested();
      if (!plan.equals(plans.get(plan.sessionId()))) throw error(INVALID_PLAN);
    }

    void choice(CandidateChoice choice, CancellationToken token) {
      validateCandidatePlan(choice.plan(), token);
      if (!choice.plan().candidates().contains(choice.candidate())) throw error(INVALID_PLAN);
    }

    LocatedFilePlan locate(CandidateChoice choice) {
      long length = choice.candidate().encodedBytes().orElse(12);
      String url =
          "https://"
              + (choice.plan().variant().build().versionLabel().equals("two") ? "kr" : "us")
              + ".cdn.blizzard.com/tpr/wow/data/"
              + choice.candidate().encodingKey();
      var value =
          new LocatedFilePlan(
              UUID.randomUUID().toString(),
              choice,
              new FileLocator(
                  FileLocator.Kind.LOOSE,
                  url,
                  choice.candidate().encodingKey(),
                  length,
                  0,
                  length,
                  Optional.empty()),
              length,
              new AcquisitionCost(length, length));
      located.put(value.sessionId(), value);
      return value;
    }

    public LooseProbe probeLoose(CandidateChoice choice, CancellationToken token) {
      choice(choice, token);
      probes++;
      if (probeFailure != null) throw probeFailure;
      if (looseMissing) {
        var permit = new Loose404Permit(UUID.randomUUID().toString(), choice);
        permits.put(permit.sessionId(), permit);
        return new LooseProbe(Optional.empty(), Optional.of(permit));
      }
      return new LooseProbe(Optional.of(locate(choice)), Optional.empty());
    }

    public void validateLoose404Permit(Loose404Permit permit, CancellationToken token) {
      choice(permit.choice(), token);
      if (!permit.equals(permits.get(permit.sessionId()))) throw error(INVALID_PLAN);
    }

    public ArchiveSearchResult searchArchives(
        Loose404Permit permit, ArchiveSearchBudgets budget, CancellationToken token) {
      validateLoose404Permit(permit, token);
      searches++;
      return new ArchiveSearchResult(Optional.empty(), 1, 5, false);
    }

    public void validateLocatedPlan(LocatedFilePlan plan, CancellationToken token) {
      choice(plan.choice(), token);
      if (!plan.equals(located.get(plan.sessionId()))) throw error(INVALID_PLAN);
    }

    public ContentRepresentation acquireEncoded(
        LocatedFilePlan plan, EncodedPublicationObserver observer, CancellationToken token) {
      acquisitions++;
      enteringAcquisition.run();
      if (acquisitionFailure != null) throw acquisitionFailure;
      var value = representation(plan);
      observer.verifiedBeforePromotion(value);
      files.physical.add(value.encoded().object().digest());
      if (failAfterPublication) throw new IllegalStateException("Crash after physical promotion");
      return value;
    }

    public ContentRepresentation inspectRetained(
        SelectedFileAssociation association, SelectedFileBudgets budgets, CancellationToken token) {
      inspections++;
      token.throwIfCancellationRequested();
      if (missingPhysical
          || !files.physical.contains(association.representation().encoded().object().digest()))
        throw error(MISSING_LOCAL);
      if (corruptPhysical) throw error(INTEGRITY_FAILURE);
      return association.representation();
    }
  }

  final Builds builds = new Builds();
  final Files files = new Files();
  final Pins pins = new Pins();
  final Gateway gateway = new Gateway(files);

  DefaultSelectedFileService service(Executor jobs, Executor acquisition) {
    return new DefaultSelectedFileService(gateway, builds, files, pins, jobs, acquisition);
  }

  FileVariantAssociation add(String version, String ckey) {
    var build = build(version);
    builds.add(build);
    return variant(build, ckey);
  }

  static <T> T await(CompletionStage<T> future) throws Exception {
    return future.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }
}
