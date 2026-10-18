package io.vibeminer.application.retention;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.retention.RetentionScope;

/** Blocking immutable-definition and mutable-pin port, with transactional bounded views. */
public interface RetentionRepository {
  RetentionScope pin(RetentionScope scope, CancellationToken cancellation);

  /**
   * Explicitly replace an unfulfilled ROOT_METADATA candidate intent atomically. Prior differing
   * ROOT_METADATA definitions for this exact build remain as unpinned history. An attached
   * ROOT_MANIFEST source is immutable; differing candidates are rejected. Same-source size facts
   * remain consistent across history. Callers serialize different active candidates until workers
   * retire.
   */
  default RetentionScope replaceUnfulfilledRoot(
      RetentionScope scope, CancellationToken cancellation) {
    throw new RetentionException(
        RetentionException.Code.STORAGE_UNAVAILABLE,
        "Explicit root intent replacement is not configured");
  }

  void release(String scopeId, CancellationToken cancellation);

  RetentionView readView(String scopeId, RetentionLimits limits, CancellationToken cancellation);
}
