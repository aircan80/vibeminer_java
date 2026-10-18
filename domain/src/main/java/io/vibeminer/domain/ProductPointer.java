package io.vibeminer.domain;

/** Publication sequence identifies a metadata update, never a game build. Unknown flags remain visible. */
public record ProductPointer(String flag, String sequence) {
    public enum Kind { CURRENT_VERSIONS, CDN_METADATA, BACKGROUND_DOWNLOAD, UNKNOWN }
    public ProductPointer {
        if (flag == null || flag.length() > 64 || flag.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid bounded publication flag");
        }
        if (sequence == null || !sequence.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid publication sequence");
    }
    public Kind kind() {
        return switch (flag) {
            case "" -> Kind.CURRENT_VERSIONS;
            case "cdn" -> Kind.CDN_METADATA;
            case "bgdl" -> Kind.BACKGROUND_DOWNLOAD;
            default -> Kind.UNKNOWN;
        };
    }
}
