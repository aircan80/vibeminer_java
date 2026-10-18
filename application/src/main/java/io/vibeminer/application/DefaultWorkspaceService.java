package io.vibeminer.application;

import io.vibeminer.domain.BuildSnapshot;
import io.vibeminer.domain.Workspace;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class DefaultWorkspaceService implements WorkspaceService {
    private final WorkspaceRepository repository;
    private final Executor executor;
    private final Clock clock;
    public DefaultWorkspaceService(WorkspaceRepository repository, Executor executor, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public CompletionStage<List<Workspace>> list() {
        return CompletableFuture.supplyAsync(() -> List.copyOf(repository.list()), executor);
    }
    @Override public CompletionStage<Workspace> save(BuildSnapshot build, String locale, String name) {
        return CompletableFuture.supplyAsync(() -> repository.save(Workspace.create(build, locale, name, clock.instant())), executor);
    }
    @Override public CompletionStage<Workspace> rename(String id, String name) {
        return CompletableFuture.supplyAsync(() -> repository.rename(id, Workspace.validateName(name)), executor);
    }
    @Override public CompletionStage<Void> remove(String id) {
        return CompletableFuture.runAsync(() -> repository.remove(id), executor);
    }
}
