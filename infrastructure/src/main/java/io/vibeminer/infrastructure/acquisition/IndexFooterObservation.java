package io.vibeminer.infrastructure.acquisition;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Transport-only footer bytes. A parser must still authenticate filename/footer/geometry. */
public record IndexFooterObservation(
    CdnIndexFooterRequest request,
    URI actualSourceUri,
    Instant receivedAt,
    long observedWholeBytes,
    byte[] bytes,
    String footerSha256,
    Optional<String> etag,
    Optional<String> lastModified) {
  public IndexFooterObservation {
    Objects.requireNonNull(request);
    Objects.requireNonNull(actualSourceUri);
    Objects.requireNonNull(receivedAt);
    Objects.requireNonNull(bytes);
    Objects.requireNonNull(footerSha256);
    Objects.requireNonNull(etag);
    Objects.requireNonNull(lastModified);
    if (!actualSourceUri.equals(request.resource().uri())
        || observedWholeBytes < 28
        || observedWholeBytes > request.maximumWholeBytes()
        || bytes.length != 28
        || !footerSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Footer observation differs from bounded request");
    bytes = bytes.clone();
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  public long start() {
    return observedWholeBytes - 28;
  }

  public String method() {
    return "GET";
  }
}
