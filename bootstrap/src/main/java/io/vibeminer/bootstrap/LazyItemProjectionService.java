package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.item.*;
import io.vibeminer.domain.item.*;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Retained-only Item facade borrowing the existing content initializer and its single owner. */
final class LazyItemProjectionService implements ItemProjectionService {
  private final LazyBootstrapService owner;

  LazyItemProjectionService(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public CompletionStage<PreparedItemProjection> prepare(ItemProjectionRequest request) {
    return owner.dispatchResources(
        runtime -> runtime.itemProjection().prepare(request), LazyItemProjectionService::classify);
  }

  @Override
  public CompletionStage<ItemPage> page(
      PreparedItemProjection handle, Optional<ItemPageCursor> cursor, int size) {
    return owner.dispatchResources(
        runtime -> runtime.itemProjection().page(handle, cursor, size),
        LazyItemProjectionService::classify);
  }

  @Override
  public CompletionStage<ItemLookupResult> lookup(PreparedItemProjection handle, long id) {
    return owner.dispatchResources(
        runtime -> runtime.itemProjection().lookup(handle, id),
        LazyItemProjectionService::classify);
  }

  @Override
  public void close() {
    owner.close();
  }

  private static Throwable classify(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof BootstrapException bootstrap) {
      var code =
          switch (bootstrap.reason()) {
            case CLOSED -> ItemProjectionException.Code.CLOSED;
            case CAPACITY -> ItemProjectionException.Code.BUSY;
            default -> ItemProjectionException.Code.STORAGE_UNAVAILABLE;
          };
      return new ItemProjectionException(code, bootstrap.getMessage(), bootstrap);
    }
    return failure;
  }
}
