package io.vibeminer.infrastructure.acquisition;

import java.util.Objects;

/** Requests size metadata only. The caller supplies an explicit whole-object policy ceiling. */
public record CdnSizeRequest(CdnResource resource, long maximumWholeBytes) {
  public CdnSizeRequest {
    Objects.requireNonNull(resource);
    if (resource.kind() != CdnResource.Kind.DATA || maximumWholeBytes < 1)
      throw new IllegalArgumentException(
          "Size observation requires a DATA resource and positive size limit");
  }
}
