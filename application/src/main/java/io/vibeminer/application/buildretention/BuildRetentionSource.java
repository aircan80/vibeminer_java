package io.vibeminer.application.buildretention;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.scheduler.PrioritySourceScheduler;

/** Acquires one authority-checked inventory member through the shared encoded source owner. */
public interface BuildRetentionSource {
  /**
   * Returns an immutable fulfillment commitment. Both success and failure retire the actual source
   * enrollment before returning; consumer cancellation alone must never retire a ledger job.
   */
  String retain(
      BuildRetention.Target target,
      BuildRetention.Member member,
      PrioritySourceScheduler.Demand demand,
      CancellationToken cancellation);
}
