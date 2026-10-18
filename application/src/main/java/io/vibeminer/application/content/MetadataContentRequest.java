package io.vibeminer.application.content;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.ResolvedSourceIdentity;
import io.vibeminer.domain.VerifiedSourceAlias;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Initial metadata guard request. Values bind exact config and requested cost; construction does
 * not authenticate config bytes or root/EN selection. The existing gateway must replay those.
 */
public record MetadataContentRequest(
    BuildSnapshot build,
    VerifiedSourceAlias configuration,
    MetadataStructureAttestation.Kind kind,
    ContentSlotKey slot,
    String requestedEncodingKey,
    long requestedEncodedBytes,
    OptionalLong declaredDecodedBytes) {
  public MetadataContentRequest {
    Objects.requireNonNull(build);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(slot);
    Objects.requireNonNull(declaredDecodedBytes);
    if (!build.productId().equals("wow"))
      throw new IllegalArgumentException(
          "Only explicitly reviewed raw wow content namespace is supported");
    var expected =
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1");
    if (!configuration.source().equals(expected))
      throw new IllegalArgumentException(
          "Metadata request config belongs to a different exact build");
    if (requestedEncodingKey == null
        || !requestedEncodingKey.matches("[0-9a-fA-F]{32}")
        || requestedEncodedBytes < 1
        || declaredDecodedBytes.isPresent() && declaredDecodedBytes.getAsLong() < 1)
      throw new IllegalArgumentException("Known positive metadata acquisition geometry required");
    requestedEncodingKey = requestedEncodingKey.toLowerCase(Locale.ROOT);
  }
}
