package io.vibeminer.application.encodedsource;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.selectedfile.ContentRepresentation;
import java.util.Objects;

/**
 * Trusted consumer-specific issuer/replay and shared encoded mechanics; never a raw UI argument.
 */
public interface EncodedSourceGateway {
  void validate(EncodedSourcePlan plan, CancellationToken cancellation);

  ContentRepresentation acquire(
      EncodedSourcePlan plan, Observer observer, CancellationToken cancellation);

  ContentRepresentation inspectAvailable(
      EncodedSourcePlan plan, ContentRepresentation representation, CancellationToken cancellation);

  @FunctionalInterface
  interface Observer {
    void verifiedBeforePromotion(ContentRepresentation prospective);
  }

  /** Physical outcome known only when the actual producer has stopped. */
  final class AcquisitionFailure extends RuntimeException {
    public enum Publication {
      CONCLUSIVELY_UNPUBLISHED,
      UNCERTAIN
    }

    private final Publication publication;
    private final RuntimeException failure;

    public AcquisitionFailure(Publication publication, RuntimeException failure) {
      super(failure.getMessage(), failure);
      this.publication = Objects.requireNonNull(publication);
      this.failure = Objects.requireNonNull(failure);
    }

    public Publication publication() {
      return publication;
    }

    public RuntimeException failure() {
      return failure;
    }
  }
}
