package io.vibeminer.infrastructure.workspace;

import java.nio.file.Path;
import java.util.Locale;

/** Computes only VibeMiner-owned paths. Never probes a game or Battle.net installation. */
public final class AppDataDirectory {
    private AppDataDirectory() { }
    public static Path resolve() {
        var override = System.getProperty("vibeminer.dataDir");
        if (override != null && !override.isBlank()) {
            var path = Path.of(override);
            if (!path.isAbsolute()) throw new IllegalArgumentException("vibeminer.dataDir must be an absolute app-owned path");
            return path.normalize();
        }
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        var home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            var base = System.getenv("LOCALAPPDATA");
            return (base == null || base.isBlank() ? home.resolve("AppData/Local") : Path.of(base)).resolve("VibeMiner");
        }
        if (os.contains("mac")) return home.resolve("Library/Application Support/VibeMiner");
        var xdg = System.getenv("XDG_DATA_HOME");
        var base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute() ? Path.of(xdg) : home.resolve(".local/share");
        return base.resolve("vibeminer");
    }
}
