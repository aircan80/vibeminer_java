package io.vibeminer.application.bootstrap;

import io.vibeminer.application.content.MetadataRepairPlan;
import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.SourceDocument;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.util.Objects;
import java.util.Optional;

/** Exact, small install-manifest scope. This does not describe the game's root/asset catalogue. */
public record InstallPlan(
    BuildSnapshot build,
    SourceDocument configuration,
    String contentKey,
    String encodingKey,
    long decodedBytes,
    long encodedBytes,
    Optional<MetadataRepairPlan> repair) {
  public InstallPlan(
      BuildSnapshot build,
      SourceDocument configuration,
      String contentKey,
      String encodingKey,
      long decodedBytes,
      long encodedBytes) {
    this(
        build,
        configuration,
        contentKey,
        encodingKey,
        decodedBytes,
        encodedBytes,
        Optional.empty());
  }

  public InstallPlan {
    Objects.requireNonNull(repair);
    Objects.requireNonNull(build);
    Objects.requireNonNull(configuration);
    if (contentKey == null
        || !contentKey.matches("[0-9a-f]{32}")
        || encodingKey == null
        || !encodingKey.matches("[0-9a-f]{32}")
        || decodedBytes < 10
        || encodedBytes < 9) throw new IllegalArgumentException("Invalid install-manifest plan");
    if (repair.isPresent()) {
      var request = repair.get().request();
      if (request.kind() != MetadataStructureAttestation.Kind.INSTALL
          || !request.build().snapshotId().equals(build.snapshotId())
          || !request.slot().contentKey().equals(contentKey)
          || !request.requestedEncodingKey().equals(encodingKey)
          || request.requestedEncodedBytes() != encodedBytes
          || request.declaredDecodedBytes().orElse(-1) != decodedBytes
          || !request.configuration().evidence().sourceUrl().equals(configuration.sourceUrl())
          || !request.configuration().evidence().verifiedAt().equals(configuration.capturedAt())
          || repair.get().cost().maximumPayloadRequests() != 1)
        throw new IllegalArgumentException("Repair consent differs from exact install plan");
    }
  }
}
