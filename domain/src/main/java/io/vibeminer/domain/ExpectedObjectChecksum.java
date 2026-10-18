package io.vibeminer.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Expected checksum over the entire supplied stream and its exact byte length.
 * This is not a general Blizzard encoding-key verifier: BLTE verification is protocol-specific.
 */
public record ExpectedObjectChecksum(ChecksumAlgorithm algorithm, String hexDigest, long length) {
    public ExpectedObjectChecksum {
        Objects.requireNonNull(algorithm, "algorithm");
        if (hexDigest == null || !hexDigest.matches("[0-9a-fA-F]{" + algorithm.hexCharacters() + "}")) {
            throw new IllegalArgumentException("Invalid expected whole-stream checksum");
        }
        if (length < 0) throw new IllegalArgumentException("Expected object length cannot be negative");
        hexDigest = hexDigest.toLowerCase(Locale.ROOT);
    }
}
