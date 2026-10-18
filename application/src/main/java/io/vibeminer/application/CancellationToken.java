package io.vibeminer.application;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation checked between bounded units; it does not interrupt arbitrary streams. */
@FunctionalInterface
public interface CancellationToken {
    boolean isCancellationRequested();
    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) throw new CancellationException("Operation cancelled");
    }
    static CancellationToken none() { return () -> false; }
}
