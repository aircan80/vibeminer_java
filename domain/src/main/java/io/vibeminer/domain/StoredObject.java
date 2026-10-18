package io.vibeminer.domain;

import java.util.Objects;

/** An immutable physical object; this says nothing about build membership or decoded support. */
public record StoredObject(ObjectDigest digest, long length) {
    public StoredObject {
        Objects.requireNonNull(digest, "digest");
        if (length < 0) throw new IllegalArgumentException("Stored object length cannot be negative");
    }
}
