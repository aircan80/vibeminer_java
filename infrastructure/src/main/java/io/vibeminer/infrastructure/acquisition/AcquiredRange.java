package io.vibeminer.infrastructure.acquisition;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Transport-only evidence, even when the requested slice happens to cover the entire object. The
 * local SHA-256 covers this range only. No BLTE/EKey/CKey or whole-object verification is implied.
 */
public record AcquiredRange(
    CdnRangeRequest request,
    URI actualSourceUri,
    Instant receivedAt,
    byte[] bytes,
    String rangeSha256) {
  public AcquiredRange {
    Objects.requireNonNull(request);
    Objects.requireNonNull(actualSourceUri);
    Objects.requireNonNull(receivedAt);
    Objects.requireNonNull(bytes);
    Objects.requireNonNull(rangeSha256);
    if (!actualSourceUri.equals(request.uri())
        || bytes.length != request.count()
        || !rangeSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Range evidence does not match pinned request");
    bytes = bytes.clone();
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }
}
