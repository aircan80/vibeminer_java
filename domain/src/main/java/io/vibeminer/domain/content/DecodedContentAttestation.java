package io.vibeminer.domain.content;

/**
 * A completing verifier's full decoded CKey/length attestation, not a declaration or grammar claim.
 */
public record DecodedContentAttestation(
    String contentKey, long actualDecodedBytes, String verifierRevision) {
  public DecodedContentAttestation {
    contentKey = ContentValues.key(contentKey);
    verifierRevision = ContentValues.revision(verifierRevision);
    if (actualDecodedBytes < 0)
      throw new IllegalArgumentException("Decoded length must be nonnegative");
  }
}
