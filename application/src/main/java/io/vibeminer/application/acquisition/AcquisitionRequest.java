package io.vibeminer.application.acquisition;

import io.vibeminer.domain.StoredObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One consumer of shared work. A committed success may win a concurrent cancellation request. */
public final class AcquisitionRequest {
    private final CompletableFuture<StoredObject> result;
    AcquisitionRequest(CompletableFuture<StoredObject> result) { this.result = result; }
    /** Callbacks may run on the completing worker; clients must avoid blocking and marshal UI updates. */
    public CompletionStage<StoredObject> completion() { return result; }
    public boolean cancel() { return result.cancel(true); }
}
