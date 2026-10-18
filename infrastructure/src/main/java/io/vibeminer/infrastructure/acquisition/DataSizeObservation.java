package io.vibeminer.infrastructure.acquisition;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** HEAD metadata only, never a whole-object checksum or a ready storage reference. */
public record DataSizeObservation(
    CdnSizeRequest request,
    URI actualSourceUri,
    Instant receivedAt,
    long observedWholeBytes,
    Optional<String> etag,
    Optional<String> lastModified) {
  public DataSizeObservation {
    Objects.requireNonNull(request);
    Objects.requireNonNull(actualSourceUri);
    Objects.requireNonNull(receivedAt);
    Objects.requireNonNull(etag);
    Objects.requireNonNull(lastModified);
    if (!actualSourceUri.equals(request.resource().uri())
        || observedWholeBytes < 1
        || observedWholeBytes > request.maximumWholeBytes())
      throw new IllegalArgumentException("Size observation differs from bounded request");
  }

  public String method() {
    return "HEAD";
  }
}
