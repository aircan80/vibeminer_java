package io.vibeminer.application.selectedfile;

import java.util.Objects;

/** Canonical locator metadata and exact known-size encoded-payload consent. */
public record LocatedFilePlan(
    String sessionId,
    CandidateChoice choice,
    FileLocator locator,
    long encodedBytes,
    AcquisitionCost cost) {
  public LocatedFilePlan {
    if (sessionId == null || sessionId.isBlank() || sessionId.length() > 256)
      throw new IllegalArgumentException("Bounded locator session required");
    Objects.requireNonNull(choice);
    Objects.requireNonNull(locator);
    Objects.requireNonNull(cost);
    if (encodedBytes < 1 || encodedBytes != locator.childLength())
      throw new IllegalArgumentException("Known pinned child length required");
  }
}
