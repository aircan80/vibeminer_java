package io.vibeminer.application.item;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.item.*;
import java.util.Optional;

/**
 * Blocking retained-only boundary. Prepare must replay exact durable association, verify whole
 * encoded physical bytes and all consumed windows, project provisionally, and atomically publish
 * one shared artifact. Cancellation/failure discards unpublished staging; an already completed
 * atomic publication may win a cancellation race. Queries recheck local source/proof readiness and
 * never prepare, repair or use transport. BUSY may reject a competing artifact preparation. Source
 * leases and staging must retire before returning, including exceptional returns.
 */
public interface ItemProjectionBackend {
  PreparedItemProjection prepare(ItemProjectionRequest request, CancellationToken token);

  ItemPage page(
      PreparedItemProjection handle,
      Optional<ItemPageCursor> cursor,
      int size,
      CancellationToken token);

  ItemLookupResult lookup(PreparedItemProjection handle, long recordId, CancellationToken token);
}
