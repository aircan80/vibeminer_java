package io.vibeminer.application.itemexport;

import java.util.concurrent.CompletionStage;

/** One actual job and one issued immutable snapshot; all adapters and executor remain borrowed. */
public interface ItemEvidenceService extends AutoCloseable {
  CompletionStage<ItemEvidenceSnapshot> capture(ItemEvidenceSelection selection);

  SaveOperation save(ItemEvidenceSnapshot snapshot, String authorizedReplacementPath);

  /** Cancellation is requested separately so confirmed publication remains observable. */
  interface SaveOperation {
    CompletionStage<ItemEvidenceReceipt> completion();

    void requestCancellation();
  }

  @Override
  void close();
}
