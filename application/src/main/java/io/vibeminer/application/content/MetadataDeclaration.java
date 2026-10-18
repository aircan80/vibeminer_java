package io.vibeminer.application.content;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One exact build's configuration declarations, separate from any retained representation. Values
 * validate shape and identity only; construction authenticates neither configuration nor bytes.
 */
public record MetadataDeclaration(
    BuildSnapshot build,
    VerifiedSourceAlias configuration,
    MetadataStructureAttestation.Kind kind,
    ContentSlotKey slot,
    Optional<String> preferredEncodingKey,
    OptionalLong declaredEncodedBytes,
    OptionalLong declaredDecodedBytes) {
  public MetadataDeclaration {
    Objects.requireNonNull(build);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(slot);
    Objects.requireNonNull(preferredEncodingKey);
    Objects.requireNonNull(declaredEncodedBytes);
    Objects.requireNonNull(declaredDecodedBytes);
    if (!build.productId().equals("wow") || !slot.namespace().equals(ContentSlotKey.WOW_NAMESPACE))
      throw new IllegalArgumentException("Metadata reuse requires the reviewed raw wow namespace");
    var expectedConfiguration =
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1");
    if (!configuration.source().equals(expectedConfiguration))
      throw new IllegalArgumentException("Configuration belongs to a different exact build");
    preferredEncodingKey =
        preferredEncodingKey.map(
            value -> {
              if (!value.matches("[0-9a-fA-F]{32}"))
                throw new IllegalArgumentException("Expected complete preferred encoding key");
              return value.toLowerCase(Locale.ROOT);
            });
    if (declaredEncodedBytes.isPresent()
            && (declaredEncodedBytes.getAsLong() < 1 || preferredEncodingKey.isEmpty())
        || declaredDecodedBytes.isPresent() && declaredDecodedBytes.getAsLong() < 1)
      throw new IllegalArgumentException("Declared metadata lengths must be positive and scoped");
  }

  /** Checks immutable facts; the executing gateway must still verify physical bytes and grammar. */
  void requireStrongActual(MetadataContentProof actual) {
    Objects.requireNonNull(actual);
    if (!actual.contentKey().equals(slot.contentKey())
        || actual.decodedAttestation().isEmpty()
        || actual.structuralAttestation().isEmpty()
        || actual.structuralAttestation().get().kind() != kind)
      throw new IllegalArgumentException(
          "Canonical metadata lacks matching full scoped capability");
    if (preferredEncodingKey.isPresent()
        && preferredEncodingKey.get().equals(actual.actualEncodingKey())
        && declaredEncodedBytes.isPresent()
        && declaredEncodedBytes.getAsLong() != actual.object().length())
      throw new IllegalArgumentException(
          "Same encoding declaration conflicts with actual encoded length");
    if (declaredDecodedBytes.isPresent()
        && declaredDecodedBytes.getAsLong()
            != actual.decodedAttestation().get().actualDecodedBytes())
      throw new IllegalArgumentException(
          "Declared decoded length conflicts with canonical content");
  }
}
