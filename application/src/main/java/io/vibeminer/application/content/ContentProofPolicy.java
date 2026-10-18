package io.vibeminer.application.content;

import io.vibeminer.domain.content.CanonicalContentProof;
import io.vibeminer.domain.content.EncodedContentProof;
import io.vibeminer.domain.content.MetadataContentProof;
import java.util.Objects;

/**
 * Shared publication and monotonic-proof rules; this policy performs no cryptographic verification.
 */
public final class ContentProofPolicy {
  private ContentProofPolicy() {}

  public static void requirePublicationReady(CanonicalContentProof proof) {
    Objects.requireNonNull(proof);
    if (proof instanceof MetadataContentProof metadata
        && (metadata.decodedAttestation().isEmpty()
            || metadata.structuralAttestation().isEmpty())) {
      throw failure(
          "New metadata publication requires full decoded and matching parser attestations");
    }
  }

  public static void requireMonotonicUpgrade(
      CanonicalContentProof previous, CanonicalContentProof next) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(next);
    if (previous instanceof EncodedContentProof) {
      if (!previous.equals(next))
        throw failure("Encoded receipt cannot be replaced or converted implicitly");
      return;
    }
    if (!(previous instanceof MetadataContentProof oldMetadata)
        || !(next instanceof MetadataContentProof newMetadata))
      throw failure("Typed proof projection cannot implicitly convert capability");
    try {
      var expected =
          oldMetadata.withAttestations(
              newMetadata.decodedAttestation(), newMetadata.structuralAttestation());
      if (!expected.equals(newMetadata))
        throw failure("Proof upgrade changed original immutable source/object/declarations");
    } catch (IllegalArgumentException invalid) {
      throw new ContentClaimException(
          ContentClaimException.Code.INVALID_STATE,
          "Metadata capability upgrade is not monotonic",
          invalid);
    }
  }

  private static ContentClaimException failure(String message) {
    return new ContentClaimException(ContentClaimException.Code.INVALID_STATE, message);
  }
}
