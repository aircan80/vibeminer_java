package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.bootstrap.InstallInspection;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.content.MetadataStructureAttestation;

/**
 * Blocking local-only verification. No acquisition, publisher admission, association, pin or claim
 * mutation is authorized. Physical/config read leases protect verification; missing/corrupt bytes
 * fail without fetching. Construction of a plan never substitutes for these checks.
 */
public interface MetadataReuseGateway {
  /**
   * Read only the retained exact configuration; unknown root facts remain unknown, without EN IO.
   */
  default MetadataDeclaration readDeclaration(
      BuildSnapshot build,
      VerifiedSourceAlias retainedConfiguration,
      MetadataStructureAttestation.Kind kind,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    throw new ContentClaimException(
        ContentClaimException.Code.UNSUPPORTED_REUSE,
        "Retained metadata declaration reading is unsupported");
  }

  void validateDeclaration(
      MetadataDeclaration declaration, LookupBudgets budgets, CancellationToken cancellation);

  /**
   * Replays actual A, preserving its exact original alias/evidence; no current EN claim follows.
   */
  MetadataContentProof replay(
      MetadataReusePlan plan, LookupBudgets budgets, CancellationToken cancellation);

  /** Dedicated local associated path; ordinary tuple-strict install inspection is unchanged. */
  default InstallInspection inspectAssociatedInstall(
      InstallPlan declaredPlanB,
      MetadataBuildAssociation association,
      LookupBudgets budgets,
      CancellationToken cancellation) {
    throw new ContentClaimException(
        ContentClaimException.Code.UNSUPPORTED_REUSE,
        "Associated install inspection is unsupported");
  }
}
