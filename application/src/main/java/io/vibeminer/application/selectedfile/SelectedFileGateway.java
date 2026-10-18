package io.vibeminer.application.selectedfile;

import io.vibeminer.application.CancellationToken;
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
}
