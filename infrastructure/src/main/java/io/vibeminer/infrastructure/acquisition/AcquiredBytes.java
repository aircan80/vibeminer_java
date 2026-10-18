package io.vibeminer.infrastructure.acquisition;

import java.time.Instant;
import java.util.Objects;

/** Configuration MD5 is verified. Loose bytes require separate BLTE EKey/chunk/CKey verification. */
public record AcquiredBytes(CdnRequest request, Instant receivedAt, byte[] bytes, String sha256,
        boolean configurationMd5Verified) {
    public AcquiredBytes {
        Objects.requireNonNull(request); Objects.requireNonNull(receivedAt); Objects.requireNonNull(sha256);
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
}
