package io.vibeminer.application.buildlibrary;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Borrowed-resource facade for one guided library; grouping remains outside JavaFX. */
public interface BuildLibraryService extends AutoCloseable {
  RefreshOperation refreshProduct(String productId);

  /** Explicit offline entry preflight; ordinary page/select never trigger migration. */
  default LocalPreparationOperation prepareLocal(
      String productId, Optional<LegacyBuildObservationImporter.Cursor> cursor) {
    var failed =
        java.util.concurrent.CompletableFuture.<BuildLibraryLocalPreparationResult>failedFuture(
                new UnsupportedOperationException("Legacy local preparation is not configured"))
            .minimalCompletionStage();
    return new LocalPreparationOperation() {
      public CompletionStage<BuildLibraryLocalPreparationResult> completion() {
        return failed;
      }

      public void requestCancellation() {}
    };
  }

  interface LocalPreparationOperation {
    CompletionStage<BuildLibraryLocalPreparationResult> completion();

    void requestCancellation();
  }

  CompletionStage<BuildLibraryPage> page(
      BuildLibraryQuery query, Optional<BuildLibraryCursor> cursor, int pageSize);

  CompletionStage<BuildLibraryPage.Entry> select(String groupId);

  interface RefreshOperation {
    /** Read-only observation; commit-winning partial results survive late cancellation. */
    CompletionStage<BuildLibraryRefreshResult> completion();

    void requestCancellation();
  }

  @Override
  void close();
}
