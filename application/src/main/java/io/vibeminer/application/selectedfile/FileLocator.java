package io.vibeminer.application.selectedfile;

import io.vibeminer.domain.selectedfile.ArchiveMappingProof;
import java.util.*;

/** Pinned scalar child geometry. Construction is not source authentication. */
public record FileLocator(
    Kind kind,
    String resourceUrl,
    String resourceKey,
    long resourceBytes,
    long childOffset,
    long childLength,
    Optional<ArchiveMappingProof> archiveProof) {
  public enum Kind {
    LOOSE,
    ARCHIVE
  }

  public FileLocator {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(resourceUrl);
    Objects.requireNonNull(archiveProof);
    if (resourceKey == null
        || !resourceKey.matches("[0-9a-f]{32}")
        || resourceUrl.isBlank()
        || resourceBytes < 1
        || childOffset < 0
        || childLength < 1
        || childOffset > resourceBytes
        || childLength > resourceBytes - childOffset
        || kind == Kind.LOOSE
            && (childOffset != 0 || childLength != resourceBytes || archiveProof.isPresent())
        || kind == Kind.ARCHIVE && archiveProof.isEmpty())
      throw new IllegalArgumentException("Invalid pinned locator geometry");
  }
}
