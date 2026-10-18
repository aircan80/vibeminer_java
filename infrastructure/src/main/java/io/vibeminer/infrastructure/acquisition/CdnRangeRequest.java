package io.vibeminer.infrastructure.acquisition;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * A single explicit bounded slice of an official resource whose whole length is independently
 * pinned.
 */
public record CdnRangeRequest(
    CdnRequest.Source source,
    String encodedKey,
    long wholeEncodedLength,
    long start,
    int count,
    int maximumBytes,
    CdnResource.Kind resourceKind) {
  /** Existing data requests remain source compatible and retain identical validation. */
  public CdnRangeRequest(
      CdnRequest.Source source,
      String encodedKey,
      long wholeEncodedLength,
      long start,
      int count,
      int maximumBytes) {
    this(source, encodedKey, wholeEncodedLength, start, count, maximumBytes, CdnResource.Kind.DATA);
  }

  public CdnRangeRequest(
      CdnResource resource, long wholeEncodedLength, long start, int count, int maximumBytes) {
    this(
        resource.source(),
        resource.key(),
        wholeEncodedLength,
        start,
        count,
        maximumBytes,
        resource.kind());
  }

  public CdnRangeRequest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(resourceKind, "resourceKind");
    Objects.requireNonNull(encodedKey, "encodedKey");
    if (!encodedKey.matches("[0-9a-fA-F]{32}"))
      throw new IllegalArgumentException("Expected 128-bit encoded key");
    encodedKey = encodedKey.toLowerCase(Locale.ROOT);
    if (wholeEncodedLength < 1
        || start < 0
        || count < 1
        || maximumBytes < count
        || maximumBytes > 64 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid pinned range or transfer budget");
    try {
      if (Math.addExact(start, (long) count - 1) >= wholeEncodedLength)
        throw new IllegalArgumentException("Range exceeds pinned whole object length");
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Range arithmetic overflow", overflow);
    }
  }

  public long endInclusive() {
    return start + count - 1L;
  }

  public String rangeHeader() {
    return "bytes=" + start + "-" + endInclusive();
  }

  public URI uri() {
    return new CdnResource(source, encodedKey, resourceKind).uri();
  }
}
