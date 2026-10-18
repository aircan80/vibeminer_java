package io.vibeminer.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Exact captured metadata bytes; URLs are provenance values, never executable sources here. */
public record SourceDocument(String sourceUrl, Instant capturedAt, String sha256, byte[] bytes) {
    public SourceDocument(String sourceUrl, Instant capturedAt, byte[] bytes) {
        this(sourceUrl, capturedAt, digest(bytes), bytes);
    }
    public SourceDocument {
        Objects.requireNonNull(sourceUrl); Objects.requireNonNull(capturedAt); Objects.requireNonNull(bytes);
        var authority = sourceUrl.startsWith("https://") ? sourceUrl.substring(8).split("[/#?]", 2)[0] : "";
        if (authority.isEmpty() || !authority.matches("[A-Za-z0-9.-]+(?::[0-9]{1,5})?") || sourceUrl.length() > 2048
                || sourceUrl.chars().anyMatch(value -> Character.isISOControl(value) || Character.isWhitespace(value))) {
            throw new IllegalArgumentException("Metadata source must have an HTTPS provenance URL");
        }
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("Metadata document exceeds 1 MiB");
        if (!digest(bytes).equals(sha256)) throw new IllegalArgumentException("Metadata digest does not match captured bytes");
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof SourceDocument value && sourceUrl.equals(value.sourceUrl)
            && capturedAt.equals(value.capturedAt) && sha256.equals(value.sha256) && Arrays.equals(bytes, value.bytes);
    }
    @Override public int hashCode() { return Objects.hash(sourceUrl, capturedAt, sha256); }
    private static String digest(byte[] bytes) {
        Objects.requireNonNull(bytes);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
