package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.CancellationToken;
import java.time.Instant;
import java.util.*;

/** Explicit local library-entry migration; never invoked implicitly by page/select. */
public interface LegacyBuildObservationImporter {
  Batch importNext(
      String productId,
      Optional<Cursor> cursor,
      Limits limits,
      Instant recordedAt,
      CancellationToken cancellation);

  record Cursor(String productId, String afterSnapshotId) {
    public Cursor {
      new BuildLibraryQuery(productId, BuildLibraryProjection.Filter.ALL);
      if (afterSnapshotId == null || !afterSnapshotId.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact legacy snapshot continuation required");
    }
  }

  record Limits(int maximumPairs, int maximumSnapshotHeaders, long maximumDocumentBytes) {
    public Limits {
      if (maximumPairs < 1
          || maximumPairs > 32
          || maximumSnapshotHeaders < 1
          || maximumSnapshotHeaders > 256
          || maximumDocumentBytes < (2L << 20)
          || maximumDocumentBytes > (64L << 20))
        throw new IllegalArgumentException("Bounded legacy import work required");
    }

    public static Limits defaults() {
      return new Limits(32, 256, 64L << 20);
    }
  }

  /** Counts describe this call only. Continuation advances only past fully handled snapshots. */
  record Batch(
      String productId,
      int scannedSnapshots,
      int importedPairs,
      int alreadyImportedPairs,
      Optional<Cursor> nextCursor,
      boolean complete) {
    public Batch {
      new BuildLibraryQuery(productId, BuildLibraryProjection.Filter.ALL);
      Objects.requireNonNull(nextCursor);
      if (scannedSnapshots < 0
          || importedPairs < 0
          || alreadyImportedPairs < 0
          || (complete && nextCursor.isPresent()))
        throw new IllegalArgumentException("Invalid import progress");
      nextCursor.ifPresent(
          c -> {
            if (!c.productId().equals(productId))
              throw new IllegalArgumentException("Wrong product cursor");
          });
    }
  }
}
