package io.vibeminer.application.content;

import static io.vibeminer.application.content.ContentClaimException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.*;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Source-worker policy for the first metadata exclusion guard; never chooses another encoding. */
public final class MetadataContentAcquisition {
  @FunctionalInterface
  public interface Transfer {
    VerifiedSourceAlias acquire(
        MetadataPublicationObserver observer, CancellationToken cancellation);
  }

  @FunctionalInterface
  public interface Replay {
    void inspect(
        VerifiedSourceAlias alias,
        MetadataPublicationObserver observer,
        CancellationToken cancellation);
  }

  private final CanonicalContentRepository repository;

  public MetadataContentAcquisition(CanonicalContentRepository repository) {
    this.repository = Objects.requireNonNull(repository);
  }

  /**
   * Invoke inside the actual source Runnable, never on consumer cancellation or on the UI thread.
   */
  public VerifiedSourceAlias acquire(
      MetadataContentRequest request,
      ResolvedSourceIdentity source,
      Supplier<Optional<VerifiedSourceAlias>> retained,
      Transfer transfer,
      Replay replay,
      CancellationToken cancellation) {
    cancellation.throwIfCancellationRequested();
    checkSource(request, source);
    var initialClaim =
        repository.reserve(
            request.slot(),
            request.requestedEncodingKey(),
            request.requestedEncodedBytes(),
            cancellation);
    checkClaim(request, source, initialClaim);
    // Reservation identity can be shared by independent coordinators. Only this
    // actual source worker's nonce authorizes publication or retirement.
    var permit = repository.claimWorker(initialClaim.token(), cancellation);
    if (!permit.reservationToken().equals(initialClaim.token()))
      throw failure(INTEGRITY_FAILURE, "Worker permit belongs to another reservation");
    Observer state = null;
    var outcome = ContentWorkerFinishOutcome.UNCERTAIN;
    Throwable primaryFailure = null;
    boolean enteredTransfer = false;
    boolean existingObject = initialClaim.proof().isPresent();
    try {
      cancellation.throwIfCancellationRequested();
      // Another publisher may have completed between passive reserve and worker
      // admission. Read its durable proof under our nonce before choosing payload.
      var claim =
          repository
              .lookup(request.slot(), cancellation)
              .orElseThrow(
                  () -> failure(INTEGRITY_FAILURE, "Owned canonical reservation disappeared"));
      if (!claim.token().equals(initialClaim.token()))
        throw failure(INTEGRITY_FAILURE, "Worker admission changed canonical reservation token");
      existingObject = claim.proof().isPresent();
      checkClaim(request, source, claim);
      state = new Observer(request, source, claim, permit);
      repository.markPublishing(permit, cancellation);
      var local = claim.proof().map(value -> ((MetadataContentProof) value).originalAlias());
      if (local.isEmpty()) local = retained.get();
      VerifiedSourceAlias result;
      if (local.isPresent()) {
        existingObject = true;
        state.replaying = true;
        result = local.get();
        replay.inspect(result, state, cancellation);
      } else {
        enteredTransfer = true;
        result = transfer.acquire(state, cancellation);
      }
      cancellation.throwIfCancellationRequested();
      if (state.complete == null || !state.complete.originalAlias().equals(result))
        throw failure(
            INTEGRITY_FAILURE, "Metadata result lacks its exact supported-grammar attestation");
      var completed = repository.completePublication(permit, state.complete, cancellation);
      if (!completed.token().equals(claim.token())
          || !completed.slot().equals(claim.slot())
          || completed.proof().isEmpty()
          || !(completed.proof().get() instanceof MetadataContentProof canonical))
        throw failure(INTEGRITY_FAILURE, "Canonical metadata completion changed its claim");
      check(request, source, canonical, true);
      if (!canonical.equals(state.complete))
        throw failure(
            INTEGRITY_FAILURE,
            "Canonical metadata completion changed original verification evidence");
      outcome = ContentWorkerFinishOutcome.RETAINED;
      return canonical.originalAlias();
    } catch (RuntimeException failed) {
      primaryFailure = failed;
      boolean conclusive =
          !enteredTransfer
              || failed instanceof MetadataAcquisitionFailure scoped
                  && scoped.publication()
                      == MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED;
      if (conclusive && (state == null || state.prospective == null) && !existingObject)
        outcome = ContentWorkerFinishOutcome.CONCLUSIVELY_UNPUBLISHED;
      throw failed;
    } catch (Error failed) {
      primaryFailure = failed;
      throw failed;
    } finally {
      // All gateway I/O and its verifier gates have exited. Facade cancellation
      // never executes this fence and cannot retire a still-running publisher.
      try {
        repository.finishWorker(permit, outcome, CancellationToken.none());
      } catch (RuntimeException cleanup) {
        if (primaryFailure != null) primaryFailure.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }

  private final class Observer implements MetadataPublicationObserver {
    final MetadataContentRequest request;
    final ResolvedSourceIdentity source;
    final ContentClaim claim;
    final ContentWorkerPermit permit;
    MetadataContentProof prospective;
    MetadataContentProof complete;
    boolean replaying;

    Observer(
        MetadataContentRequest request,
        ResolvedSourceIdentity source,
        ContentClaim claim,
        ContentWorkerPermit permit) {
      this.request = request;
      this.source = source;
      this.claim = claim;
      this.permit = permit;
    }

    @Override
    public void beforePromotion(MetadataContentProof proof, CancellationToken cancellation) {
      cancellation.throwIfCancellationRequested();
      check(request, source, proof, false);
      if (proof.decodedAttestation().isEmpty() || prospective != null)
        throw failure(
            INTEGRITY_FAILURE, "Missing decoded proof or repeated metadata promotion gate");
      // Remember before calling persistence: a throwing transaction may have committed its proof.
      prospective = proof;
      repository.recordVerifiedCompletion(permit, proof, cancellation);
    }

    @Override
    public void afterStructureValidation(
        MetadataContentProof proof, CancellationToken cancellation) {
      cancellation.throwIfCancellationRequested();
      check(request, source, proof, true);
      if (complete != null) throw failure(INTEGRITY_FAILURE, "Metadata parser gate invoked twice");
      if (prospective == null) {
        if (!replaying)
          throw failure(
              INTEGRITY_FAILURE, "Fresh metadata transfer skipped its prepromotion verifier gate");
        // Local replay verifies an existing object; there is no physical publication gate.
        prospective = proof;
        if (claim.proof().isPresent()) {
          ContentProofPolicy.requireMonotonicUpgrade(claim.proof().get(), proof);
          repository.upgradeProof(permit, proof, cancellation);
        } else repository.recordVerifiedCompletion(permit, proof, cancellation);
      } else {
        ContentProofPolicy.requireMonotonicUpgrade(prospective, proof);
        repository.upgradeProof(permit, proof, cancellation);
      }
      complete = proof;
    }
  }

  private static void checkClaim(
      MetadataContentRequest request, ResolvedSourceIdentity source, ContentClaim claim) {
    if (!claim.slot().equals(request.slot())
        || !claim.actualEncodingKey().equals(request.requestedEncodingKey())
        || claim.encodedBytes() != request.requestedEncodedBytes())
      throw failure(INTEGRITY_FAILURE, "Canonical claim changed selected content or geometry");
    if (claim.proof().isPresent() && !(claim.proof().get() instanceof MetadataContentProof))
      throw failure(
          UNSUPPORTED_REUSE, "Encoded-only content cannot authorize metadata interpretation");
    if (claim.proof().isPresent())
      check(request, source, (MetadataContentProof) claim.proof().get(), false);
  }

  private static void checkSource(MetadataContentRequest request, ResolvedSourceIdentity source) {
    String namespace =
        switch (request.kind()) {
          case ROOT -> "wow.root.tsfm.v1v2";
          case INSTALL -> "tact.install.v1";
          case ENCODING -> "tact.encoding.v1";
          case DOWNLOAD -> "tact.download.v1v2v3";
          case DOWNLOAD_SIZE -> "tact.download-size.v1";
        };
    var tuple = source.immutableSourceKey().split(";", -1);
    if (!source.namespace().equals(namespace)
        || tuple.length != 4
        || !tuple[0].equals(request.slot().contentKey())
        || !tuple[1].equals(request.requestedEncodingKey())
        || !positiveCanonicalLength(tuple[2])
        || !tuple[3].equals(Long.toString(request.requestedEncodedBytes()))
        || request.declaredDecodedBytes().isPresent()
            && !tuple[2].equals(Long.toString(request.declaredDecodedBytes().getAsLong())))
      throw failure(
          INTEGRITY_FAILURE, "Metadata source identity disagrees with exact requested declaration");
  }

  private static boolean positiveCanonicalLength(String value) {
    if (!value.matches("[1-9][0-9]{0,18}")) return false;
    try {
      return Long.parseLong(value) > 0;
    } catch (NumberFormatException overflow) {
      return false;
    }
  }

  private static void check(
      MetadataContentRequest request,
      ResolvedSourceIdentity source,
      MetadataContentProof proof,
      boolean complete) {
    if (!proof.declaredContentKey().equals(request.slot().contentKey())
        || !proof.actualEncodingKey().equals(request.requestedEncodingKey())
        || proof.object().length() != request.requestedEncodedBytes()
        || request.declaredDecodedBytes().isPresent()
            && (proof.declaredDecodedBytes().isPresent()
                    && request.declaredDecodedBytes().getAsLong()
                        != proof.declaredDecodedBytes().getAsLong()
                || proof.decodedAttestation().isPresent()
                    && request.declaredDecodedBytes().getAsLong()
                        != proof.decodedAttestation().get().actualDecodedBytes()))
      throw failure(INTEGRITY_FAILURE, "Verifier facts disagree with exact metadata declaration");
    if (!proof.originalAlias().source().equals(source))
      throw failure(
          UNSUPPORTED_REUSE, "Original metadata representation has another source capability");
    if (proof.structuralAttestation().isPresent()
        && proof.structuralAttestation().get().kind() != request.kind())
      throw failure(
          UNSUPPORTED_REUSE, "Original metadata grammar differs from requested interpretation");
    if (complete) ContentProofPolicy.requirePublicationReady(proof);
  }

  private static ContentClaimException failure(ContentClaimException.Code code, String message) {
    return new ContentClaimException(code, message);
  }
}
