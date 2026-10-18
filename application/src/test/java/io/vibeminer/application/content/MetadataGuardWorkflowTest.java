package io.vibeminer.application.content;

import static io.vibeminer.application.content.ContentContractsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.catalog.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MetadataGuardWorkflowTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bothMetadataWorkflowsGuardCachedAliasesAndReplayOriginalProof(boolean root) {
    var f = new Fixture(root);
    try {
      f.plan();
      assertEquals(0, f.authority.reservations);
      var first = f.retain();
      assertEquals(f.gateway.artifact, first);
      assertEquals(1, f.gateway.transfers);
      assertEquals(1, f.authority.completions);
      var second = f.retain();
      assertEquals(first, second);
      assertEquals(1, f.gateway.transfers);
      assertEquals(1, f.gateway.replays);
      assertEquals(2, f.authority.reservations, "process cache cannot bypass canonical authority");
      f.authority.block = ContentClaimException.Code.LEGACY_CONFLICT;
      var failure = assertThrows(CompletionException.class, f::retain);
      assertEquals(
          ContentClaimException.Code.LEGACY_CONFLICT,
          ((ContentClaimException) failure.getCause()).code());
      assertEquals(1, f.gateway.transfers);
    } finally {
      f.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void prospectiveFailureNeverAttachesAndRemainsRecovery(boolean root) {
    var f = new Fixture(root);
    try {
      f.plan();
      f.gateway.failAfterGate = true;
      assertThrows(CompletionException.class, f::retain);
      assertEquals(1, f.authority.uncertain);
      assertEquals(0, f.authority.releases);
      assertFalse(f.catalog.references.keySet().stream().anyMatch(key -> key.endsWith(f.role())));
    } finally {
      f.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshServiceAndExactBuildReuseOriginalPhysicalMetadataWithoutTransfer(boolean root) {
    var first = new Fixture(root);
    first.plan();
    var original = first.retain();
    first.close();
    var old = build("wow");
    var next =
        new BuildSnapshot(
            old.productId(),
            "Next",
            "current",
            "us",
            "2",
            old.buildConfig(),
            old.cdnConfig(),
            Instant.EPOCH.plusSeconds(1),
            true);
    var gateway =
        new Gateway(
            root, "https://kr.cdn.blizzard.com/newly-advertised", Instant.EPOCH.plusSeconds(100));
    var originalPlan = gateway.installPlan;
    gateway.installPlan = new InstallPlan(next, originalPlan.configuration(), C, E, 100, 80);
    try (var installs =
            new DefaultBootstrapService(
                gateway,
                first.catalog,
                STORE,
                Runnable::run,
                Runnable::run,
                null,
                first.authority);
        var roots =
            new DefaultFileLookupService(
                gateway,
                first.catalog,
                STORE,
                Runnable::run,
                Runnable::run,
                null,
                first.authority)) {
      if (root) {
        var plan = roots.planRoot(next, LookupBudgets.defaults()).toCompletableFuture().join();
        assertEquals(
            original,
            roots
                .retainRoot(new RootSelectionPlan(plan, plan.candidates().getFirst()))
                .toCompletableFuture()
                .join());
      } else {
        var plan = installs.inspectConfiguration(next).toCompletableFuture().join();
        assertEquals(
            original.evidence(),
            installs.download(plan).toCompletableFuture().join().verification());
      }
      assertEquals(0, gateway.transfers);
      assertEquals(1, gateway.replays);
      assertEquals(
          original.evidence(),
          first.catalog.references.get(next.snapshotId() + first.role()).evidence());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unreviewedProductNeverBypassesGuardOrBecomesGenericStorageFailure(boolean root) {
    var f = new Fixture(root);
    var unreviewed = build("wow_unknown");
    var original = f.gateway.installPlan;
    f.gateway.installPlan = new InstallPlan(unreviewed, original.configuration(), C, E, 100, 80);
    try {
      if (root) {
        var plan =
            f.lookup.planRoot(unreviewed, LookupBudgets.defaults()).toCompletableFuture().join();
        f.rootPlan = new RootSelectionPlan(plan, plan.candidates().getFirst());
      } else
        f.installPlan = f.install.inspectConfiguration(unreviewed).toCompletableFuture().join();
      var failed = assertThrows(CompletionException.class, f::retain).getCause();
      if (root)
        assertEquals(FileLookupException.Code.UNSUPPORTED, ((FileLookupException) failed).code());
      else
        assertEquals(BootstrapException.Reason.UNSUPPORTED, ((BootstrapException) failed).reason());
      assertEquals(0, f.authority.reservations);
      assertEquals(0, f.gateway.transfers);
    } finally {
      f.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void classicBetaMetadataUsesCanonicalGuardAndRetainsExactMembership(boolean root) {
    var f = new Fixture(root);
    var classic = build("wow_classic_beta");
    var original = f.gateway.installPlan;
    f.gateway.installPlan = new InstallPlan(classic, original.configuration(), C, E, 100, 80);
    try {
      if (root) {
        var plan =
            f.lookup.planRoot(classic, LookupBudgets.defaults()).toCompletableFuture().join();
        f.rootPlan = new RootSelectionPlan(plan, plan.candidates().getFirst());
      } else f.installPlan = f.install.inspectConfiguration(classic).toCompletableFuture().join();
      var retained = f.retain();
      assertEquals(1, f.gateway.transfers);
      assertEquals(1, f.authority.completions);
      var membership = f.catalog.references.get(classic.snapshotId() + f.role());
      assertEquals(classic.snapshotId(), membership.snapshotId());
      assertEquals(retained.source(), membership.source());
      assertEquals(retained.object(), membership.object());
      assertEquals(retained.evidence(), membership.evidence());
      assertFalse(f.catalog.references.containsKey(build("wow").snapshotId() + f.role()));
      assertEquals(retained, f.retain());
      assertEquals(1, f.gateway.transfers);
      assertEquals(1, f.gateway.replays);
    } finally {
      f.close();
    }
  }

  static final class Fixture implements AutoCloseable {
    final boolean root;
    final Gateway gateway;
    final Catalog catalog = new Catalog();
    final Authority authority = new Authority();
    final DefaultBootstrapService install;
    final DefaultFileLookupService lookup;
    InstallPlan installPlan;
    RootSelectionPlan rootPlan;

    Fixture(boolean root) {
      this.root = root;
      gateway = new Gateway(root);
      install =
          new DefaultBootstrapService(
              gateway, catalog, STORE, Runnable::run, Runnable::run, null, authority);
      lookup =
          new DefaultFileLookupService(
              gateway, catalog, STORE, Runnable::run, Runnable::run, null, authority);
    }

    String role() {
      return root ? "ROOT_MANIFEST" : "INSTALL_MANIFEST";
    }

    void plan() {
      if (root) {
        var p =
            lookup.planRoot(build("wow"), LookupBudgets.defaults()).toCompletableFuture().join();
        rootPlan = new RootSelectionPlan(p, p.candidates().getFirst());
      } else installPlan = install.inspectConfiguration(build("wow")).toCompletableFuture().join();
    }

    VerifiedSourceAlias retain() {
      if (root) return lookup.retainRoot(rootPlan).toCompletableFuture().join();
      install.download(installPlan).toCompletableFuture().join();
      return gateway.artifact;
    }

    public void close() {
      install.close();
      lookup.close();
    }
  }

  static final class Gateway implements BootstrapArtifactGateway, FileLookupGateway {
    final boolean root;
    final VerifiedSourceAlias artifact;
    final MetadataContentProof full, decoded;
    InstallPlan installPlan =
        new InstallPlan(
            build("wow"),
            new SourceDocument(configuration().evidence().sourceUrl(), Instant.EPOCH, new byte[10]),
            C,
            E,
            100,
            80);
    RootPlan canonical;
    int transfers, replays;
    boolean failAfterGate;

    Gateway(boolean root) {
      this(root, "https://us.cdn.blizzard.com/fixture", Instant.EPOCH);
    }

    Gateway(boolean root, String actualUrl, Instant at) {
      this.root = root;
      var sourceAlias =
          alias(
              new ResolvedSourceIdentity(
                  root ? "wow.root.tsfm.v1v2" : "tact.install.v1",
                  C + ";" + E + ";100;80",
                  "metadata.v1"),
              80);
      artifact =
          new VerifiedSourceAlias(
              sourceAlias.source(),
              sourceAlias.object(),
              new SourceVerificationEvidence(
                  sourceAlias.source().verificationSemantics(), actualUrl, at));
      decoded =
          new MetadataContentProof(
              artifact,
              E,
              C,
              OptionalLong.of(100),
              Optional.of(new DecodedContentAttestation(C, 100, "md5.v1")),
              Optional.empty());
      full =
          decoded.withAttestations(
              decoded.decodedAttestation(),
              Optional.of(
                  new MetadataStructureAttestation(
                      root
                          ? MetadataStructureAttestation.Kind.ROOT
                          : MetadataStructureAttestation.Kind.INSTALL,
                      "parser.v1")));
    }

    public ResolvedSourceIdentity configurationIdentity(BuildSnapshot b) {
      return configuration().source();
    }

    public InstallPlan inspectConfiguration(BuildSnapshot b, CancellationToken t) {
      return installPlan;
    }

    public void validatePlan(InstallPlan p, CancellationToken t) {
      assertEquals(installPlan, p);
    }

    public VerifiedSourceAlias retainConfiguration(InstallPlan p, CancellationToken t) {
      return configuration();
    }

    public InstallPlan restoreConfiguration(
        BuildSnapshot b, VerifiedSourceAlias a, CancellationToken t) {
      assertEquals(configuration(), a);
      return installPlan;
    }

    public ResolvedSourceIdentity installIdentity(InstallPlan p) {
      return artifact.source();
    }

    public VerifiedSourceAlias acquireInstall(InstallPlan p, CancellationToken t) {
      throw new AssertionError("unguarded");
    }

    public VerifiedSourceAlias acquireInstall(
        InstallPlan p, MetadataPublicationObserver o, CancellationToken t) {
      return transfer(o, t);
    }

    public InstallInspection inspectRetained(
        InstallPlan p, VerifiedSourceAlias a, CancellationToken t) {
      return new InstallInspection(p, a.object(), a.evidence(), List.of(), List.of());
    }

    public InstallInspection inspectRetained(
        InstallPlan p, VerifiedSourceAlias a, MetadataPublicationObserver o, CancellationToken t) {
      replays++;
      o.afterStructureValidation(replayProof(a), t);
      return inspectRetained(p, a, t);
    }

    private MetadataContentProof replayProof(VerifiedSourceAlias original) {
      return new MetadataContentProof(
          original,
          E,
          C,
          full.declaredDecodedBytes(),
          full.decodedAttestation(),
          full.structuralAttestation());
    }

    public RootPlan prepareRoot(
        BuildSnapshot b,
        Optional<VerifiedSourceAlias> a,
        LookupBudgets budgets,
        CancellationToken t) {
      canonical =
          new RootPlan(
              "session", b, configuration(), C, 100, List.of(new RootCandidate(E, 80, 0)), budgets);
      return canonical;
    }

    public void validateSelection(RootSelectionPlan p, CancellationToken t) {
      assertEquals(canonical, p.plan());
    }

    public ResolvedSourceIdentity rootIdentity(RootSelectionPlan p) {
      return artifact.source();
    }

    public VerifiedSourceAlias acquireRoot(RootSelectionPlan p, CancellationToken t) {
      throw new AssertionError("unguarded");
    }

    public VerifiedSourceAlias acquireRoot(
        RootSelectionPlan p, MetadataPublicationObserver o, CancellationToken t) {
      return transfer(o, t);
    }

    VerifiedSourceAlias transfer(MetadataPublicationObserver o, CancellationToken t) {
      transfers++;
      o.beforePromotion(root ? decoded : full, t);
      if (failAfterGate)
        throw new MetadataAcquisitionFailure(
            MetadataAcquisitionFailure.Publication.UNCERTAIN,
            new IllegalStateException("postgate"));
      o.afterStructureValidation(full, t);
      return artifact;
    }

    public RootLookupResult lookupRetained(
        BuildSnapshot b,
        VerifiedSourceAlias cfg,
        VerifiedSourceAlias a,
        Set<Long> ids,
        LookupBudgets budgets,
        CancellationToken t) {
      return new RootLookupResult(b, a, ids, List.of(), 1);
    }

    public RootLookupResult lookupRetained(
        BuildSnapshot b,
        VerifiedSourceAlias cfg,
        VerifiedSourceAlias a,
        Set<Long> ids,
        LookupBudgets budgets,
        MetadataPublicationObserver o,
        CancellationToken t) {
      replays++;
      o.afterStructureValidation(replayProof(a), t);
      return lookupRetained(b, cfg, a, ids, budgets, t);
    }
  }

  static final class Authority implements CanonicalContentRepository {
    ContentClaimException.Code block;
    int reservations, completions, releases, uncertain;
    CanonicalContentProof proof;
    UUID id = UUID.randomUUID();
    ContentSlotKey slot;

    ContentClaim claim(CanonicalReservation.Status s) {
      return new ContentClaim(slot, E, 80, id, s, Optional.ofNullable(proof));
    }

    public Optional<ContentClaim> lookup(ContentSlotKey s, CancellationToken t) {
      return slot == null
          ? Optional.empty()
          : Optional.of(claim(CanonicalReservation.Status.PUBLISHING));
    }

    public ContentClaim reserve(ContentSlotKey s, String e, long n, CancellationToken t) {
      reservations++;
      if (block != null) throw new ContentClaimException(block, "blocked");
      slot = s;
      return claim(
          proof == null
              ? CanonicalReservation.Status.PENDING
              : CanonicalReservation.Status.RETAINED);
    }

    ContentWorkerPermit active;

    public synchronized ContentWorkerPermit claimWorker(
        UUID token, CancellationToken cancellation) {
      if (active != null)
        throw new ContentClaimException(ContentClaimException.Code.BUSY, "Active source worker");
      active = new ContentWorkerPermit(token, UUID.randomUUID());
      return active;
    }

    void require(ContentWorkerPermit permit) {
      if (!permit.equals(active))
        throw new ContentClaimException(
            ContentClaimException.Code.INVALID_STATE, "Stale worker permit");
    }

    public void markPublishing(ContentWorkerPermit permit, CancellationToken token) {
      require(permit);
      markPublishing(permit.reservationToken(), token);
    }

    public void recordVerifiedCompletion(
        ContentWorkerPermit permit, CanonicalContentProof value, CancellationToken token) {
      require(permit);
      recordVerifiedCompletion(permit.reservationToken(), value, token);
    }

    public ContentClaim upgradeProof(
        ContentWorkerPermit permit, CanonicalContentProof value, CancellationToken token) {
      require(permit);
      return upgradeProof(permit.reservationToken(), value, token);
    }

    public ContentClaim completePublication(
        ContentWorkerPermit permit, CanonicalContentProof value, CancellationToken token) {
      require(permit);
      return completePublication(permit.reservationToken(), value, token);
    }

    public synchronized void finishWorker(
        ContentWorkerPermit permit, ContentWorkerFinishOutcome outcome, CancellationToken token) {
      require(permit);
      active = null;
      switch (outcome) {
        case CONCLUSIVELY_UNPUBLISHED -> releases++;
        case UNCERTAIN -> uncertain++;
        case RETAINED -> {}
      }
    }

    public void markPublishing(UUID i, CancellationToken t) {}

    public void recordVerifiedCompletion(UUID i, CanonicalContentProof p, CancellationToken t) {
      proof = p;
    }

    public ContentClaim upgradeProof(UUID i, CanonicalContentProof p, CancellationToken t) {
      proof = p;
      return claim(CanonicalReservation.Status.PUBLISHING);
    }

    public ContentClaim completePublication(UUID i, CanonicalContentProof p, CancellationToken t) {
      completions++;
      proof = p;
      return claim(CanonicalReservation.Status.RETAINED);
    }

    public void releaseUnpublished(UUID i, CancellationToken t) {
      releases++;
    }

    public void markUncertain(UUID i, CancellationToken t) {
      uncertain++;
    }
  }

  static final class Catalog implements BuildObjectCatalog {
    final Map<ResolvedSourceIdentity, VerifiedSourceAlias> aliases = new HashMap<>();
    final Map<String, BuildObjectReference> references = new HashMap<>();

    public void registerSnapshot(BuildSnapshot b) {}

    public Optional<StoredObject> findVerifiedAlias(ResolvedSourceIdentity s, CancellationToken t) {
      return findVerifiedSourceAlias(s, t).map(VerifiedSourceAlias::object);
    }

    public Optional<VerifiedSourceAlias> findVerifiedSourceAlias(
        ResolvedSourceIdentity s, CancellationToken t) {
      return Optional.ofNullable(aliases.get(s));
    }

    public BuildObjectReference attachVerifiedObject(
        String id,
        String role,
        ResolvedSourceIdentity s,
        StoredObject o,
        SourceVerificationEvidence e,
        CancellationToken t) {
      t.throwIfCancellationRequested();
      var r = new BuildObjectReference(id, role, s, o, e);
      references.put(id + role, r);
      aliases.put(s, new VerifiedSourceAlias(s, o, e));
      return r;
    }

    public List<BuildObjectReference> listReferences(String id) {
      return references.values().stream().filter(r -> r.snapshotId().equals(id)).toList();
    }

    public Optional<BuildObjectReference> findVerifiedObject(
        String id, String role, CancellationToken t) {
      return Optional.ofNullable(references.get(id + role));
    }
  }

  static final ObjectStore STORE =
      new ObjectStore() {
        public StoredObject publish(
            InputStream s, ExpectedObjectChecksum c, long m, CancellationToken t) {
          throw new AssertionError();
        }

        public StoredObject publishVerified(
            InputStream s, long n, long m, CompletionVerification v, CancellationToken t) {
          throw new AssertionError();
        }

        public Optional<StoredObject> verify(ObjectDigest d, CancellationToken t) {
          return Optional.of(new StoredObject(d, 80));
        }

        public ObjectReadLease openRead(ObjectDigest d, CancellationToken t) {
          throw new AssertionError();
        }

        public void close() {
          throw new AssertionError();
        }
      };
}
