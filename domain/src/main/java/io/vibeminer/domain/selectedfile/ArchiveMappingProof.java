package io.vibeminer.domain.selectedfile;

import java.util.Objects;
import java.util.OptionalLong;

/** Digests identify authenticated index observations, not the whole archive. */
public record ArchiveMappingProof(
    String indexIdentity,
    String footerSha256,
    String tocSha256,
    String pageSha256,
    String verifierRevision,
    OptionalLong advertisedIndexBytes,
    long authenticatedIndexBytes) {
  /**
   * Compatibility for earlier receipts whose geometry was not recorded. Zero here means unknown,
   * never an authenticated empty index. Production locators must record positive authenticated
   * size.
   */
  public ArchiveMappingProof(
      String indexIdentity,
      String footerSha256,
      String tocSha256,
      String pageSha256,
      String verifierRevision) {
    this(
        indexIdentity,
        footerSha256,
        tocSha256,
        pageSha256,
        verifierRevision,
        OptionalLong.empty(),
        0);
  }

  public ArchiveMappingProof {
    indexIdentity = SelectedFileValues.key(indexIdentity);
    footerSha256 = SelectedFileValues.digest(footerSha256);
    tocSha256 = SelectedFileValues.digest(tocSha256);
    pageSha256 = SelectedFileValues.digest(pageSha256);
    verifierRevision = SelectedFileValues.revision(verifierRevision);
    Objects.requireNonNull(advertisedIndexBytes);
    if (authenticatedIndexBytes < 0
        || advertisedIndexBytes.isPresent()
            && (advertisedIndexBytes.getAsLong() < 1 || authenticatedIndexBytes == 0)) {
      throw new IllegalArgumentException(
          "Index sizes must be positive observations or explicitly unknown");
    }
  }

  /** Whether positive authenticated geometry was actually recorded, rather than legacy unknown. */
  public boolean hasAuthenticatedIndexGeometry() {
    return authenticatedIndexBytes > 0;
  }
}
