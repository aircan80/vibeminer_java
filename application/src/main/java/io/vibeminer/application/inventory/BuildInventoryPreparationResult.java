package io.vibeminer.application.inventory;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildretention.BuildRetention;
import java.util.Objects;

/**
 * Adapter-issued authority paired with its exact input; construction does not seal an inventory.
 */
public record BuildInventoryPreparationResult(
    BuildMetadataAdmission admission, BuildRetention.Target target) {
  public BuildInventoryPreparationResult {
    Objects.requireNonNull(admission);
    Objects.requireNonNull(target);
    if (!admission.reference().key().identity().equals(target.groupId())
        || !admission.reference().snapshotId().equals(target.snapshotId()))
      throw new IllegalArgumentException("Issued inventory target belongs to another build");
  }
}
