package io.vibeminer.application.buildopening;

import java.util.*;

/**
 * One latest stage observation, not an event log. Wire counters cover encoded response body bytes
 * for this Open operation only, separately from decoded/index work and build-retention percentages.
 * Requirement counters cover only the required metadata closure. Verification may continue after
 * transfer reaches its known total; unknown totals do not imply zero or a percentage.
 */
public record BuildOpenProgress(
    Stage stage,
    long transferredWireBytes,
    OptionalLong expectedWireBytes,
    int verifiedRequirements,
    OptionalInt requiredRequirements) {
  public enum Stage {
    VALIDATING_CONTEXT,
    VERIFYING_RETAINED,
    RESOLVING_REQUIREMENTS,
    ACQUIRING_METADATA,
    VERIFYING_METADATA,
    COMMITTING_ADMISSION,
    COMPLETE
  }

  public BuildOpenProgress {
    Objects.requireNonNull(stage);
    Objects.requireNonNull(expectedWireBytes);
    Objects.requireNonNull(requiredRequirements);
    if (transferredWireBytes < 0
        || verifiedRequirements < 0
        || verifiedRequirements > BuildMetadataAdmission.MAXIMUM_REQUIREMENTS
        || expectedWireBytes.isPresent() && expectedWireBytes.getAsLong() < transferredWireBytes
        || requiredRequirements.isPresent()
            && (requiredRequirements.getAsInt() < verifiedRequirements
                || requiredRequirements.getAsInt() > BuildMetadataAdmission.MAXIMUM_REQUIREMENTS))
      throw new IllegalArgumentException(
          "Bounded measured progress with honest optional totals required");
    if (stage == Stage.COMPLETE
        && (requiredRequirements.isEmpty()
            || requiredRequirements.getAsInt() != verifiedRequirements))
      throw new IllegalArgumentException(
          "Complete progress requires every required metadata proof");
  }

  public static BuildOpenProgress initial() {
    return new BuildOpenProgress(
        Stage.VALIDATING_CONTEXT, 0, OptionalLong.empty(), 0, OptionalInt.empty());
  }
}
