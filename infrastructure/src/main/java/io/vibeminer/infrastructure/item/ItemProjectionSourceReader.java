package io.vibeminer.infrastructure.item;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.item.ItemProjectionRequest;
import io.vibeminer.domain.item.*;
import java.util.List;

/** Internal retained-only source boundary. Leases span projection/publication or query reads. */
public interface ItemProjectionSourceReader {
  OpenedProjection open(ItemProjectionRequest request, CancellationToken token);

  /** Replays local proof without rewriting the handle's historical preparation timestamp. */
  VerificationLease verify(PreparedItemProjection handle, CancellationToken token);

  interface VerificationLease extends AutoCloseable {
    @Override
    void close();
  }

  record CopyDeclaration(long targetId, long sourceId) {
    public CopyDeclaration {
      if (targetId < 1 || targetId > 0xffffffffL || sourceId < 1 || sourceId > 0xffffffffL)
        throw new IllegalArgumentException("Unsigned copy IDs required");
    }
  }

  @FunctionalInterface
  interface RowSink {
    void accept(ItemPhysicalRow provisionalRow);
  }

  interface OpenedProjection extends VerificationLease {
    ItemArtifactDefinition definition();

    ItemProjectionEvidence evidence();

    List<CopyDeclaration> copies();

    /** All rows remain provisional until this method completes successfully. */
    PreparedItemProjection.Coverage project(RowSink sink, CancellationToken token);
  }
}
