package io.vibeminer.infrastructure.acquisition;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** A pinned request. The separate advertised ConfigPath is retained, never guessed into a route. */
public record CdnRequest(Source source, Kind kind, String key, int maximumBytes, long exactBytes) {
    public enum Kind { CONFIG, LOOSE_DATA }
    public record Source(String host, String path, String configPath, List<String> advertisedHosts) {
        private static final Set<String> SUPPORTED = Set.of("us.cdn.blizzard.com", "kr.cdn.blizzard.com",
                "level3.blizzard.com", "level3.ssl.blizzard.com");
        public Source {
            Objects.requireNonNull(host); Objects.requireNonNull(path); Objects.requireNonNull(configPath);
            advertisedHosts = List.copyOf(advertisedHosts);
            if (!SUPPORTED.contains(host) || !advertisedHosts.contains(host))
                throw new IllegalArgumentException("Host is not an advertised supported official CDN host");
            if (!path.matches("tpr/[a-zA-Z0-9_-]{1,64}") || !configPath.matches("tpr/[a-zA-Z0-9_/-]{1,128}")
                    || configPath.contains("//") || configPath.endsWith("/"))
                throw new IllegalArgumentException("Unsafe advertised CDN path");
        }
    }
    public CdnRequest {
        Objects.requireNonNull(source); Objects.requireNonNull(kind); Objects.requireNonNull(key);
        if (!key.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("Expected 128-bit protocol key");
        key = key.toLowerCase(Locale.ROOT);
        if (maximumBytes < 1 || maximumBytes > 64 * 1024 * 1024 || exactBytes < -1 || exactBytes > maximumBytes)
            throw new IllegalArgumentException("Invalid transfer budget");
        if (kind == Kind.CONFIG && (exactBytes != -1 || maximumBytes > 1024 * 1024))
            throw new IllegalArgumentException("Configuration limit exceeds policy");
        if (kind == Kind.LOOSE_DATA && exactBytes < 1)
            throw new IllegalArgumentException("Loose data requires a verified encoded length");
    }
    public static CdnRequest configuration(Source source, String key) {
        return new CdnRequest(source, Kind.CONFIG, key, 1024 * 1024, -1);
    }
    /** Caller supplies the encoded size from verified configuration; never schedules a download itself. */
    public static CdnRequest loose(Source source, String encodedKey, long encodedSize, int transferBudget) {
        return new CdnRequest(source, Kind.LOOSE_DATA, encodedKey, transferBudget, encodedSize);
    }
    public URI uri() {
        return URI.create("https://" + source.host() + "/" + source.path() + "/"
                + (kind == Kind.CONFIG ? "config" : "data") + "/" + key.substring(0, 2) + "/"
                + key.substring(2, 4) + "/" + key);
    }
}
