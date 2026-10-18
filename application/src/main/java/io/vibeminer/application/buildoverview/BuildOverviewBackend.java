package io.vibeminer.application.buildoverview;

import static io.vibeminer.application.buildoverview.BuildOverview.*;

import io.vibeminer.application.CancellationToken;

/** Read-only current authority. No implicit preparation, transfer, mode change or repair. */
@FunctionalInterface
public interface BuildOverviewBackend {
  Snapshot read(Request request, ProgressSink progress, CancellationToken cancellation);

  @FunctionalInterface
  interface ProgressSink {
    void update(Progress value);
  }
}
