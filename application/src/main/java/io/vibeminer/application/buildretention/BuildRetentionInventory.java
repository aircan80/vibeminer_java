package io.vibeminer.application.buildretention;

import static io.vibeminer.application.buildretention.BuildRetention.*;

import io.vibeminer.application.CancellationToken;
import java.util.List;

/** Trusted complete-inventory authority, not a caller boolean. No production implementation yet. */
public interface BuildRetentionInventory {
  /**
   * Replays exact admitted all-declared closure; holds immutable generation/source leases until
   * close.
   */
  Opened openSealed(Target target, CancellationToken cancellation);

  interface Opened extends AutoCloseable {
    Seal seal();

    /**
     * Exact contiguous ordinals; returns precisely min(maximum, remaining) members, never
     * partial-limit.
     */
    List<Member> members(long firstOrdinal, int maximum, CancellationToken cancellation);

    @Override
    void close();
  }
}
