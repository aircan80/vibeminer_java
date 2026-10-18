package io.vibeminer.application.itemnames;

import io.vibeminer.domain.item.*;
import java.util.Objects;

public record ItemNamesLookup(PreparedItemNames pair, JoinedItemLookup result) {
  public ItemNamesLookup {
    Objects.requireNonNull(pair);
    Objects.requireNonNull(result);
  }
}
