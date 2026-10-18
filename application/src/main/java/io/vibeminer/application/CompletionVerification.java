package io.vibeminer.application;

/**
 * Final gate for an inline-verifying encoded stream after its real EOF has been observed.
 * Implementations must reject incomplete/failed protocol verification by throwing, including
 * required decoded digest/length checks. Returning normally permits physical publication.
 * The store supplies no protocol implementation, guessed encoding-key checksum or caller boolean.
 */
@FunctionalInterface
public interface CompletionVerification {
    void verifyComplete(CancellationToken cancellation);
}
