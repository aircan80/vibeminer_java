package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.content.MetadataRepairException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.bootstrap.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * One bounded HEAD and local configuration/session replay only. Borrowed gateways/transports remain
 * caller-owned. No CAS publication, publisher marker, SQL access or canonical mutation occurs.
 */
public final class OfficialMetadataRepairGateway implements MetadataRepairGateway, AutoCloseable {
  public static final Duration HEAD_DEADLINE = MetadataRepairGateway.MAXIMUM_HEAD_DURATION;
  public static final Duration PLAN_LIFETIME = Duration.ofMinutes(5);
  private static final int MAXIMUM_PLANS = 16;
  private final BootstrapArtifactGateway installs;
  private final FileLookupGateway roots;
  private final CdnMetadataTransport metadata;
  private final Clock clock;
  private final LongSupplier nanos;
  private final Semaphore headCapacity = new Semaphore(4);
  private final LinkedHashMap<UUID, Issued> issued = new LinkedHashMap<>();
  private boolean closed;
  private final Set<CompletableFuture<DataSizeObservation>> pendingHeads = new HashSet<>();

  private record Issued(
      InstallPlan install,
      RootSelectionPlan root,
      ContentClaim original,
      MetadataRepairPlan plan,
      long createdNanos) {}

  public OfficialMetadataRepairGateway(
      BootstrapArtifactGateway installs,
      FileLookupGateway roots,
      CdnMetadataTransport metadata,
      Clock clock) {
    this(installs, roots, metadata, clock, System::nanoTime);
  }

  OfficialMetadataRepairGateway(
      BootstrapArtifactGateway installs,
      FileLookupGateway roots,
      CdnMetadataTransport metadata,
      Clock clock,
      LongSupplier nanos) {
    this.installs = Objects.requireNonNull(installs);
    this.roots = Objects.requireNonNull(roots);
    this.metadata = Objects.requireNonNull(metadata);
    this.clock = Objects.requireNonNull(clock);
    this.nanos = Objects.requireNonNull(nanos);
  }

  @Override
  public MetadataRepairPlan prepareInstall(
      InstallPlan base,
      VerifiedSourceAlias configuration,
      ContentClaim original,
      CancellationToken token) {
    ensureOpen();
    validateInstallBase(base, configuration, token);
    var request =
        new MetadataContentRequest(
            base.build(),
            configuration,
            MetadataStructureAttestation.Kind.INSTALL,
            new ContentSlotKey("wow", base.contentKey()),
            base.encodingKey(),
            base.encodedBytes(),
            OptionalLong.of(base.decodedBytes()));
    var proof = checkOriginal(request, installs.installIdentity(base), original);
    var cost =
        new MetadataRepairCost(
            base.encodedBytes(),
            base.encodedBytes(),
            1,
            twice(base.decodedBytes()),
            Duration.ofMinutes(10));
    var plan = observeAndIssue(request, proof, original, cost, token);
    remember(new Issued(base, null, original, plan, nanos.getAsLong()));
    return plan;
  }

  @Override
  public MetadataRepairPlan prepareRoot(
      RootSelectionPlan base, ContentClaim original, CancellationToken token) {
    ensureOpen();
    validateRootBase(base, token);
    var root = base.plan();
    var request =
        new MetadataContentRequest(
            root.build(),
            root.configuration(),
            MetadataStructureAttestation.Kind.ROOT,
            new ContentSlotKey("wow", root.contentKey()),
            base.candidate().encodingKey(),
            base.candidate().encodedBytes(),
            OptionalLong.of(root.decodedBytes()));
    var proof = checkOriginal(request, roots.rootIdentity(base), original);
    var work = twice(root.decodedBytes());
    if (work > root.budgets().maximumDecodedWorkBytes())
      throw failure(LIMIT, "Complete root verification exceeds the approved decoded-work limit");
    var cost =
        new MetadataRepairCost(
            base.candidate().encodedBytes(),
            base.candidate().encodedBytes(),
            (int) Math.min(Integer.MAX_VALUE, (long) root.budgets().maximumChunks() + 2),
            work,
            Duration.ofMinutes(10));
    var plan = observeAndIssue(request, proof, original, cost, token);
    remember(new Issued(null, base, original, plan, nanos.getAsLong()));
    return plan;
  }

  @Override
  public void validateInstall(InstallPlan base, MetadataRepairPlan plan, CancellationToken token) {
    var entry = requireIssued(plan, token);
    if (!Objects.equals(entry.install(), base) || entry.root() != null)
      throw failure(INVALID_PLAN, "Repair consent belongs to another ordinary install plan");
    validateInstallBase(base, plan.request().configuration(), token);
    checkOriginal(plan.request(), installs.installIdentity(base), entry.original());
    requireIssued(plan, token);
  }

  @Override
  public void validateRoot(
      RootSelectionPlan base, MetadataRepairPlan plan, CancellationToken token) {
    var entry = requireIssued(plan, token);
    if (!Objects.equals(entry.root(), base) || entry.install() != null)
      throw failure(INVALID_PLAN, "Repair consent belongs to another ordinary root selection");
    validateRootBase(base, token);
    checkOriginal(plan.request(), roots.rootIdentity(base), entry.original());
    requireIssued(plan, token);
  }

  private void validateInstallBase(
      InstallPlan base, VerifiedSourceAlias configuration, CancellationToken token) {
    Objects.requireNonNull(base);
    Objects.requireNonNull(configuration);
    token.throwIfCancellationRequested();
    if (base.repair().isPresent())
      throw failure(
          INVALID_PLAN,
          "An ordinary plan cannot acquire repair consent by stripping its existing repair field");
    requireNamespace(base.build());
    // The borrowed verifier reads/hash-checks actual retained config bytes and validates
    // its official original route. Equality includes the complete raw SourceDocument.
    if (!installs.configurationIdentity(base.build()).equals(configuration.source())
        || !installs.restoreConfiguration(base.build(), configuration, token).equals(base))
      throw failure(INTEGRITY, "Install plan differs from exact retained configuration proof");
    var route = resolve(base.build());
    var expected = CdnRequest.configuration(route, base.build().buildConfig()).uri().toString();
    if (expected.equals(base.configuration().sourceUrl())) installs.validatePlan(base, token);
  }

  private void validateRootBase(RootSelectionPlan base, CancellationToken token) {
    Objects.requireNonNull(base);
    token.throwIfCancellationRequested();
    if (base.plan().repair().isPresent())
      throw failure(
          INVALID_PLAN,
          "Repair-bearing root plans cannot issue new consent by stripping their fields");
    requireNamespace(base.plan().build());
    // Must be the SAME borrowed gateway that owns this selection's canonical session.
    roots.validateSelection(base, token);
    if (base.candidate().encodedBytes() < 1)
      throw failure(UNSUPPORTED, "Unknown EN size cannot authorize original-metadata repair");
  }

  private MetadataRepairPlan observeAndIssue(
      MetadataContentRequest request,
      MetadataContentProof proof,
      ContentClaim original,
      MetadataRepairCost cost,
      CancellationToken token) {
    token.throwIfCancellationRequested();
    var resource =
        new CdnResource(
            resolve(request.build()), request.requestedEncodingKey(), CdnResource.Kind.DATA);
    var head = new CdnSizeRequest(resource, request.requestedEncodedBytes());
    if (!headCapacity.tryAcquire()) throw failure(LIMIT, "Bounded metadata HEAD capacity is busy");
    DataSizeObservation observation;
    CompletableFuture<DataSizeObservation> future = null;
    long started = nanos.getAsLong();
    try {
      future = metadata.size(head);
      synchronized (this) {
        if (closed) {
          future.cancel(true);
          throw failure(CLOSED, "Repair planning gateway closed during HEAD admission");
        }
        pendingHeads.add(future);
      }
      observation = awaitHead(future, started, token);
    } finally {
      synchronized (this) {
        if (future != null) pendingHeads.remove(future);
      }
      headCapacity.release();
    }
    if (!observation.request().equals(head)
        || !observation.actualSourceUri().equals(resource.uri())
        || observation.observedWholeBytes() != request.requestedEncodedBytes())
      throw failure(INTEGRITY, "HEAD differs from exact original source or encoded geometry");
    var now = clock.instant();
    if (observation.receivedAt().isAfter(now)
        || Duration.between(observation.receivedAt(), now)
                .compareTo(MetadataRepairPlan.MAXIMUM_LIFETIME)
            > 0) throw failure(INTEGRITY, "HEAD observation is stale or lies after issuance");
    String digest =
        digest(
            "metadata-head-v1\n"
                + resource.uri()
                + "\n"
                + observation.observedWholeBytes()
                + "\n"
                + observation.receivedAt()
                + "\n");
    var source =
        new MetadataRepairSourceObservation(
            resource.uri().toString(),
            resource.key(),
            observation.observedWholeBytes(),
            observation.receivedAt(),
            digest);
    token.throwIfCancellationRequested();
    return new MetadataRepairPlan(
        UUID.randomUUID(),
        original.token(),
        request,
        proof,
        source,
        cost,
        now,
        now.plus(PLAN_LIFETIME));
  }

  private DataSizeObservation awaitHead(
      CompletableFuture<DataSizeObservation> future, long started, CancellationToken token) {
    try {
      while (true) {
        token.throwIfCancellationRequested();
        if (nanos.getAsLong() - started >= HEAD_DEADLINE.toNanos())
          throw failure(LIMIT, "Repair HEAD exceeded its fifteen-second planning deadline");
        try {
          DataSizeObservation observation = future.get(100, TimeUnit.MILLISECONDS);
          token.throwIfCancellationRequested();
          if (nanos.getAsLong() - started >= HEAD_DEADLINE.toNanos())
            throw failure(LIMIT, "Repair HEAD exceeded its fifteen-second planning deadline");
          return observation;
        } catch (TimeoutException pending) {
          /* cancellation and the separate HEAD deadline remain bounded */
        }
      }
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new CancellationException("Repair planning interrupted");
    } catch (ExecutionException failed) {
      future.cancel(true);
      throw headFailure(failed.getCause());
    } catch (RuntimeException failed) {
      future.cancel(true);
      throw failed;
    }
  }

  private static RuntimeException headFailure(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof CancellationException cancelled) return cancelled;
    if (failure instanceof AcquisitionException source)
      return new MetadataRepairException(
          switch (source.reason()) {
            case UNAVAILABLE, TRANSPORT -> UNAVAILABLE;
            case INTEGRITY -> INTEGRITY;
            case LIMIT -> LIMIT;
            case CANCELLED -> throw new CancellationException(source.getMessage());
          },
          "Original metadata HEAD could not be verified",
          source);
    return new MetadataRepairException(UNAVAILABLE, "Original metadata HEAD failed", failure);
  }

  private static MetadataContentProof checkOriginal(
      MetadataContentRequest request, ResolvedSourceIdentity identity, ContentClaim claim) {
    Objects.requireNonNull(claim);
    if (!claim.slot().equals(request.slot()))
      throw canonical(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          "Canonical claim belongs to another namespace or CKey");
    if (!claim.actualEncodingKey().equals(request.requestedEncodingKey()))
      throw canonical(
          ContentClaimException.Code.REPLAN_REQUIRED,
          "Only original canonical A can be planned for repair");
    if (claim.status() == CanonicalReservation.Status.PUBLISHING
        || claim.status() == CanonicalReservation.Status.PENDING)
      throw canonical(
          ContentClaimException.Code.BUSY, "Original metadata has an unfinished publisher intent");
    if (claim.encodedBytes() != request.requestedEncodedBytes())
      throw canonical(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          "Original encoded length conflicts with current declaration");
    if (claim.proof().isEmpty() || !(claim.proof().get() instanceof MetadataContentProof proof))
      throw canonical(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          "Original metadata proof is required; encoded-only history cannot authorize repair");
    var tuple = proof.originalAlias().source().immutableSourceKey().split(";", -1);
    if (!tuple[2].equals(Long.toString(request.declaredDecodedBytes().orElseThrow())))
      throw canonical(
          ContentClaimException.Code.INTEGRITY_FAILURE,
          "Independent decoded declaration conflicts with original metadata");
    if (!proof.originalAlias().source().equals(identity))
      throw canonical(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          "Original metadata source semantics are not supported by the current exact plan");
    try {
      CapturedBuildSource.validateDataProof(
          proof.originalAlias().evidence().sourceUrl(), proof.actualEncodingKey());
    } catch (SourceContextException invalid) {
      throw new MetadataRepairException(
          INTEGRITY, "Original data evidence is not a supported official proof", invalid);
    }
    return proof;
  }

  private synchronized void remember(Issued entry) {
    ensureOpen();
    issued.put(entry.plan().issuanceId(), entry);
    if (issued.size() > MAXIMUM_PLANS) issued.remove(issued.keySet().iterator().next());
  }

  private synchronized Issued requireIssued(MetadataRepairPlan plan, CancellationToken token) {
    ensureOpen();
    Objects.requireNonNull(plan);
    token.throwIfCancellationRequested();
    var entry = issued.get(plan.issuanceId());
    if (entry == null
        || !entry.plan().equals(plan)
        || !plan.isUsableAt(clock.instant())
        || nanos.getAsLong() - entry.createdNanos() >= PLAN_LIFETIME.toNanos()) {
      if (entry != null && entry.plan().equals(plan)) issued.remove(plan.issuanceId());
      throw failure(INVALID_PLAN, "Repair consent is unknown, changed, evicted or expired");
    }
    return entry;
  }

  private static void requireNamespace(BuildSnapshot build) {
    if (!build.productId().equals("wow"))
      throw canonical(
          ContentClaimException.Code.UNSUPPORTED_REUSE,
          "No reviewed metadata content namespace for this product");
  }

  private static CdnRequest.Source resolve(BuildSnapshot build) {
    try {
      return CapturedBuildSource.resolve(build);
    } catch (SourceContextException invalid) {
      throw new MetadataRepairException(
          invalid.reason() == SourceContextException.Reason.UNSUPPORTED ? UNSUPPORTED : INTEGRITY,
          "Exact captured official source is unsupported",
          invalid);
    }
  }

  private static long twice(long decoded) {
    if (decoded > Long.MAX_VALUE / 2) throw failure(LIMIT, "Decoded work overflows repair bounds");
    return decoded * 2;
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static MetadataRepairException failure(
      MetadataRepairException.Code code, String message) {
    return new MetadataRepairException(code, message);
  }

  private static ContentClaimException canonical(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }

  private synchronized void ensureOpen() {
    if (closed) throw failure(CLOSED, "Repair planning gateway closed");
  }

  @Override
  public void close() {
    List<CompletableFuture<DataSizeObservation>> pending;
    synchronized (this) {
      closed = true;
      issued.clear();
      pending = List.copyOf(pendingHeads);
    }
    pending.forEach(future -> future.cancel(true));
  }
}
