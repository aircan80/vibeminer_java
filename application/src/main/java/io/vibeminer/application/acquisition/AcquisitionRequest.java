package io.vibeminer.application.acquisition;

import io.vibeminer.domain.VerifiedSourceAlias;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One consumer of shared work. A committed success may win a concurrent cancellation request. */
public final class AcquisitionRequest {
    private final CompletableFuture<VerifiedSourceAlias> result;
    AcquisitionRequest(CompletableFuture<VerifiedSourceAlias> result) { this.result = result; }
    /** Callbacks may run on the completing worker; clients must avoid blocking and marshal UI updates. */
    public CompletionStage<VerifiedSourceAlias> completion() { return result; }
    public boolean cancel() { return result.cancel(true); }
}
