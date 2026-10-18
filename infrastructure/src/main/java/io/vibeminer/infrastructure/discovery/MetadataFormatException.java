package io.vibeminer.infrastructure.discovery;

/** An official metadata document cannot be interpreted safely. */
public final class MetadataFormatException extends IllegalArgumentException {
    public MetadataFormatException(String message) { super(message); }
    public MetadataFormatException(String message, Throwable cause) { super(message, cause); }
}
