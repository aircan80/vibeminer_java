package io.vibeminer.domain.item;

import java.util.*;

/** Item copies/nonmatches remain scoped outcomes and cannot manufacture named physical rows. */
public record JoinedItemLookup(ItemLookupResult item, Optional<ItemNameMatch> name) {
  public JoinedItemLookup {
    Objects.requireNonNull(item);
    Objects.requireNonNull(name);
    if (item.requestedId() == 0) throw new IllegalArgumentException("Positive Item ID required");
    if ((item.outcome() == ItemLookupResult.Outcome.PHYSICAL_MATCH) != name.isPresent()
        || name.isPresent() && name.get().requestedId() != item.requestedId())
      throw new IllegalArgumentException(
          "Joined name is available only for the matching physical Item anchor");
  }
}
