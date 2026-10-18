package io.vibeminer.infrastructure.discovery;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Source bytes and capture provenance; neither is a claim of decoded game content. */
public record CapturedMetadata(URI source, Instant observedAt, byte[] bytes) {
    public CapturedMetadata {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observedAt, "observedAt");
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }
}
