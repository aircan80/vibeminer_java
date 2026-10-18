package io.vibeminer.application;

import io.vibeminer.domain.Workspace;
import java.util.List;
import java.util.Optional;

/** Atomic metadata persistence port. Implementations never contact discovery sources. */
public interface WorkspaceRepository {
    List<Workspace> list();
    Optional<Workspace> find(String id);
    Workspace save(Workspace workspace);
    Workspace rename(String id, String name);
    void remove(String id);
}
