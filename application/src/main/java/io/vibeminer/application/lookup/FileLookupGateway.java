package io.vibeminer.application.lookup;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.content.MetadataPublicationObserver;
import io.vibeminer.domain.*;
import java.util.Optional;
import java.util.Set;

/** Blocking source/protocol port. Resources remain adapter/caller-owned. */
public interface FileLookupGateway {
  ResolvedSourceIdentity configurationIdentity(BuildSnapshot build);

  /** One operation-scoped metadata ledger covers configuration plus selected EN planning. */
  RootPlan prepareRoot(
      BuildSnapshot build,
      Optional<VerifiedSourceAlias> retainedConfiguration,
      LookupBudgets budgets,
      CancellationToken cancellation);

  /**
   * No HTTP: compare complete selected tuple with bounded canonical session and exact config proof.
   * Evicted/unknown sessions and changed tuples fail INVALID_PLAN before any root acquisition.
   */
  void validateSelection(RootSelectionPlan selection, CancellationToken cancellation);

  ResolvedSourceIdentity rootIdentity(RootSelectionPlan selection);

  /**
   * Verify complete BLTE/CKey, publish one encoded object, structurally validate before receipt.
   */
  VerifiedSourceAlias acquireRoot(RootSelectionPlan selection, CancellationToken cancellation);

  /**
   * Zero HTTP. Revalidate configuration/root identity, full protocol and supported structural EOF.
   */
  RootLookupResult lookupRetained(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      Set<Long> ids,
      LookupBudgets budgets,
      CancellationToken cancellation);

  default VerifiedSourceAlias acquireRoot(
      RootSelectionPlan selection, MetadataPublicationObserver observer, CancellationToken token) {
    throw new FileLookupException(
        FileLookupException.Code.UNSUPPORTED, "Guarded root publication is unsupported");
  }

  default RootLookupResult lookupRetained(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      Set<Long> ids,
      LookupBudgets budgets,
      MetadataPublicationObserver observer,
      CancellationToken token) {
    throw new FileLookupException(
        FileLookupException.Code.UNSUPPORTED, "Attested root replay is unsupported");
  }
}
