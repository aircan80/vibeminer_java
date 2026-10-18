package io.vibeminer.application.item;

import io.vibeminer.domain.item.*;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface ItemProjectionService extends AutoCloseable {
  CompletionStage<PreparedItemProjection> prepare(ItemProjectionRequest request);

  CompletionStage<ItemPage> page(
      PreparedItemProjection handle, Optional<ItemPageCursor> cursor, int size);

  CompletionStage<ItemLookupResult> lookup(PreparedItemProjection handle, long recordId);

  @Override
  void close();
}
