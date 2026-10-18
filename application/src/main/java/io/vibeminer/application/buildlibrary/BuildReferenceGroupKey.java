package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Region-independent presentation grouping, never a replacement snapshot or acquisition proof. */
public record BuildReferenceGroupKey(
    String productId,
    String channel,
    String versionLabel,
    String buildConfig,
    String cdnConfig,
    String upstreamBuildId,
    String productConfig,
    Map<String, String> additionalReferences) {
  private static final Set<String> KNOWN =
      Set.of("Region", "VersionsName", "BuildConfig", "CDNConfig", "BuildId", "ProductConfig");

  public BuildReferenceGroupKey {
    new DiscoveryHistoryFilter(productId, Optional.empty(), Optional.empty());
    channel = BuildLibraryValues.text(channel, 4096);
    versionLabel = BuildLibraryValues.text(versionLabel, 4096);
    buildConfig = BuildLibraryValues.config(buildConfig, false);
    cdnConfig = BuildLibraryValues.config(cdnConfig, false);
    var evidence = new BuildEvidence(upstreamBuildId, productConfig, List.of());
    productConfig = evidence.productConfig();
    additionalReferences = Map.copyOf(additionalReferences);
    if (additionalReferences.size() > 64)
      throw new IllegalArgumentException("Bounded additional references required");
    long length = 0;
    for (var entry : additionalReferences.entrySet()) {
      var key = entry.getKey();
      var value = entry.getValue();
      if (KNOWN.contains(key)
          || key.length() > 16384
          || !key.matches("[A-Za-z][A-Za-z0-9_]*")
          || value.indexOf('\0') >= 0
          || value.indexOf('\n') >= 0
          || value.indexOf('|') >= 0
          || !StandardCharsets.UTF_8.newEncoder().canEncode(value))
        throw new IllegalArgumentException("Lossless unknown identity references required");
      length += value.length();
    }
    if (length > 16384)
      throw new IllegalArgumentException("Additional references exceed source row limit");
  }

  public static BuildReferenceGroupKey from(DiscoveryEvent.ObservedBuild row) {
    var b = row.build();
    var unknown = new HashMap<>(row.versionsValues());
    KNOWN.forEach(unknown::remove);
    return new BuildReferenceGroupKey(
        b.productId(),
        b.channel(),
        b.versionLabel(),
        b.buildConfig(),
        b.cdnConfig(),
        b.evidence().upstreamBuildId(),
        b.evidence().productConfig(),
        unknown);
  }

  public String identity() {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (var field :
          List.of(
              "build-reference-group-v1",
              productId,
              channel,
              versionLabel,
              buildConfig,
              cdnConfig,
              upstreamBuildId,
              productConfig)) part(hash, field);
      hash.update(ByteBuffer.allocate(4).putInt(additionalReferences.size()).array());
      for (var key : new TreeSet<>(additionalReferences.keySet())) {
        part(hash, key);
        part(hash, additionalReferences.get(key));
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void part(MessageDigest hash, String value) {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
    hash.update(bytes);
  }

  public String snapshotIdFor(String region) {
    new DiscoveryHistoryFilter(productId, Optional.of(region), Optional.empty());
    return new BuildSnapshot(
            productId,
            productId,
            channel,
            region,
            versionLabel,
            buildConfig,
            cdnConfig,
            Instant.EPOCH,
            false,
            new BuildEvidence(upstreamBuildId, productConfig, List.of()))
        .snapshotId();
  }
}
