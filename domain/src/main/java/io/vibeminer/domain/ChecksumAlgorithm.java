package io.vibeminer.domain;

/** Explicit whole-stream checksum algorithms supported at the object-store boundary. */
public enum ChecksumAlgorithm {
    MD5("MD5", 32), SHA256("SHA-256", 64);
    private final String jcaName;
    private final int hexCharacters;
    ChecksumAlgorithm(String jcaName, int hexCharacters) { this.jcaName = jcaName; this.hexCharacters = hexCharacters; }
    public String jcaName() { return jcaName; }
    public int hexCharacters() { return hexCharacters; }
}
