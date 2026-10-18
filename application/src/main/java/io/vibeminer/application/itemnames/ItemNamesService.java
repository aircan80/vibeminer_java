package io.vibeminer.application.itemnames;

import io.vibeminer.domain.item.*;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface ItemNamesService extends AutoCloseable {
  CompletionStage<PreparedItemNames> prepare(ItemNamesRequest request);

  CompletionStage<ItemNamesPage> page(
      PreparedItemNames pair, Optional<ItemNamesCursor> cursor, int size);

  CompletionStage<ItemNamesLookup> lookup(PreparedItemNames pair, long recordId);

  @Override
  void close();
}
