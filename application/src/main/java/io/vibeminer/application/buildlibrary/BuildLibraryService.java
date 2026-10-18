package io.vibeminer.application.buildlibrary;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Borrowed-resource facade for one guided library; grouping remains outside JavaFX. */
public interface BuildLibraryService extends AutoCloseable {
  RefreshOperation refreshProduct(String productId);

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
