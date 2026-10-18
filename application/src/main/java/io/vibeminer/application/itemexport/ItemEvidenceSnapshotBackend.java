package io.vibeminer.application.itemexport;

import io.vibeminer.application.CancellationToken;

/** Retained-only snapshot: applicable source leases span the same final SQL context/row read. */
@FunctionalInterface
public interface ItemEvidenceSnapshotBackend {
  ItemEvidenceSnapshot capture(ItemEvidenceSelection selection, CancellationToken cancellation);
}
