package io.vibeminer.application.buildreadiness;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** SQL-only visible-page overlay; selection never starts a physical assessment. */
public interface BuildReadinessService extends AutoCloseable {
  Operation read(List<BuildReadiness.Context> contexts);

  interface Operation {
    CompletionStage<List<BuildReadiness.Status>> completion();

    void requestCancellation();
  }

  @Override
  void close();
}
