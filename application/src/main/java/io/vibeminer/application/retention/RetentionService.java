package io.vibeminer.application.retention;

import io.vibeminer.domain.retention.RetentionScope;
import java.util.concurrent.CompletionStage;

/** Explicit retention policy only; none of these actions acquire or delete bytes. */
public interface RetentionService extends AutoCloseable {
  CompletionStage<RetentionScope> pin(RetentionScope scope);

  CompletionStage<Void> release(String scopeId);

  CompletionStage<RetentionMeasurement> measure(String scopeId, RetentionLimits limits);

  @Override
  void close();
}
