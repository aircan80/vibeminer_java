package io.vibeminer.infrastructure.lookup;

import io.vibeminer.application.*;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.format.wow.root.SelectedRootLookup;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.bootstrap.InstallMetadataVerifier;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Exact-object repair mechanics only. Caller owns issuance, worker admission and durable auditing.
 */
public final class OfficialMetadataRepairExecutionGateway
    implements MetadataRepairExecutionGateway {
  private final MetadataRepairGateway issuer;
  private final ObjectStore store;
  private final LookupTransport transport;
  private final Clock clock;
  private final LongSupplier nanos;

  public OfficialMetadataRepairExecutionGateway(
      MetadataRepairGateway issuer,
      ObjectStore store,
      PinnedCdnTransport whole,
      PinnedCdnRangeTransport ranges,
      Clock clock) {
    this(
        issuer,
        store,
        new LookupTransport() {
          public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
            return whole.fetch(request);
          }

          public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
            return ranges.fetch(request);
          }
        },
        clock);
  }

  OfficialMetadataRepairExecutionGateway(
      MetadataRepairGateway issuer, ObjectStore store, LookupTransport transport, Clock clock) {
    this(issuer, store, transport, clock, System::nanoTime);
  }

  OfficialMetadataRepairExecutionGateway(
      MetadataRepairGateway issuer,
      ObjectStore store,
      LookupTransport transport,
      Clock clock,
      LongSupplier nanos) {
    this.issuer = Objects.requireNonNull(issuer);
    this.store = Objects.requireNonNull(store);
    this.transport = Objects.requireNonNull(transport);
    this.clock = Objects.requireNonNull(clock);
    this.nanos = Objects.requireNonNull(nanos);
  }

  @Override
  public MetadataRepairObservation repairInstall(
      InstallPlan ordinary,
      MetadataRepairPlan issued,
      UUID attempt,
      MetadataRepairObserver observer,
      CancellationToken cancellation) {
    boolean[] gate = {false};
    try {
      requireArguments(attempt, observer, cancellation);
      issuer.validateInstall(ordinary, issued, cancellation);
      var route = requireRoute(ordinary.build(), issued);
      var work = new RepairWork(issued, cancellation, nanos);
      work.decodedWork(ordinary.decodedBytes());
      if (ordinary.encodedBytes() > InstallMetadataVerifier.MAXIMUM_ENCODED_BYTES)
        throw new MetadataRepairException(
            MetadataRepairException.Code.LIMIT, "Install repair exceeds bounded verifier");
      store.publisherStarted();
      var request =
          CdnRequest.loose(
              route,
              ordinary.encodingKey(),
              ordinary.encodedBytes(),
              (int) ordinary.encodedBytes());
      work.reserve(ordinary.encodedBytes());
      var response = work.await(transport.configuration(request));
      byte[] bytes = response.bytes();
      if (!response.request().equals(request) || bytes.length != ordinary.encodedBytes())
        throw integrity("Install repair response differs from pinned request");
      work.observe(request.uri().toString(), 0, bytes.length, response.receivedAt());
      new InstallMetadataVerifier()
          .decodeAndParse(
              bytes,
              ordinary.decodedBytes(),
              ordinary.encodingKey(),
              ordinary.contentKey(),
              work::cancelled);
      var observation = observation(issued, attempt, work, true);
      var object =
          store.repairVerified(
              new ByteArrayInputStream(bytes),
              issued.originalProof().object(),
              issued.cost().maximumAdditionalPhysicalBytes(),
              token -> {
                work.check();
                observer.beforePromotion(observation, token);
                gate[0] = true;
                work.check();
              },
              work::cancelled);
      requireObject(issued, object);
      work.check();
      observer.afterStructureValidation(observation, work::cancelled);
      work.check();
      return observation;
    } catch (Exception failed) {
      throw classified(failed, gate[0]);
    }
  }

  @Override
  public MetadataRepairObservation repairRoot(
      RootSelectionPlan ordinary,
      MetadataRepairPlan issued,
      UUID attempt,
      MetadataRepairObserver observer,
      CancellationToken cancellation) {
    boolean[] gate = {false};
    try {
      requireArguments(attempt, observer, cancellation);
      issuer.validateRoot(ordinary, issued, cancellation);
      var plan = ordinary.plan();
      var route = requireRoute(plan.build(), issued);
      var work = new RepairWork(issued, cancellation, nanos);
      work.decodedWork(Math.multiplyExact(plan.decodedBytes(), 2));
      var lookup = new LookupWork(plan.budgets(), work::cancelled, false);
      var supervised = supervised(work);
      store.publisherStarted();
      var remote =
          new RemoteBlteObject(
              route,
              ordinary.candidate().encodingKey(),
              ordinary.candidate().encodedBytes(),
              plan.decodedBytes(),
              supervised,
              plan.budgets(),
              lookup,
              false);
      for (var chunk : remote.plan.chunks()) remote.checkChunk(chunk);
      MetadataRepairObservation[] prospective = {null};
      StoredObject object;
      try (var encoded = new VerifiedRootStreams.Encoded(remote, plan.contentKey(), lookup)) {
        object =
            store.repairVerified(
                encoded,
                issued.originalProof().object(),
                issued.cost().maximumAdditionalPhysicalBytes(),
                token -> {
                  encoded.verifyComplete(token);
                  prospective[0] = observation(issued, attempt, work, false);
                  observer.beforePromotion(prospective[0], token);
                  gate[0] = true;
                  work.check();
                },
                work::cancelled);
      }
      requireObject(issued, object);
      parseRoot(object, ordinary, lookup, work);
      var complete =
          prospective[0].withStructuralAttestation(
              new MetadataStructureAttestation(
                  MetadataStructureAttestation.Kind.ROOT, "wow-root-tsfm-v1v2-r1"));
      work.check();
      observer.afterStructureValidation(complete, work::cancelled);
      work.check();
      return complete;
    } catch (Exception failed) {
      throw classified(failed, gate[0]);
    }
  }

  private LookupTransport supervised(RepairWork work) {
    return new LookupTransport() {
      public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
        throw new AssertionError("Root repair cannot fetch configuration");
      }

      public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
        work.reserve(request.count());
        var response = work.await(transport.range(request));
        if (!response.request().equals(request)
            || !response.actualSourceUri().equals(request.uri()))
          throw integrity("Root repair range differs from pinned request");
        work.observe(
            response.actualSourceUri().toString(),
            request.start(),
            response.bytes().length,
            response.receivedAt());
        return CompletableFuture.completedFuture(response);
      }
    };
  }

  private void parseRoot(
      StoredObject object, RootSelectionPlan ordinary, LookupWork lookup, RepairWork work)
      throws Exception {
    var plan = ordinary.plan();
    var budgets = plan.budgets();
    try (var lease = store.openRead(object.digest(), work::cancelled);
        var decoded =
            new VerifiedRootStreams.Decoded(
                lease.stream(),
                ordinary.candidate().encodingKey(),
                plan.contentKey(),
                object.length(),
                plan.decodedBytes(),
                budgets,
                lookup)) {
      if (!lease.object().equals(object))
        throw integrity("Repaired root physical identity changed");
      var limits =
          new SelectedRootLookup.Limits(
              budgets.maximumRootDecodedBytes(),
              budgets.maximumRootRecords(),
              100000,
              budgets.maximumRootRecords(),
              budgets.maximumSelectedIds(),
              budgets.maximumMatches());
      new SelectedRootLookup().read(decoded, Set.of(), limits, work::cancelled);
      decoded.requireComplete();
    }
  }

  private MetadataRepairObservation observation(
      MetadataRepairPlan issued, UUID attempt, RepairWork work, boolean grammar) {
    var original = issued.originalProof();
    var alias =
        new VerifiedSourceAlias(
            original.originalAlias().source(),
            original.object(),
            new SourceVerificationEvidence(
                original.originalAlias().source().verificationSemantics(),
                issued.sourceObservation().resourceUrl(),
                clock.instant()));
    return new MetadataRepairObservation(
        attempt,
        original,
        alias,
        new DecodedContentAttestation(
            original.declaredContentKey(),
            issued.request().declaredDecodedBytes().orElseThrow(),
            "blte-nz-full-ckey-v1"),
        grammar
            ? Optional.of(
                new MetadataStructureAttestation(
                    MetadataStructureAttestation.Kind.INSTALL, "install-manifest-v1-r1"))
            : Optional.empty(),
        work.requests(),
        work.wire(),
        work.completeDigest());
  }

  private static CdnRequest.Source requireRoute(BuildSnapshot build, MetadataRepairPlan issued) {
    var route = CapturedBuildSource.resolve(build);
    if (!new CdnResource(route, issued.originalProof().actualEncodingKey(), CdnResource.Kind.DATA)
        .uri()
        .toString()
        .equals(issued.sourceObservation().resourceUrl()))
      throw integrity("Repair route differs from issued HEAD");
    return route;
  }

  private static void requireArguments(
      UUID attempt, MetadataRepairObserver observer, CancellationToken token) {
    Objects.requireNonNull(attempt);
    Objects.requireNonNull(observer);
    Objects.requireNonNull(token).throwIfCancellationRequested();
  }

  private static void requireObject(MetadataRepairPlan issued, StoredObject object) {
    if (!issued.originalProof().object().equals(object))
      throw integrity("Repair changed original physical identity");
  }

  private static MetadataRepairException integrity(String message) {
    return new MetadataRepairException(MetadataRepairException.Code.INTEGRITY, message);
  }

  private static MetadataAcquisitionFailure classified(Exception failed, boolean gateReturned) {
    RuntimeException cause =
        failed instanceof RuntimeException runtime ? runtime : LookupWork.failure(failed);
    return new MetadataAcquisitionFailure(
        gateReturned
            ? MetadataAcquisitionFailure.Publication.UNCERTAIN
            : MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
        cause);
  }
}
