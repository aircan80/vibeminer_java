package io.vibeminer.infrastructure.encodedsource;

import static io.vibeminer.application.encodedsource.EncodedSourceException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.protocol.tact.blte.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/**
 * The single encoded publisher recipe. Trusted facade issuers validate their plan before calling.
 */
public final class OfficialEncodedSourcePublisher {
  private final ObjectStore store;
  private final PinnedCdnRangeTransport ranges;
  private final LongSupplier nanos;

  public OfficialEncodedSourcePublisher(ObjectStore store, PinnedCdnRangeTransport ranges) {
    this(store, ranges, System::nanoTime);
  }

  public OfficialEncodedSourcePublisher(
      ObjectStore store, PinnedCdnRangeTransport ranges, LongSupplier nanos) {
    this.store = Objects.requireNonNull(store);
    this.ranges = Objects.requireNonNull(ranges);
    this.nanos = Objects.requireNonNull(nanos);
  }

  public ContentRepresentation acquire(
      EncodedSourcePlan plan,
      EncodedSourceGateway.Observer observer,
      CancellationToken cancellation) {
    var promotionPossible = new AtomicBoolean();
    try {
      store.publisherStarted();
      var budget = plan.limits();
      var token = stage(budget.maximumDuration(), cancellation);
      Objects.requireNonNull(observer);
      var locator = plan.location();
      var resource =
          new CdnResource(
              CapturedBuildSource.resolve(plan.sourceBuild()),
              locator.resourceKey(),
              CdnResource.Kind.DATA);
      if (!resource.uri().toString().equals(locator.resourceUrl()))
        throw fail(INTEGRITY_FAILURE, "Canonical payload route changed");
      var observations = new Observations();
      var prospective = new AtomicReference<ContentRepresentation>();
      try (var source =
              new PinnedRangeInputStream(
                  ranges,
                  resource,
                  locator.resourceBytes(),
                  locator.childOffset(),
                  locator.childLength(),
                  budget.maximumRangeBytes(),
                  new PinnedRangeInputStream.Limits(
                      Math.min(budget.maximumWireBytes(), plan.cost().maximumPayloadWireBytes()),
                      budget.maximumRequests(),
                      budget.maximumDuration()),
                  token::isCancellationRequested,
                  observations::accept);
          var encoded =
              new EncodedBlteInputStream(
                  source,
                  plan.encodingKey(),
                  plan.encodedBytes(),
                  encodedLimits(budget),
                  token::isCancellationRequested)) {
        var stored =
            store.publishVerified(
                encoded,
                plan.encodedBytes(),
                budget.maximumEncodedBytes(),
                completionToken -> {
                  completionToken.throwIfCancellationRequested();
                  try {
                    var proof = encoded.completionProof();
                    var object =
                        new StoredObject(
                            new ObjectDigest(proof.physicalSha256()), proof.encodedBytes());
                    var receipt = observations.receipt(locator);
                    var alias =
                        new VerifiedSourceAlias(
                            ContentRepresentation.sourceIdentity(proof.encodingKey()),
                            object,
                            new SourceVerificationEvidence(
                                ContentRepresentation.VERIFIER_SEMANTICS,
                                receipt.actualResourceUrl(),
                                receipt.observedAt()));
                    var complete =
                        new ContentRepresentation(
                            plan.slot(),
                            alias,
                            EncodedScope.valueOf(proof.scope().name()),
                            receipt);
                    prospective.set(complete);
                    promotionPossible.set(true);
                    observer.verifiedBeforePromotion(complete);
                  } catch (IOException error) {
                    throw fail(INTEGRITY_FAILURE, "Encoded completion proof failed", error);
                  }
                },
                token);
        var complete = prospective.get();
        if (complete == null || !complete.encoded().object().equals(stored))
          throw fail(
              INTEGRITY_FAILURE,
              "Physical publication differs from complete encoded verifier proof");
        return complete;
      }
    } catch (Exception error) {
      throw new EncodedSourceGateway.AcquisitionFailure(
          promotionPossible.get()
              ? EncodedSourceGateway.AcquisitionFailure.Publication.UNCERTAIN
              : EncodedSourceGateway.AcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED,
          classify(error));
    }
  }

  /** Physical encoded verification only. The caller replays its own root/EN or sealed inventory. */
  public ContentRepresentation inspect(
      ContentRepresentation retained, EncodedSourcePlan.Limits limits, CancellationToken token) {
    try (var lease = store.openRead(retained.encoded().object().digest(), token);
        var encoded =
            new EncodedBlteInputStream(
                lease.stream(),
                retained.encoded().source().immutableSourceKey(),
                retained.encoded().object().length(),
                encodedLimits(limits),
                token::isCancellationRequested)) {
      if (!lease.object().equals(retained.encoded().object()))
        throw fail(INTEGRITY_FAILURE, "Retained encoded physical metadata changed");
      byte[] buffer = new byte[8192];
      while (encoded.read(buffer) != -1) token.throwIfCancellationRequested();
      var completion = encoded.completionProof();
      if (!completion.physicalSha256().equals(retained.encoded().object().digest().value())
          || !completion.scope().name().equals(retained.scope().name()))
        throw fail(INTEGRITY_FAILURE, "Retained encoded verification scope changed");
      return retained;
    } catch (Exception failure) {
      throw classify(failure);
    }
  }

  private static EncodedBlteInputStream.Limits encodedLimits(EncodedSourcePlan.Limits budget) {
    return new EncodedBlteInputStream.Limits(
        budget.maximumHeaderBytes(),
        budget.maximumChunks(),
        budget.maximumEncodedBytes(),
        budget.maximumEncodedBytes());
  }

  private CancellationToken stage(Duration duration, CancellationToken cancellation) {
    long start = nanos.getAsLong();
    return () -> {
      cancellation.throwIfCancellationRequested();
      if (nanos.getAsLong() - start >= duration.toNanos())
        throw fail(BUDGET_EXCEEDED, "Encoded source operation deadline exceeded");
      return false;
    };
  }

  private static RuntimeException classify(Throwable original) {
    var error = original;
    while ((error instanceof CompletionException
            || error instanceof ExecutionException
            || error instanceof IOException && !(error instanceof InterruptedIOException))
        && error.getCause() != null) error = error.getCause();
    if (error instanceof EncodedSourceException typed) return typed;
    if (error instanceof CancellationException canceled) return canceled;
    if (error instanceof InterruptedIOException) {
      var canceled = new CancellationException("Encoded source operation cancelled");
      canceled.initCause(error);
      return canceled;
    }
    if (error instanceof AcquisitionException acquisition)
      return fail(
          switch (acquisition.reason()) {
            case UNAVAILABLE, TRANSPORT -> UNAVAILABLE;
            case LIMIT -> BUDGET_EXCEEDED;
            case CANCELLED -> throw new CancellationException("Encoded source operation cancelled");
            case INTEGRITY -> INTEGRITY_FAILURE;
          },
          acquisition.getMessage(),
          acquisition);
    if (error instanceof EncodedBlteException protocol) {
      if (protocol.reason() == EncodedBlteException.Reason.CANCELLED)
        return new CancellationException("Encoded verifier cancelled");
      return fail(
          switch (protocol.reason()) {
            case LIMIT -> BUDGET_EXCEEDED;
            case UNSUPPORTED -> UNSUPPORTED;
            case CLOSED -> CLOSED;
            default -> INTEGRITY_FAILURE;
          },
          protocol.getMessage(),
          protocol);
    }
    if (error instanceof ObjectStoreException storage
        && storage.getCause() != null
        && storage.code() == ObjectStoreException.Code.IO_FAILURE) {
      Throwable nested = storage.getCause();
      if (nested instanceof IOException && nested.getCause() != null) nested = nested.getCause();
      if (nested instanceof EncodedBlteException
          || nested instanceof AcquisitionException
          || nested instanceof CancellationException
          || nested instanceof EncodedSourceException) return classify(nested);
    }
    if (error instanceof ObjectStoreException storage)
      return fail(
          switch (storage.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
            case LIMIT_EXCEEDED -> BUDGET_EXCEEDED;
            default -> STORAGE_UNAVAILABLE;
          },
          storage.getMessage(),
          storage);
    return fail(INTEGRITY_FAILURE, "Encoded source/protocol operation failed", error);
  }

  private static EncodedSourceException fail(EncodedSourceException.Code code, String message) {
    return new EncodedSourceException(code, message);
  }

  private static EncodedSourceException fail(
      EncodedSourceException.Code code, String message, Throwable cause) {
    return new EncodedSourceException(code, message, cause);
  }

  private static final class Observations {
    final MessageDigest digest;
    long count, bytes;
    Instant latest;

    Observations() {
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }

    void accept(PinnedRangeInputStream.RangeObservation observation) {
      String canonical =
          observation.request().uri()
              + "\n"
              + observation.request().wholeEncodedLength()
              + "\n"
              + observation.request().start()
              + "\n"
              + observation.responseBytes()
              + "\n"
              + observation.actualSourceUri()
              + "\n"
              + observation.receivedAt()
              + "\n"
              + observation.rangeSha256()
              + "\n";
      digest.update(canonical.getBytes(StandardCharsets.UTF_8));
      count++;
      bytes = Math.addExact(bytes, observation.responseBytes());
      latest = observation.receivedAt();
    }

    EncodedSourceReceipt receipt(EncodedSourcePlan.Location locator) {
      if (count < 1 || bytes != locator.childLength())
        throw fail(INTEGRITY_FAILURE, "Complete child lacks exact transport observation coverage");
      return new EncodedSourceReceipt(
          EncodedSourceReceipt.Kind.valueOf(locator.kind().name()),
          locator.resourceUrl(),
          locator.resourceKey(),
          locator.resourceBytes(),
          locator.childOffset(),
          locator.childLength(),
          latest,
          count,
          bytes,
          HexFormat.of().formatHex(digest.digest()),
          locator.archiveProof());
    }
  }
}
