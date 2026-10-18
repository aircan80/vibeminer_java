package io.vibeminer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable selected source identity; a version label alone never identifies a build. */
public record BuildSnapshot(String productId, String editionLabel, String channel,
        String region, String versionLabel, String buildConfig, String cdnConfig,
        Instant observedAt, boolean fixture) {
    public BuildSnapshot {
        productId = required(productId, "productId");
        editionLabel = required(editionLabel, "editionLabel");
        channel = required(channel, "channel");
        region = required(region, "region");
        versionLabel = required(versionLabel, "versionLabel");
        buildConfig = identity(buildConfig, "buildConfig");
        cdnConfig = identity(cdnConfig, "cdnConfig");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public String snapshotId() {
        try {
            var value = String.join("\n", productId, channel, region, versionLabel, buildConfig, cdnConfig,
                    Boolean.toString(fixture));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK lacks SHA-256", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(field + " must be a nonblank single line");
        }
        return value;
    }

    private static String identity(String value, String field) {
        required(value, field);
        if (!value.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException(field + " must be a 128-bit protocol identity");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
