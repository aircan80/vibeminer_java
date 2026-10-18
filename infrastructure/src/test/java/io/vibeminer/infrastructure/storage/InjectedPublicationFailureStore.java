package io.vibeminer.infrastructure.storage;

import java.nio.file.*;
import java.util.concurrent.atomic.*;

/** Test-only ENOSPC-like atomic-promotion fault, never a claim that a real volume was filled. */
public final class InjectedPublicationFailureStore {
  private final boolean moveBeforeFailure;

  public InjectedPublicationFailureStore() {
    this(false);
  }

  public InjectedPublicationFailureStore(boolean moveBeforeFailure) {
    this.moveBeforeFailure = moveBeforeFailure;
  }

  private final AtomicBoolean enabled = new AtomicBoolean();
  private final AtomicInteger failures = new AtomicInteger();
  private final AtomicLong stagedBytes = new AtomicLong();

  public FileObjectStore create(Path root) {
    return new FileObjectStore(
        root,
        (stage, target, replace) -> {
          if (enabled.get()) {
            failures.incrementAndGet();
            stagedBytes.set(Files.size(stage));
            if (moveBeforeFailure) Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            throw new FileSystemException(
                stage.toString(),
                target.toString(),
                "ENOSPC: injected no space at atomic publication");
          }
          if (replace)
            Files.move(
                stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          else Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        });
  }

  public void enable() {
    enabled.set(true);
  }

  public void disable() {
    enabled.set(false);
  }

  public int failures() {
    return failures.get();
  }

  public long stagedBytes() {
    return stagedBytes.get();
  }
}
