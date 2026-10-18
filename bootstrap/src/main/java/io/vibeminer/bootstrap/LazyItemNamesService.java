package io.vibeminer.bootstrap;

import io.vibeminer.application.bootstrap.BootstrapException;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.domain.item.*;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Retained physical Item names facade borrowing the single lazy content owner. */
final class LazyItemNamesService implements ItemNamesService {
  private final LazyBootstrapService owner;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();

  LazyItemNamesService(LazyBootstrapService owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  @Override
  public CompletionStage<PreparedItemNames> prepare(ItemNamesRequest request) {
    return dispatch(runtime -> runtime.itemNames().prepare(request));
  }

  @Override
  public CompletionStage<ItemNamesPage> page(
      PreparedItemNames pair, Optional<ItemNamesCursor> cursor, int size) {
    return dispatch(runtime -> runtime.itemNames().page(pair, cursor, size));
  }

  @Override
  public CompletionStage<ItemNamesLookup> lookup(PreparedItemNames pair, long recordId) {
    return dispatch(runtime -> runtime.itemNames().lookup(pair, recordId));
  }

  @Override
  public CompletionStage<ItemNamesSearchPage> search(
      PreparedItemNames pair,
      ItemNameLiteralQuery query,
      Optional<ItemNamesSearchCursor> cursor,
      int size) {
    return dispatch(runtime -> runtime.itemNames().search(pair, query, cursor, size));
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) requests.forEach(request -> request.cancel(true));
  }

  private <T> CompletionStage<T> dispatch(
      Function<LazyBootstrapService.Resources, CompletionStage<T>> action) {
    if (closed.get())
      return CompletableFuture.failedFuture(
          new ItemNamesException(ItemNamesException.Code.CLOSED, "Item names facade is closed"));
    var request =
        owner
            .dispatchResources(
                runtime -> {
                  if (closed.get())
                    throw new ItemNamesException(
                        ItemNamesException.Code.CLOSED, "Item names facade is closed");
                  return action.apply(runtime);
                },
                LazyItemNamesService::classify)
            .toCompletableFuture();
    requests.add(request);
    request.whenComplete((value, failure) -> requests.remove(request));
    if (closed.get()) request.cancel(true);
    return request;
  }

  private static Throwable classify(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (failure instanceof BootstrapException bootstrap) {
      var code =
          switch (bootstrap.reason()) {
            case CLOSED -> ItemNamesException.Code.CLOSED;
            case CAPACITY -> ItemNamesException.Code.BUSY;
            default -> ItemNamesException.Code.STORAGE_UNAVAILABLE;
          };
      return new ItemNamesException(code, bootstrap.getMessage(), bootstrap);
    }
    return failure;
  }
}
