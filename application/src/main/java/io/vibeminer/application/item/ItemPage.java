package io.vibeminer.application.item;

import io.vibeminer.domain.item.*;
import java.util.*;

public record ItemPage(
    PreparedItemProjection handle,
    List<ItemPhysicalRow> rows,
    Optional<ItemPageCursor> nextCursor) {
  public ItemPage {
    Objects.requireNonNull(handle);
    rows = List.copyOf(rows);
    Objects.requireNonNull(nextCursor);
  }
}
