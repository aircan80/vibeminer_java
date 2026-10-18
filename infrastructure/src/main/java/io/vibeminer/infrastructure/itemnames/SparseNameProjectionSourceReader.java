package io.vibeminer.infrastructure.itemnames;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.itemnames.ItemNamesRequest;
import io.vibeminer.domain.item.*;
import java.util.List;

/** Internal Sparse-only source boundary. The paired backend separately verifies the Item source. */
public interface SparseNameProjectionSourceReader {
  OpenedNames open(ItemNamesRequest request, CancellationToken token);

  /**
   * Requires an authenticated trusted persisted handle from the backend, not an arbitrary DTO.
   * Replays physical identity and historical admitted proof without refreshing its timestamps.
   */
  VerificationLease verify(PreparedSparseNames trustedNames, CancellationToken token);

  interface VerificationLease extends AutoCloseable {
    @Override
    void close();
  }

  record CopyDeclaration(long targetId, long sourceId) {
    public CopyDeclaration {
      if (targetId < 1 || targetId > 0xffffffffL || sourceId < 1 || sourceId > 0xffffffffL)
        throw new IllegalArgumentException("Positive unsigned copy IDs required");
    }
  }

  @FunctionalInterface
  interface RowSink {
    void accept(DisplayCandidate provisionalName);
  }

  interface OpenedNames extends VerificationLease {
    SparseNameArtifactDefinition definition();

    List<CopyDeclaration> copies();

    /**
     * Single use; every callback is provisional until this returns with final consumed evidence.
     */
    PreparedSparseNames project(RowSink sink, CancellationToken token);
  }
}
