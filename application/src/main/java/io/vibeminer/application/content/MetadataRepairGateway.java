package io.vibeminer.application.content;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.bootstrap.InstallPlan;
import io.vibeminer.application.lookup.RootSelectionPlan;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.ContentClaim;
import java.time.Duration;

/**
 * Blocking, bounded repair planning only. No physical repair, publisher admission or claim
 * mutation. Callers supply authoritative history and must reread it under an actual worker permit
 * before any future execution; a caller-created claim or this port's issued plan cannot bypass that
 * authority.
 *
 * <p>This issuer does not inspect local object readiness. Before prepare, the application must
 * establish that original A is missing, corrupt or uncertain. Under the actual worker permit it
 * must check readiness again: healthy A requires local replay, not a repair GET.
 */
public interface MetadataRepairGateway {
  int MAXIMUM_HEAD_REQUESTS = 1;
  long MAXIMUM_HEAD_BODY_BYTES = 0;
  Duration MAXIMUM_HEAD_DURATION = Duration.ofSeconds(15);

  MetadataRepairPlan prepareInstall(
      InstallPlan ordinaryPlan,
      VerifiedSourceAlias retainedConfiguration,
      ContentClaim original,
      CancellationToken cancellation);

  MetadataRepairPlan prepareRoot(
      RootSelectionPlan ordinarySelection, ContentClaim original, CancellationToken cancellation);

  /** No HTTP. Replay the complete issued tuple and original ordinary configuration plan. */
  void validateInstall(
      InstallPlan ordinaryPlan, MetadataRepairPlan issued, CancellationToken cancellation);

  /** No HTTP. Borrow the same root gateway/session that authenticated the ordinary selection. */
  void validateRoot(
      RootSelectionPlan ordinarySelection,
      MetadataRepairPlan issued,
      CancellationToken cancellation);
}
