package io.vibeminer.domain.content;

import io.vibeminer.domain.StoredObject;
import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Concrete metadata ledger facts. Historical aliases supply declarations only; their verifier
 * strings do not implicitly populate current decoded or structural attestations. Prospective ledger
 * values do not authorize publishing an alias or ready membership.
 */
public record MetadataContentProof(
    VerifiedSourceAlias originalAlias,
    String actualEncodingKey,
    String declaredContentKey,
    OptionalLong declaredDecodedBytes,
    Optional<DecodedContentAttestation> decodedAttestation,
    Optional<MetadataStructureAttestation> structuralAttestation)
    implements CanonicalContentProof {
  public MetadataContentProof {
    Objects.requireNonNull(originalAlias);
    Objects.requireNonNull(declaredDecodedBytes);
    Objects.requireNonNull(decodedAttestation);
    Objects.requireNonNull(structuralAttestation);
    actualEncodingKey = ContentValues.key(actualEncodingKey);
    declaredContentKey = ContentValues.key(declaredContentKey);
    MetadataStructureAttestation.Kind sourceKind = sourceKind(originalAlias);
    String[] tuple = originalAlias.source().immutableSourceKey().split(";", -1);
    if (tuple.length != 4
        || !tuple[0].equals(declaredContentKey)
        || !tuple[1].equals(actualEncodingKey))
      throw new IllegalArgumentException("Metadata alias content/encoding tuple mismatch");
    long sourceDecoded = positiveLength(tuple[2]), sourceEncoded = positiveLength(tuple[3]);
    if (sourceEncoded != originalAlias.object().length())
      throw new IllegalArgumentException(
          "Metadata alias physical length differs from source tuple");
    if (declaredDecodedBytes.isPresent() && declaredDecodedBytes.getAsLong() != sourceDecoded)
      throw new IllegalArgumentException(
          "Declared decoded length conflicts with original source tuple");
    if (decodedAttestation.isPresent()) {
      var decoded = decodedAttestation.get();
      if (!decoded.contentKey().equals(declaredContentKey)
          || decoded.actualDecodedBytes() != sourceDecoded)
        throw new IllegalArgumentException(
            "Decoded attestation conflicts with metadata declarations");
    }
    if (structuralAttestation.isPresent()
        && (decodedAttestation.isEmpty() || structuralAttestation.get().kind() != sourceKind))
      throw new IllegalArgumentException(
          "Metadata grammar requires matching explicit decoded attestation");
  }

  @Override
  public StoredObject object() {
    return originalAlias.object();
  }

  @Override
  public String contentKey() {
    return declaredContentKey;
  }

  /** Adds facts only; dropping or replacing an existing attestation is not a monotonic upgrade. */
  public MetadataContentProof withAttestations(
      Optional<DecodedContentAttestation> decoded,
      Optional<MetadataStructureAttestation> structural) {
    Objects.requireNonNull(decoded);
    Objects.requireNonNull(structural);
    if (decodedAttestation.isPresent() && !decodedAttestation.equals(decoded)
        || structuralAttestation.isPresent() && !structuralAttestation.equals(structural))
      throw new IllegalArgumentException(
          "Existing metadata attestation cannot be removed or replaced");
    return new MetadataContentProof(
        originalAlias,
        actualEncodingKey,
        declaredContentKey,
        declaredDecodedBytes,
        decoded,
        structural);
  }

  private static MetadataStructureAttestation.Kind sourceKind(VerifiedSourceAlias alias) {
    return switch (alias.source().namespace()) {
      case "wow.root.tsfm.v1v2" -> MetadataStructureAttestation.Kind.ROOT;
      case "tact.install.v1" -> MetadataStructureAttestation.Kind.INSTALL;
      case "tact.encoding.v1" -> MetadataStructureAttestation.Kind.ENCODING;
      case "tact.download.v1v2v3" -> MetadataStructureAttestation.Kind.DOWNLOAD;
      case "tact.download-size.v1" -> MetadataStructureAttestation.Kind.DOWNLOAD_SIZE;
      default ->
          throw new IllegalArgumentException("Unsupported original metadata source namespace");
    };
  }

  private static long positiveLength(String value) {
    if (!value.matches("[1-9][0-9]{0,18}"))
      throw new IllegalArgumentException("Invalid original metadata source length");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Metadata source length exceeds signed long", invalid);
    }
  }
}
