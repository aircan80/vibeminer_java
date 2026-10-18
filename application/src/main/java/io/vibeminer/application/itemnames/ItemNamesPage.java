package io.vibeminer.application.itemnames;

import io.vibeminer.domain.item.*;
import java.util.*;

public record ItemNamesPage(
    PreparedItemNames pair, List<NamedItemRow> rows, Optional<ItemNamesCursor> nextCursor) {
  public ItemNamesPage {
    Objects.requireNonNull(pair);
    rows = List.copyOf(rows);
    Objects.requireNonNull(nextCursor);
  }
}
