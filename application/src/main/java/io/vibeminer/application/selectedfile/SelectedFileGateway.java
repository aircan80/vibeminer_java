package io.vibeminer.application.selectedfile;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;

/** Blocking source/protocol mechanics; canonical validation is required before cached reuse too. */
public interface SelectedFileGateway {
  CandidatePlan prepareCandidates(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      FileVariantAssociation variant,
      SelectedFileBudgets budgets,
      CancellationToken cancellation);

  void validateCandidatePlan(CandidatePlan plan, CancellationToken cancellation);

  LooseProbe probeLoose(CandidateChoice choice, CancellationToken cancellation);

  void validateLoose404Permit(Loose404Permit permit, CancellationToken cancellation);

  ArchiveSearchResult searchArchives(
      Loose404Permit permit, ArchiveSearchBudgets budgets, CancellationToken cancellation);

  void validateLocatedPlan(LocatedFilePlan plan, CancellationToken cancellation);

  ContentRepresentation acquireEncoded(
      LocatedFilePlan plan, EncodedPublicationObserver observer, CancellationToken cancellation);

  ContentRepresentation inspectRetained(
      SelectedFileAssociation association,
      SelectedFileBudgets budgets,
      CancellationToken cancellation);

  /** Validated legacy locator bridge; the captured authority is borrowed, never consumer-owned. */
  default EncodedSourceAuthorization authorizeEncodedSource(
      LocatedFilePlan located, CancellationToken cancellation) {
    validateLocatedPlan(located, cancellation);
    var choice = located.choice();
    var budgets = choice.plan().budgets();
    var location = located.locator();
    var plan =
        new EncodedSourcePlan(
            located.sessionId(),
            choice.plan().variant().build(),
            choice.plan().variant().slot(),
            choice.candidate().encodingKey(),
            located.encodedBytes(),
            new EncodedSourcePlan.Location(
                EncodedSourcePlan.LocationKind.valueOf(location.kind().name()),
                location.resourceUrl(),
                location.resourceKey(),
                location.resourceBytes(),
                location.childOffset(),
                location.childLength(),
                location.archiveProof()),
            new EncodedSourcePlan.Cost(
                located.cost().encodedTransferBytes(),
                located.cost().maximumAdditionalPhysicalBytes()),
            new EncodedSourcePlan.Limits(
                budgets.lookup().maximumHeaderBytes(),
                budgets.lookup().maximumChunks(),
                budgets.maximumEncodedFileBytes(),
                budgets.maximumPayloadRangeBytes(),
                budgets.maximumPayloadRequests(),
                budgets.maximumPayloadWireBytes(),
                budgets.maximumPayloadDuration()));
    var issuer = this;
    return new EncodedSourceAuthorization(
        plan,
        new EncodedSourceGateway() {
          public void validate(EncodedSourcePlan actual, CancellationToken token) {
            if (!plan.equals(actual))
              throw new EncodedSourceException(
                  EncodedSourceException.Code.INVALID_PLAN,
                  "Encoded source differs from its issued locator");
            try {
              issuer.validateLocatedPlan(located, token);
            } catch (SelectedFileException invalid) {
              throw sourceFailure(invalid);
            }
          }

          public ContentRepresentation acquire(
              EncodedSourcePlan actual, Observer observer, CancellationToken token) {
            validate(actual, token);
            try {
              return issuer.acquireEncoded(located, observer::verifiedBeforePromotion, token);
            } catch (EncodedAcquisitionFailure failure) {
              throw new AcquisitionFailure(
                  AcquisitionFailure.Publication.valueOf(failure.publication().name()),
                  sourceFailure(failure.failure()));
            } catch (SelectedFileException failure) {
              throw sourceFailure(failure);
            }
          }

          public ContentRepresentation inspectAvailable(
              EncodedSourcePlan actual, ContentRepresentation value, CancellationToken token) {
            validate(actual, token);
            var association =
                new SelectedFileAssociation(
                    new SelectedFileDescriptor(
                        choice.plan().variant(),
                        choice.candidate().encodingKey(),
                        ContentRepresentation.VERIFIER_SEMANTICS),
                    choice.plan().configuration(),
                    choice.plan().root(),
                    choice.plan().proof(),
                    value);
            try {
              return issuer.inspectRetained(association, budgets, token);
            } catch (SelectedFileException failure) {
              throw sourceFailure(failure);
            }
          }
        });
  }

  record EncodedSourceAuthorization(EncodedSourcePlan plan, EncodedSourceGateway gateway) {
    public EncodedSourceAuthorization {
      java.util.Objects.requireNonNull(plan);
      java.util.Objects.requireNonNull(gateway);
    }
  }

  private static RuntimeException sourceFailure(SelectedFileException failure) {
    if (failure.code() == SelectedFileException.Code.CANCELLED) {
      var canceled = new java.util.concurrent.CancellationException(failure.getMessage());
      canceled.initCause(failure);
      return canceled;
    }
    EncodedSourceException.Code code;
    try {
      code = EncodedSourceException.Code.valueOf(failure.code().name());
    } catch (IllegalArgumentException other) {
      code = EncodedSourceException.Code.INTEGRITY_FAILURE;
    }
    return new EncodedSourceException(code, failure.getMessage(), failure);
  }
}
