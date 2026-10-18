package io.vibeminer.application.itemnames;

import io.vibeminer.domain.item.*;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface ItemNamesService extends AutoCloseable {
  CompletionStage<PreparedItemNames> prepare(ItemNamesRequest request);

  CompletionStage<ItemNamesPage> page(
      PreparedItemNames pair, Optional<ItemNamesCursor> cursor, int size);

  CompletionStage<ItemNamesLookup> lookup(PreparedItemNames pair, long recordId);

  /** Explicit literal search; no preparation, acquisition or repair follows from this operation. */
  default CompletionStage<ItemNamesSearchPage> search(
      PreparedItemNames pair,
      ItemNameLiteralQuery query,
      Optional<ItemNamesSearchCursor> cursor,
      int size) {
    return java.util.concurrent.CompletableFuture.failedFuture(
        new ItemNamesException(
            ItemNamesException.Code.UNSUPPORTED,
            "This service does not provide literal name search"));
  }

  @Override
  void close();
}
