package io.vibeminer.domain.selectedfile;

/** Digests identify the authenticated footer/TOC/page observations, not the whole archive. */
public record ArchiveMappingProof(
    String indexIdentity,
    String footerSha256,
    String tocSha256,
    String pageSha256,
    String verifierRevision) {
  public ArchiveMappingProof {
    indexIdentity = SelectedFileValues.key(indexIdentity);
    footerSha256 = SelectedFileValues.digest(footerSha256);
    tocSha256 = SelectedFileValues.digest(tocSha256);
    pageSha256 = SelectedFileValues.digest(pageSha256);
    verifierRevision = SelectedFileValues.revision(verifierRevision);
  }
}
