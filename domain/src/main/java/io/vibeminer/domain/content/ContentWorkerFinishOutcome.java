package io.vibeminer.domain.content;

/** Actual worker's final cleanup disposition, never inferred from a cancelled consumer future. */
public enum ContentWorkerFinishOutcome {
  CONCLUSIVELY_UNPUBLISHED,
  UNCERTAIN,
  RETAINED
}
