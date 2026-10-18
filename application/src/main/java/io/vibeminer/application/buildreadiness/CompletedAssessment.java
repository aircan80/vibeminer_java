package io.vibeminer.application.buildreadiness;

import io.vibeminer.application.buildoverview.BuildOverview;

/** Internal terminal-success receipt. No public DTO-to-recorder factory or readiness authority. */
public final class CompletedAssessment {
  private final BuildReadiness.Context context;
  private final BuildOverview.MetadataState metadata;
  private final BuildReadiness.Observation observation;

  private CompletedAssessment(BuildOverview.Snapshot snapshot, String inventoryScope) {
    metadata = snapshot.metadata();
    context =
        new BuildReadiness.Context(
            snapshot.reference(), snapshot.locale(), snapshot.scopeRevision(), inventoryScope);
    observation =
        new BuildReadiness.Observation(
            snapshot.readiness(),
            snapshot.measuredAt(),
            snapshot.revision(),
            BuildReadiness.Origin.from(snapshot.reference()),
            snapshot.target());
    new BuildReadiness.Status(
        context, BuildReadiness.AdmissionPresence.PRESENT, java.util.Optional.of(observation));
  }

  static CompletedAssessment successful(BuildOverview.Snapshot snapshot, String inventoryScope) {
    return new CompletedAssessment(snapshot, inventoryScope);
  }

  public BuildOverview.MetadataState metadata() {
    return metadata;
  }

  public BuildReadiness.Context context() {
    return context;
  }

  public BuildReadiness.Observation observation() {
    return observation;
  }
}
