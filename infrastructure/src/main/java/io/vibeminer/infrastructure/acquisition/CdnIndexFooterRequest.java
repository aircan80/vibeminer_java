package io.vibeminer.infrastructure.acquisition;

import java.util.Objects;

/** Observes the final 28 bytes without trusting a configuration's advertised index size. */
public record CdnIndexFooterRequest(CdnResource resource, long maximumWholeBytes) {
  public static final int FOOTER_BYTES = 28;

  public CdnIndexFooterRequest {
    Objects.requireNonNull(resource);
    if (resource.kind() != CdnResource.Kind.INDEX
        || maximumWholeBytes < FOOTER_BYTES
        || maximumWholeBytes > 2L * 1024 * 1024 * 1024)
      throw new IllegalArgumentException(
          "Footer observation requires an INDEX resource and bounded index size");
  }

  public String rangeHeader() {
    return "bytes=-28";
  }
}
