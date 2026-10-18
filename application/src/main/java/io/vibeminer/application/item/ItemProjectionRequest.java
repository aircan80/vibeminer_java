package io.vibeminer.application.item;

import io.vibeminer.domain.selectedfile.SelectedFileDescriptor;
import java.util.Objects;

public record ItemProjectionRequest(
    SelectedFileDescriptor source, String locale, ItemProjectionLimits limits) {
  public ItemProjectionRequest {
    Objects.requireNonNull(source);
    Objects.requireNonNull(limits);
    if (locale == null || !locale.matches("[a-z]{2}[A-Z]{2}"))
      throw new IllegalArgumentException("Explicit locale required");
  }
}
