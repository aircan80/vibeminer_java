package io.vibeminer.application.retention;

import io.vibeminer.domain.retention.RetentionScope;
import java.util.*;

/** One transaction's definition/pin snapshot; not a claim about an atomic filesystem state. */
public record RetentionView(
    RetentionScope selected, boolean pinned, List<RetentionScope> otherPinned) {
  public RetentionView {
    Objects.requireNonNull(selected);
    otherPinned = List.copyOf(otherPinned);
  }
}
