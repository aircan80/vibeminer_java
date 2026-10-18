package io.vibeminer.application.buildretention;

/** Borrowed durable-pump notifications; methods admit bounded work rather than await downloads. */
public interface BuildRetentionActivation {
  /** Revalidate this exact persisted intent/generation before scheduling actual workers. */
  void resumeDownloadAll(BuildRetention.Intent acceptedIntent);

  /** Detach only bulk consumers from the prior generation, never shared foreground ownership. */
  void withdraw(BuildRetention.Target target, long priorGeneration);
}
