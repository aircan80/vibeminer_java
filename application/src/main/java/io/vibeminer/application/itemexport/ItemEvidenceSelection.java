package io.vibeminer.application.itemexport;

import io.vibeminer.domain.item.*;
import java.util.Objects;
import java.util.Optional;

/** Pinned consumer identity; a displayed row or caller-built DTO is not source authority. */
public record ItemEvidenceSelection(
    PreparedItemProjection item, long recordId, Optional<PreparedItemNames> names) {
  public ItemEvidenceSelection {
    Objects.requireNonNull(item);
    Objects.requireNonNull(names);
    if (recordId < 1
        || recordId > 0xffffffffL
        || names.isPresent() && !names.get().item().equals(item))
      throw new IllegalArgumentException("Positive Item ID and exact optional companion required");
  }
}
