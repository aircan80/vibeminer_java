package io.vibeminer.application.itemnames;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.util.Optional;

/**
 * Blocking retained-only boundary. Prepare independently replays Item readiness and Sparse original
 * association/whole physical source and admitted windows, stages provisional names and atomically
 * publishes shared names without copying Item rows. Queries reverify BOTH source chains under
 * leases; remembered application authorization is never proof. No discovery/HTTP/implicit
 * prepare/repair. Extraction limits exclude the separately disclosed bounded retained-root replay
 * stages. Current root defaults permit512MiB decoded work each; no small combined-work ceiling is
 * implied. Leases/staging retire before return. Cancellation discards unpublished staging; atomic
 * commit may already have won. Historical admitted proof reuse requires identical full physical
 * SHA/length/EKey, exact interpretation revisions and trusted structural/proof manifest. It mints
 * no decoded proof or historical timestamp. No complete decoded CKey or copied name is inferred.
 */
public interface ItemNamesBackend {
  PreparedItemNames prepare(ItemNamesRequest request, CancellationToken token);

  ItemNamesPage page(
      PreparedItemNames pair, Optional<ItemNamesCursor> cursor, int size, CancellationToken token);

  ItemNamesLookup lookup(PreparedItemNames pair, long recordId, CancellationToken token);
}
