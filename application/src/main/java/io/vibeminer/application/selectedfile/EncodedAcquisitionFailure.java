package io.vibeminer.application.selectedfile;

import java.util.Objects;

/** Trusted gateway outcome after the acquisition worker has stopped; never consumer consent. */
public final class EncodedAcquisitionFailure extends RuntimeException {
  public enum Publication {
    CONCLUSIVELY_UNPUBLISHED,
    UNCERTAIN
  }

  private final Publication publication;
  private final SelectedFileException failure;

  public EncodedAcquisitionFailure(Publication publication, SelectedFileException failure) {
    super(failure.getMessage(), failure);
    this.publication = Objects.requireNonNull(publication);
    this.failure = Objects.requireNonNull(failure);
  }

  public Publication publication() {
    return publication;
  }

  public SelectedFileException failure() {
    return failure;
  }
}
