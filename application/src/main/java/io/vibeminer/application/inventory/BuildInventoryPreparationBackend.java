package io.vibeminer.application.inventory;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import java.util.function.Consumer;

/**
 * Blocking local complete-inventory preparation and real target issuance on the caller's worker.
 */
@FunctionalInterface
public interface BuildInventoryPreparationBackend {
  /**
   * Re-read the exact durable admission and verify its local source proofs. Complete bounded
   * inventory preparation, then issue a target from that actual prepared generation and the same
   * reference/locale/metadata scope. Return the original admission value with the issued target. No
   * HTTP, executor submission/join, invented target, or partial-inventory completion is allowed.
   * Scope-revision fields in admission and target describe different responsibilities.
   *
   * <p>Report nonterminal stages only; unmeasured reference counts stay empty. Close all operation
   * resources before returning. A valid issued result wins cancellation after its commit. Failures
   * use BuildInventoryPreparationException or CancellationException without hiding partial state.
   */
  BuildInventoryPreparationResult prepare(
      BuildMetadataAdmission admission,
      Consumer<BuildInventoryPreparationProgress> progress,
      CancellationToken cancellation);
}
