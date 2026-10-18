package io.vibeminer.application.buildretention;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Thin asynchronous mode policy over the trusted inventory-backed durable repository. */
public interface BuildRetentionModeService extends AutoCloseable {
  /** No saved intent means the default On demand preference; this creates no durable authority. */
  Operation read(BuildRetention.Target target);

  Operation setMode(
      BuildRetention.Target target,
      long expectedGeneration,
      BuildRetention.Mode mode,
      BuildRetention.Policy policy);

  /** Explicit restore for a prepared target; global startup enumeration is not implied. */
  Operation resume(BuildRetention.Target target);

  record ActivationFailure(BuildRetentionException.Code code, String message) {
    public ActivationFailure {
      java.util.Objects.requireNonNull(code);
      java.util.Objects.requireNonNull(message);
      if (message.isBlank() || message.length() > 4096)
        throw new IllegalArgumentException("Bounded activation failure required");
    }
  }

  /**
   * A pump failure never disguises the successfully committed durable preference as rolled back.
   */
  record View(
      BuildRetention.Target target,
      Optional<BuildRetention.Intent> intent,
      Optional<ActivationFailure> activationFailure) {
    public View {
      java.util.Objects.requireNonNull(target);
      java.util.Objects.requireNonNull(intent);
      java.util.Objects.requireNonNull(activationFailure);
      if (intent.isPresent() && !intent.get().target().equals(target)
          || activationFailure.isPresent() && intent.isEmpty())
        throw new IllegalArgumentException("Exact persisted target required");
    }

    public BuildRetention.Mode mode() {
      return intent.map(BuildRetention.Intent::mode).orElse(BuildRetention.Mode.ON_DEMAND);
    }
  }

  interface Operation {
    CompletionStage<View> completion();

    void requestCancellation();
  }

  /** Cancels owned actual actions cooperatively; borrowed executor/repository/pump remain open. */
  @Override
  void close();
}
