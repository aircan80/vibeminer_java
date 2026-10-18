package io.vibeminer.infrastructure.bootstrap;

import io.vibeminer.infrastructure.acquisition.AcquiredBytes;
import io.vibeminer.infrastructure.acquisition.CdnRequest;
import java.util.concurrent.CompletableFuture;

/** Infrastructure seam for deterministic transport tests; production uses PinnedCdnTransport. */
@FunctionalInterface
interface BootstrapTransfer { CompletableFuture<AcquiredBytes> fetch(CdnRequest request); }
