package io.vibeminer.domain.content;

import java.util.Objects;

/**
 * Separate supported-parser EOF attestation; no general readability follows from a decoded hash.
 */
public record MetadataStructureAttestation(Kind kind, String parserRevision) {
  public enum Kind {
    ROOT,
    INSTALL,
    ENCODING,
    DOWNLOAD,
    DOWNLOAD_SIZE
  }

  public MetadataStructureAttestation {
    Objects.requireNonNull(kind);
    parserRevision = ContentValues.revision(parserRevision);
  }
}
