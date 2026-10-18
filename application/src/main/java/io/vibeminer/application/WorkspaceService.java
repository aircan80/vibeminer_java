package io.vibeminer.application;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.Workspace;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Asynchronous named exact-build views; restore never refreshes latest pointers. */
public interface WorkspaceService {
    CompletionStage<List<Workspace>> list();
    CompletionStage<Workspace> save(BuildSnapshot build, String locale, String name);
    CompletionStage<Workspace> rename(String id, String name);
    CompletionStage<Void> remove(String id);
}
