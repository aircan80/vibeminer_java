package io.vibeminer.application;

import io.vibeminer.domain.BuildSnapshot;
import java.time.Instant;
import java.util.List;

/** Synthetic evidence shared by the shell and deterministic tests; never presented as CDN data. */
public final class FixtureDiscovery {
    private FixtureDiscovery() { }
    public static List<BuildSnapshot> builds() {
        return List.of(
            new BuildSnapshot("wowt", "World of Warcraft Retail", "PTR", "us", "DEMO-105",
                "11111111111111111111111111111115", "22222222222222222222222222222225", Instant.EPOCH, true),
            new BuildSnapshot("wowt", "World of Warcraft Retail", "PTR", "us", "DEMO-104",
                "11111111111111111111111111111114", "22222222222222222222222222222224", Instant.EPOCH, true));
    }
}
