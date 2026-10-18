package io.vibeminer.application.inventory;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import java.util.concurrent.CompletionStage;

/**
 * Explicit asynchronous preparation; metadata readiness alone never supplies a retention target.
 */
public interface BuildInventoryPreparationService extends AutoCloseable {
  Operation prepare(BuildMetadataAdmission admission);

  interface Operation {
    CompletionStage<BuildInventoryPreparationResult> completion();

    BuildInventoryPreparationProgress progress();

    void requestCancellation();
  }

  @Override
  void close();
}
