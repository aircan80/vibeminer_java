package io.vibeminer.application.itemnames;

import io.vibeminer.domain.item.*;
import java.util.*;

/**
 * Only verified physical matches; a complete empty result is scoped to this exact prepared pair.
 */
public record ItemNamesSearchPage(
    PreparedItemNames pair,
    ItemNameLiteralQuery query,
    String generationFingerprint,
    List<NamedItemRow> rows,
    Optional<ItemNamesSearchCursor> nextCursor) {
  public ItemNamesSearchPage {
    Objects.requireNonNull(pair);
    Objects.requireNonNull(query);
    if (generationFingerprint == null || !generationFingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Opaque validated generation fingerprint required");
    rows = List.copyOf(rows);
    Objects.requireNonNull(nextCursor);
  }
}
