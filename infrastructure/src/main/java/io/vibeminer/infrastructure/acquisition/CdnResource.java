package io.vibeminer.infrastructure.acquisition;

import java.net.URI;
import java.util.Objects;

/**
 * An approved official route. Extensions are selected by a closed kind, never caller-supplied text.
 */
public record CdnResource(CdnRequest.Source source, String key, Kind kind) {
  public enum Kind {
    DATA,
    INDEX
  }

  public CdnResource {
    Objects.requireNonNull(source);
    Objects.requireNonNull(kind);
    key = CdnRequest.configuration(source, key).key();
  }

  public URI uri() {
    URI data = CdnRequest.loose(source, key, 1, 1).uri();
    return kind == Kind.DATA ? data : URI.create(data.toString() + ".index");
  }
}
