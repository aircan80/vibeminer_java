package io.vibeminer.application.bootstrap;

import io.vibeminer.domain.BuildSnapshot;
import java.util.concurrent.CompletionStage;

/** Explicit asynchronous actions. Cancel the returned future to detach this consumer. */
public interface BootstrapService extends AutoCloseable {
    /** Downloads only the bounded pinned build configuration, never a bootstrap content object. */
    CompletionStage<InstallPlan> inspectConfiguration(BuildSnapshot build);
    /** Separate consent/action to acquire precisely the plan's small install-manifest object. */
    CompletionStage<InstallInspection> download(InstallPlan plan);
    /** Offline-only: verifies retained physical bytes; never performs HTTP. */
    CompletionStage<InstallInspection> reopen(BuildSnapshot build);
    @Override void close();
}
