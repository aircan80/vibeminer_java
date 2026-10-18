package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.lookup.FileLookupException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.lookup.FileLookupException;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.infrastructure.acquisition.*;

import java.time.Duration;
import java.util.concurrent.*;

/** One bounded operation ledger. Metadata shares one ledger across config and selected EN reads. */
final class LookupWork {
    private final CancellationToken cancellation;
    private final long wireLimit, decodedLimit;
    private final int requestLimit;
    private final long started = System.nanoTime();
    private final long deadline = Duration.ofMinutes(10).toNanos();
    private long wire, decoded;
    private int requests;

    LookupWork(LookupBudgets budgets, CancellationToken cancellation, boolean metadata) {
        this.cancellation = cancellation;
        wireLimit =
                metadata ? budgets.maximumMetadataWireBytes() : budgets.maximumRootEncodedBytes();
        decodedLimit =
                metadata
                        ? budgets.maximumMetadataDecodedBytes()
                        : budgets.maximumDecodedWorkBytes();
        requestLimit =
                metadata
                        ? budgets.maximumMetadataRequests()
                        : (int) Math.min(Integer.MAX_VALUE, (long) budgets.maximumChunks() + 2);
    }

    void check() {
        cancellation.throwIfCancellationRequested();
        if (System.nanoTime() - started >= deadline)
            throw fail(BUDGET_EXCEEDED, "Lookup operation deadline exceeded");
    }

    boolean cancelled() {
        check();
        return false;
    }

    void reserve(long bytes) {
        check();
        if (bytes < 1 || bytes > wireLimit - wire || requests >= requestLimit)
            throw fail(BUDGET_EXCEEDED, "Next transfer exceeds lookup wire/request budget");
        wire += bytes;
        requests++;
    }

    void settle(long reserved, long actual) {
        if (actual < 0 || actual > reserved)
            throw fail(INTEGRITY_FAILURE, "Source exceeded reserved transfer budget");
        wire -= reserved - actual;
        check();
    }

    void decode(long bytes) {
        check();
        if (bytes < 0 || bytes > decodedLimit - decoded)
            throw fail(BUDGET_EXCEEDED, "Lookup decoded work exceeds budget");
        decoded += bytes;
    }

    <T> T await(CompletableFuture<T> future) {
        try {
            while (true) {
                check();
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waiting) {
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("File lookup cancelled");
        } catch (ExecutionException failed) {
            throw failure(failed.getCause());
        } catch (RuntimeException failed) {
            future.cancel(true);
            throw failed;
        }
    }

    static RuntimeException failure(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        if (error instanceof FileLookupException typed) return typed;
        if (error instanceof CancellationException cancelled) return cancelled;
        if (error instanceof AcquisitionException source
                && source.reason() == AcquisitionException.Reason.CANCELLED)
            return new CancellationException(source.getMessage());
        if (error instanceof AcquisitionException source)
            return new FileLookupException(
                    switch (source.reason()) {
                        case UNAVAILABLE -> UNAVAILABLE;
                        case INTEGRITY -> INTEGRITY_FAILURE;
                        case LIMIT -> BUDGET_EXCEEDED;
                        case CANCELLED -> INVALID_REQUEST;
                        default -> UNAVAILABLE;
                    },
                    source.getMessage(),
                    source);
        if (error instanceof SourceContextException source)
            return new FileLookupException(
                    switch (source.reason()) {
                        case UNSUPPORTED -> UNSUPPORTED;
                        case MALFORMED, INTEGRITY -> INTEGRITY_FAILURE;
                    },
                    source.getMessage(),
                    source);
        if (error instanceof io.vibeminer.application.ObjectStoreException stored)
            return new FileLookupException(
                    switch (stored.code()) {
                        case MISSING_OBJECT -> MISSING_LOCAL;
                        case INTEGRITY_FAILURE -> INTEGRITY_FAILURE;
                        case LIMIT_EXCEEDED -> BUDGET_EXCEEDED;
                        default -> STORAGE_FAILURE;
                    },
                    stored.getMessage(),
                    stored);
        if (error instanceof io.vibeminer.protocol.tact.blte.BlteException protocol)
            return protocol(protocol.reason().name(), protocol);
        if (error instanceof io.vibeminer.protocol.tact.encoding.EncodingManifestException protocol)
            return protocol(protocol.reason().name(), protocol);
        if (error instanceof io.vibeminer.protocol.tact.config.TactConfigException protocol)
            return protocol(protocol.reason().name(), protocol);
        if (error instanceof io.vibeminer.format.wow.root.StreamingRootException protocol) {
            if (protocol.getCause() != null) return failure(protocol.getCause());
            return protocol(protocol.reason().name(), protocol);
        }
        if (error instanceof java.io.IOException io) {
            if (io.getCause() != null) return failure(io.getCause());
            return new FileLookupException(
                    STORAGE_FAILURE, "Unable to read retained lookup bytes", io);
        }
        return new FileLookupException(
                INTEGRITY_FAILURE, "Lookup source could not be interpreted", error);
    }

    private static RuntimeException protocol(String reason, Exception cause) {
        if (reason.equals("CANCELLED")) return new CancellationException(cause.getMessage());
        return new FileLookupException(
                switch (reason) {
                    case "LIMIT" -> BUDGET_EXCEEDED;
                    case "UNSUPPORTED", "MISSING_FIELD" -> UNSUPPORTED;
                    case "IO" -> STORAGE_FAILURE;
                    default -> INTEGRITY_FAILURE;
                },
                cause.getMessage(),
                cause);
    }

    static FileLookupException fail(FileLookupException.Code code, String message) {
        return new FileLookupException(code, message);
    }
}
