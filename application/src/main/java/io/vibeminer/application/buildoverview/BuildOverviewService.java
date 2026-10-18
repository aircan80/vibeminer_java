package io.vibeminer.application.buildoverview;

import static io.vibeminer.application.buildoverview.BuildOverview.*;

import java.util.concurrent.CompletionStage;

public interface BuildOverviewService extends AutoCloseable {
  Operation read(Request request);

  interface Operation {
    CompletionStage<Snapshot> completion();

    Progress progress();

    void requestCancellation();
  }

  @Override
  void close();
}
