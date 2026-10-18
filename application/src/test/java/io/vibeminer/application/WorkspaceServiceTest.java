package io.vibeminer.application;

import io.vibeminer.domain.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceServiceTest {
    @Test void persistenceRunsOnlyOnWorkerAndRestoreRequiresNoDiscovery() {
        var scheduled = new ArrayDeque<Runnable>();
        var repository = new MemoryRepository();
        var service = new DefaultWorkspaceService(repository, scheduled::add, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var save = service.save(FixtureDiscovery.builds().getFirst(), "enUS", "Pinned build").toCompletableFuture();
        assertFalse(save.isDone()); assertTrue(repository.values.isEmpty());
        scheduled.remove().run();
        var saved = save.join(); assertEquals(Instant.EPOCH, saved.savedAt());
        var restored = service.list().toCompletableFuture();
        scheduled.remove().run(); assertEquals(List.of(saved), restored.join());
        var renamed = service.rename(saved.id(), "New label").toCompletableFuture();
        scheduled.remove().run(); assertEquals(saved.build(), renamed.join().build());
    }
    @Test void invalidInputNeverWritesRepository() {
        var repository = new MemoryRepository();
        var service = new DefaultWorkspaceService(repository, Runnable::run, Clock.systemUTC());
        assertThrows(java.util.concurrent.CompletionException.class, () -> service.save(
            FixtureDiscovery.builds().getFirst(), "invalid", "Name").toCompletableFuture().join());
        assertTrue(repository.values.isEmpty());
    }
    private static final class MemoryRepository implements WorkspaceRepository {
        final Map<String,Workspace> values = new HashMap<>();
        public List<Workspace> list() { return List.copyOf(values.values()); }
        public Optional<Workspace> find(String id) { return Optional.ofNullable(values.get(id)); }
        public Workspace save(Workspace value) { values.put(value.id(),value); return value; }
        public Workspace rename(String id,String name) { var value=values.get(id).renamed(name); values.put(id,value); return value; }
        public void remove(String id) { values.remove(id); }
    }
}
