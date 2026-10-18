package io.vibeminer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** A named locale view over one immutable build; metadata coverage only at this stage. */
public record Workspace(String id, BuildSnapshot build, String locale, String name, Instant savedAt) {
    public Workspace {
        Objects.requireNonNull(build); Objects.requireNonNull(locale); Objects.requireNonNull(savedAt);
        if (!locale.matches("[a-z]{2}[A-Z]{2}")) throw new IllegalArgumentException("Choose a valid locale such as enUS");
        name = validateName(name);
        if (!identity(build, locale).equals(id)) throw new IllegalArgumentException("Workspace identity does not match pinned build/locale");
    }
    public static Workspace create(BuildSnapshot build, String locale, String name, Instant savedAt) {
        return new Workspace(identity(build, locale), build, locale, name, savedAt);
    }
    public Workspace renamed(String name) { return new Workspace(id, build, locale, name, savedAt); }
    public static String validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Workspace name must contain 1–120 printable characters");
        }
        return name.strip();
    }
    private static String identity(BuildSnapshot build, String locale) {
        Objects.requireNonNull(build); Objects.requireNonNull(locale);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((build.snapshotId() + "\n" + locale).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
