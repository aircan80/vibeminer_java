package io.vibeminer.application.retention;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.retention.RetentionScope;

/** Blocking immutable-definition and mutable-pin port, with transactional bounded views. */
public interface RetentionRepository {
  RetentionScope pin(RetentionScope scope, CancellationToken cancellation);

  void release(String scopeId, CancellationToken cancellation);

  RetentionView readView(String scopeId, RetentionLimits limits, CancellationToken cancellation);
}
