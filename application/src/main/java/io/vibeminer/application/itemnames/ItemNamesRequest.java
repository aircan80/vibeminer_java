package io.vibeminer.application.itemnames;

import io.vibeminer.domain.item.PreparedItemProjection;
import io.vibeminer.domain.selectedfile.SelectedFileDescriptor;
import java.util.Objects;

/** Explicit retained source pair; construction validates context, never source readiness. */
public record ItemNamesRequest(
    PreparedItemProjection item,
    SelectedFileDescriptor sparseSource,
    String locale,
    SparseNameLimits limits) {
  public ItemNamesRequest {
    Objects.requireNonNull(item);
    Objects.requireNonNull(sparseSource);
    Objects.requireNonNull(limits);
  }
}
