package io.vibeminer.application.buildreadiness;

import io.vibeminer.application.CancellationToken;
import java.util.List;

/** Internal blocking SQL port. These observations never authorize files, targets or Full. */
public interface BuildReadinessBackend {
  List<BuildReadiness.Status> read(List<BuildReadiness.Context> contexts, CancellationToken token);

  /** Accepts only a receipt minted after the borrowed real overview operation completed. */
  void record(CompletedAssessment assessment, CancellationToken token);
}
