package io.vibeminer.domain;

import java.util.Locale;

/** Full SHA-256 identity of physical bytes owned by VibeMiner, not a Blizzard CKey/EKey. */
public record ObjectDigest(String value) {
    public ObjectDigest {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid SHA-256 object digest");
        value = value.toLowerCase(Locale.ROOT);
    }
}
