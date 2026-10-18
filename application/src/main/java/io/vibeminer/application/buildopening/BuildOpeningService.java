package io.vibeminer.application.buildopening;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import java.util.concurrent.CompletionStage;

/** One explicit Open action. Executor/adapters stay owned by the shared runtime. */
public interface BuildOpeningService extends AutoCloseable {
  OpenOperation open(BuildLibraryReference reference, String locale);

  interface OpenOperation {
    CompletionStage<BuildMetadataAdmission> completion();

    /** Nonblocking latest immutable observation; never starts I/O. */
    BuildOpenProgress progress();

    void requestCancellation();
  }

  @Override
  void close();
}
