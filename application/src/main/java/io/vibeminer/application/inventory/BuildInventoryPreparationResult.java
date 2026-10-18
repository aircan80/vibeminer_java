package io.vibeminer.application.inventory;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildretention.BuildRetention;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapter-issued authority paired with its exact input; construction does not seal an inventory. An
 * offered download policy contains conservative sealed-member reservation ceilings, not measured
 * transfer activity. Empty means this result offers no Download all budget.
 */
public record BuildInventoryPreparationResult(
    BuildMetadataAdmission admission,
    BuildRetention.Target target,
    Optional<BuildRetention.Policy> downloadPolicy) {
  public BuildInventoryPreparationResult(
      BuildMetadataAdmission admission, BuildRetention.Target target) {
    this(admission, target, Optional.empty());
  }

  public BuildInventoryPreparationResult {
    Objects.requireNonNull(admission);
    Objects.requireNonNull(target);
    Objects.requireNonNull(downloadPolicy);
    if (!admission.reference().key().identity().equals(target.groupId())
        || !admission.reference().snapshotId().equals(target.snapshotId()))
      throw new IllegalArgumentException("Issued inventory target belongs to another build");
  }
}
