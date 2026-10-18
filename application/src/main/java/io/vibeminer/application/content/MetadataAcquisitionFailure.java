package io.vibeminer.application.content;

import java.util.Objects;

/** A verifier's conservative physical-publication boundary; ordinary errors imply uncertainty. */
public final class MetadataAcquisitionFailure extends RuntimeException {
  public enum Publication {
    CONCLUSIVELY_UNPUBLISHED,
    UNCERTAIN
  }

  private final Publication publication;

  public MetadataAcquisitionFailure(Publication publication, RuntimeException failure) {
    super(Objects.requireNonNull(failure).getMessage(), failure);
    this.publication = Objects.requireNonNull(publication);
  }

  public Publication publication() {
    return publication;
  }

  public RuntimeException failure() {
    return (RuntimeException) getCause();
  }
}
