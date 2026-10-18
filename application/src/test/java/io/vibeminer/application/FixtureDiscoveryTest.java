package io.vibeminer.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixtureDiscoveryTest {
    @Test void fixturesAreExplicitlySyntheticAndCannotMutateAcrossViews() {
        var builds = FixtureDiscovery.builds();
        assertTrue(builds.stream().allMatch(build -> build.fixture() && build.versionLabel().startsWith("DEMO-")));
        assertEquals(2, builds.stream().map(build -> build.snapshotId()).distinct().count());
        assertThrows(UnsupportedOperationException.class, builds::clear);
    }
}
