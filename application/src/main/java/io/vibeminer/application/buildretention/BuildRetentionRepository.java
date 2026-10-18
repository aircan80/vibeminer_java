package io.vibeminer.application.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;

import io.vibeminer.application.CancellationToken;
import java.time.Instant;
import java.util.*;

/** Blocking transactional ledger only. It owns no downloader, pump, store or executor. */
public interface BuildRetentionRepository {
  Optional<Intent> readIntent(Target target, CancellationToken cancellation);

  /** Bounded current-target queue state, not a fulfillment/readiness or success assertion. */
  default boolean hasPending(Target target, CancellationToken cancellation) {
    throw new BuildRetentionException(
        BuildRetentionException.Code.NOT_READY,
        "Pending-job projection is not supported by this repository");
  }

  /**
   * expectedGeneration0 creates ONLY a verified sealed Download all intent; committed result wins
   * late cancel.
   */
  Intent setMode(
      Target target,
      long expectedGeneration,
      Mode mode,
      Policy policy,
      Instant authorizedAt,
      CancellationToken cancellation);

  Intent fillWindow(Target target, long generation, int maximum, CancellationToken cancellation);

  /** At most2 globally running jobs; reserves known costs durably before returning actual lease. */
  Optional<Job> claimNext(Target target, long generation, CancellationToken cancellation);

  /**
   * Called only at actual worker finally using an uncancelled cleanup token, never from facade
   * cancellation. Exact nonce+epoch fences writes.
   */
  Job finish(Lease lease, Finish finish, CancellationToken cancellation);

  Job retryFailed(Target target, long generation, String memberId, CancellationToken cancellation);

  List<Job> pageJobs(Target target, long afterOrdinal, int maximum, CancellationToken cancellation);
}
