package io.vibeminer.domain.item;

import java.util.Objects;

/** Left-join value references the original Item physical row, including an unavailable name. */
public record NamedItemRow(ItemPhysicalRow item, ItemNameMatch name) {
  public NamedItemRow {
    Objects.requireNonNull(item);
    Objects.requireNonNull(name);
    if (item.recordId() != name.requestedId())
      throw new IllegalArgumentException("Names join must preserve the Item ID anchor");
  }
}
